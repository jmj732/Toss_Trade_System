package com.jmj.trade.refresh;

import com.jmj.trade.account.AccountSyncException;
import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.analysis.PortfolioAnalysisException;
import com.jmj.trade.analysis.PortfolioAnalysisWorkflowService;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.observability.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Periodically refreshes active broker connections by reusing the manual sync and analysis
 * workflows. The sweep never submits orders and never produces order suggestions.
 */
final class ScheduledPortfolioRefreshService {

    private static final Logger LOG =
            LoggerFactory.getLogger(ScheduledPortfolioRefreshService.class);
    private static final String OPERATION = "scheduled_refresh";

    private final JdbcTemplate jdbc;
    private final ScheduledRefreshLease lease;
    private final AccountSyncService syncService;
    private final PortfolioAnalysisWorkflowService analysisService;
    private final MeterRegistry registry;
    private final ScheduledRefreshProperties properties;
    private final Duration syncStaleAfter;

    ScheduledPortfolioRefreshService(
            JdbcTemplate jdbc,
            ScheduledRefreshLease lease,
            AccountSyncService syncService,
            PortfolioAnalysisWorkflowService analysisService,
            MeterRegistry registry,
            ScheduledRefreshProperties properties,
            Duration syncStaleAfter
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.syncService = Objects.requireNonNull(syncService, "syncService");
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.syncStaleAfter = Objects.requireNonNull(syncStaleAfter, "syncStaleAfter");
        if (!syncStaleAfter.isPositive()) {
            throw new IllegalArgumentException("syncStaleAfter must be positive");
        }
    }

    ScheduledRefreshResult refresh() {
        var owner = UUID.randomUUID();
        if (!lease.acquire(owner)) {
            LOG.atInfo()
                    .addKeyValue("operation", OPERATION)
                    .addKeyValue("outcome", "skipped")
                    .log("scheduled refresh sweep skipped because the lease is held elsewhere");
            return ScheduledRefreshResult.notAcquired();
        }
        try {
            return sweep();
        } finally {
            lease.release(owner);
        }
    }

    private ScheduledRefreshResult sweep() {
        var startedAt = System.nanoTime();
        var refreshed = 0;
        var failed = 0;
        var skipped = 0;
        for (var candidate : dueConnections()) {
            switch (refreshOne(candidate)) {
                case REFRESHED -> refreshed++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
        }
        LOG.atInfo()
                .addKeyValue("operation", OPERATION)
                .addKeyValue("outcome", failed == 0 ? "success" : "failure")
                .addKeyValue("refreshed", refreshed)
                .addKeyValue("failed", failed)
                .addKeyValue("skipped", skipped)
                .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                .log("scheduled refresh sweep completed");
        return new ScheduledRefreshResult(true, refreshed, failed, skipped);
    }

    private Outcome refreshOne(Candidate candidate) {
        var previousCorrelationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString());
        var sample = Timer.start(registry);
        var outcome = Outcome.REFRESHED;
        String errorType = null;
        String errorCode = null;
        try {
            syncService.sync(candidate.userId(), candidate.connectionId());
            analysisService.execute(candidate.userId(), candidate.connectionId());
        } catch (RuntimeException exception) {
            outcome = conflict(exception) ? Outcome.SKIPPED : Outcome.FAILED;
            errorType = exception.getClass().getSimpleName();
            errorCode = errorCode(exception);
        } finally {
            var elapsed = sample.stop(Timer.builder("trade.operation.duration")
                    .description("Request latency for bounded trade operations")
                    .tag("operation", OPERATION)
                    .tag("outcome", outcome.tag())
                    .register(registry));
            var event = LOG.atInfo()
                    .addKeyValue("operation", OPERATION)
                    .addKeyValue("outcome", outcome.tag())
                    .addKeyValue("connection_id", candidate.connectionId())
                    .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(elapsed));
            if (errorType != null) {
                event = event.addKeyValue("error_type", errorType).addKeyValue("error_code", errorCode);
            }
            event.log("scheduled refresh connection completed");
            restoreMdc(previousCorrelationId);
        }
        return outcome;
    }

    /**
     * Active connections whose credentials were validated recently, that are not already running a
     * live manual sync or analysis, that have not exhausted their retry budget, and whose last
     * successful sync is older than the refresh interval. A {@code RUNNING} sync older than
     * {@code portfolio.sync.stale-after} is abandoned work that {@link AccountSyncService} recovers,
     * so it must not exclude the connection forever.
     */
    private List<Candidate> dueConnections() {
        return jdbc.query("""
                SELECT connection.user_id, connection.id
                  FROM broker_connections connection
                  LEFT JOIN LATERAL (
                      SELECT run.started_at, run.completed_at
                        FROM account_sync_runs run
                       WHERE run.user_id = connection.user_id
                         AND run.broker_connection_id = connection.id
                         AND run.credential_revision = connection.credential_revision
                         AND run.status = 'SUCCEEDED'
                       ORDER BY run.completed_at DESC, run.id DESC
                       LIMIT 1
                  ) success ON true
                 WHERE connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                   AND connection.last_validated_at IS NOT NULL
                   AND connection.last_validated_at > CURRENT_TIMESTAMP
                       - CAST(? AS bigint) * INTERVAL '1 millisecond'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM account_sync_runs run
                        WHERE run.user_id = connection.user_id
                          AND run.broker_connection_id = connection.id
                          AND run.status = 'RUNNING'
                          AND run.started_at >= CURRENT_TIMESTAMP
                              - CAST(? AS bigint) * INTERVAL '1 millisecond'
                   )
                   AND NOT EXISTS (
                       SELECT 1
                         FROM analysis_runs run
                        WHERE run.user_id = connection.user_id
                          AND run.broker_connection_id = connection.id
                          AND run.status = 'RUNNING'
                   )
                   AND (
                       SELECT count(*)
                         FROM account_sync_runs run
                        WHERE run.user_id = connection.user_id
                          AND run.broker_connection_id = connection.id
                          AND run.credential_revision = connection.credential_revision
                          AND run.status = 'FAILED'
                          AND run.started_at
                              > COALESCE(success.started_at, CAST('-infinity' AS timestamptz))
                   ) < CAST(? AS integer)
                   AND (
                       success.completed_at IS NULL
                       OR success.completed_at <= CURRENT_TIMESTAMP
                          - CAST(? AS bigint) * INTERVAL '1 millisecond'
                   )
                 ORDER BY COALESCE(success.completed_at, CAST('-infinity' AS timestamptz)),
                          connection.id
                 LIMIT ?
                """, (resultSet, rowNum) -> new Candidate(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getObject("id", UUID.class)
        ),
                properties.connectionStaleAfter().toMillis(),
                syncStaleAfter.toMillis(),
                properties.maxConsecutiveFailures(),
                properties.interval().toMillis(),
                properties.batchSize());
    }

    private static boolean conflict(RuntimeException exception) {
        return (exception instanceof AccountSyncException syncException
                && syncException.code() == AccountSyncException.Code.SYNC_ALREADY_RUNNING)
                || (exception instanceof PortfolioAnalysisException analysisException
                && analysisException.code() == PortfolioAnalysisException.Code.ALREADY_RUNNING);
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof AccountSyncException syncException) {
            return syncException.code().name();
        }
        if (exception instanceof PortfolioAnalysisException analysisException) {
            return "ANALYSIS_" + analysisException.code().name();
        }
        if (exception instanceof BrokerException brokerException) {
            return "BROKER_" + brokerException.category().name();
        }
        return "INTERNAL_ERROR";
    }

    private static void restoreMdc(String previous) {
        if (previous == null) {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        } else {
            MDC.put(CorrelationIdFilter.MDC_KEY, previous);
        }
    }

    private record Candidate(UUID userId, UUID connectionId) {
    }

    private enum Outcome {
        REFRESHED("success"),
        FAILED("failure"),
        SKIPPED("skipped");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }
}
