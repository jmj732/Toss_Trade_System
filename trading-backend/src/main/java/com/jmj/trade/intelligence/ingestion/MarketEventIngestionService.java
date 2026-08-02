package com.jmj.trade.intelligence.ingestion;

import com.jmj.trade.intelligence.EventIntelligenceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MarketEventIngestionService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MarketEventProviderRegistry providers;
    private final MarketEventIngestionLease lease;
    private final EventIntelligenceService events;
    private final MarketEventIngestionProperties properties;

    MarketEventIngestionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            MarketEventProviderRegistry providers,
            MarketEventIngestionLease lease,
            EventIntelligenceService events,
            MarketEventIngestionProperties properties
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.providers = Objects.requireNonNull(providers, "providers");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.events = Objects.requireNonNull(events, "events");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public IngestionResult collect() {
        var owner = UUID.randomUUID();
        if (!lease.acquire(owner)) {
            return IngestionResult.notAcquired();
        }
        try {
            reapStaleRuns();
            var targets = targets();
            var symbols = targets.stream()
                    .flatMap(target -> target.symbols().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var succeeded = new ArrayList<MarketEventProviderId>();
            var failed = new ArrayList<MarketEventProviderId>();
            var inserted = 0;
            for (var provider : providers.providers()) {
                if (!due(provider.id())) {
                    continue;
                }
                if (!lease.renew(owner)) {
                    throw new IllegalStateException("market event ingestion lease expired");
                }
                UUID run;
                try {
                    run = startRun(provider.id());
                } catch (RuntimeException exception) {
                    failed.add(provider.id());
                    continue;
                }
                try {
                    var maxEvents = Math.min(properties.batchSize(),
                            properties.maxEventsPerProvider());
                    var collection = provider.collectWithFailures(new MarketEventProvider.Request(
                            symbols, OffsetDateTime.now(ZoneOffset.UTC)
                                    .minus(properties.lookback()).toInstant(), maxEvents,
                            () -> lease.renew(owner)));
                    if (collection == null || collection.events().size() > maxEvents) {
                        throw new IllegalStateException("provider returned too many events");
                    }
                    if (!lease.renew(owner)) {
                        throw new IllegalStateException("market event ingestion lease expired");
                    }
                    var providerInserted = 0;
                    RuntimeException firstEventFailure = null;
                    for (var event : collection.events()) {
                        for (var target : targets) {
                            if (!matches(target, event)) {
                                continue;
                            }
                            if (!lease.renew(owner)) {
                                throw new IllegalStateException("market event ingestion lease expired");
                            }
                            try {
                                if (events.ingest(target.userId(), target.connectionId(), event)) {
                                    providerInserted++;
                                    inserted++;
                                }
                            } catch (RuntimeException ignored) {
                                if (firstEventFailure == null) {
                                    firstEventFailure = ignored;
                                }
                            }
                        }
                    }
                    if (collection.hasFailures()) {
                        throw new IllegalStateException("provider partially failed", collection.failures().getFirst());
                    }
                    if (firstEventFailure != null) {
                        throw new IllegalStateException("event persistence failed", firstEventFailure);
                    }
                    complete(run, providerInserted);
                    succeeded.add(provider.id());
                } catch (RuntimeException exception) {
                    try {
                        fail(run, exception);
                    } catch (RuntimeException persistenceFailure) {
                        // Keep this provider isolated even when its run ledger is unavailable.
                    }
                    failed.add(provider.id());
                }
            }
            return new IngestionResult(true, List.copyOf(succeeded), List.copyOf(failed), inserted);
        } finally {
            lease.release(owner);
        }
    }

    public boolean reprocess(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        var owner = UUID.randomUUID();
        if (!lease.acquire(owner)) {
            return false;
        }
        try {
            return jdbc.update("""
                    UPDATE market_event_ingestion_runs
                       SET attempt = 1, next_retry_at = CURRENT_TIMESTAMP,
                           last_error = NULL
                     WHERE id = ? AND status = 'FAILED'
                    """, runId) == 1;
        } finally {
            lease.release(owner);
        }
    }

    private boolean matches(Target target, MarketEvent event) {
        if (!event.affectedSymbols().isEmpty()
                && !java.util.Collections.disjoint(target.symbols(), event.affectedSymbols())) {
            return true;
        }
        return !event.macroScope().isEmpty();
    }

    private boolean due(MarketEventProviderId provider) {
        var latest = jdbc.query("""
                SELECT status, next_retry_at, attempt, last_error
                  FROM market_event_ingestion_runs
                 WHERE provider = ?
                 ORDER BY started_at DESC, id DESC
                 LIMIT 1
                """, (resultSet, rowNumber) -> new LatestRun(
                resultSet.getString("status"),
                resultSet.getObject("next_retry_at", OffsetDateTime.class),
                resultSet.getInt("attempt"), resultSet.getString("last_error")), provider.name())
                .stream().findFirst().orElse(null);
        if (latest == null || "SUCCEEDED".equals(latest.status())) {
            return true;
        }
        if (!"FAILED".equals(latest.status())) {
            return false;
        }
        return latest.attempt() < properties.maxAttempts()
                && (latest.nextRetryAt() == null
                || !latest.nextRetryAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private UUID startRun(MarketEventProviderId provider) {
        return transaction.execute(status -> {
            var latestAttempt = jdbc.query("""
                    SELECT attempt, status, last_error
                      FROM market_event_ingestion_runs
                     WHERE provider = ?
                     ORDER BY started_at DESC, id DESC
                     LIMIT 1
                    """, (resultSet, rowNumber) -> new LatestRun(
                    resultSet.getString("status"), null, resultSet.getInt("attempt"),
                    resultSet.getString("last_error")), provider.name())
                    .stream().findFirst().orElse(null);
            var id = UUID.randomUUID();
            var now = now();
            jdbc.update("""
                    INSERT INTO market_event_ingestion_runs (
                        id, provider, status, attempt, requested_since, started_at
                    ) VALUES (?, ?, 'RUNNING', ?, ?, ?)
                    """, id, provider.name(), latestAttempt == null
                            || "SUCCEEDED".equals(latestAttempt.status())
                            ? 1 : latestAttempt.lastError() == null
                            ? 1 : latestAttempt.attempt() + 1,
                    now.minus(properties.lookback()), now);
            return id;
        });
    }

    private void complete(UUID runId, int collectedEvents) {
        var updated = transaction.execute(status -> jdbc.update("""
                UPDATE market_event_ingestion_runs
                   SET status = 'SUCCEEDED', completed_at = ?, collected_events = ?,
                       last_error = NULL
                 WHERE id = ? AND status = 'RUNNING'
                """, now(), collectedEvents, runId));
        if (updated == null || updated != 1) {
            throw new IllegalStateException("market event ingestion run completion was not recorded");
        }
    }

    private void fail(UUID runId, RuntimeException exception) {
        transaction.executeWithoutResult(status -> {
            var attempt = jdbc.queryForObject(
                    "SELECT attempt FROM market_event_ingestion_runs WHERE id = ?",
                    Integer.class, runId);
            var delay = properties.retryBackoff()
                    .multipliedBy(1L << Math.min(10, Math.max(0, attempt - 1)));
            if (delay.compareTo(properties.maxRetryBackoff()) > 0) {
                delay = properties.maxRetryBackoff();
            }
            var updated = jdbc.update("""
                    UPDATE market_event_ingestion_runs
                       SET status = 'FAILED', completed_at = ?, next_retry_at = ?,
                           last_error = ?
                     WHERE id = ? AND status = 'RUNNING'
                    """, now(), now().plus(delay), error(exception), runId);
            if (updated != 1) {
                throw new IllegalStateException("market event ingestion run failure was not recorded");
            }
        });
    }

    private void reapStaleRuns() {
        var threshold = properties.staleAfter().compareTo(properties.leaseTtl()) > 0
                ? properties.staleAfter() : properties.leaseTtl();
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE market_event_ingestion_runs
                   SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                       next_retry_at = CURRENT_TIMESTAMP, last_error = 'STALE_RUN'
                 WHERE status = 'RUNNING'
                   AND started_at < CURRENT_TIMESTAMP
                       - CAST(? AS bigint) * INTERVAL '1 millisecond'
                """, threshold.toMillis()));
    }

    private List<Target> targets() {
        return jdbc.query("""
                SELECT connection.user_id, connection.id,
                       COALESCE(string_agg(DISTINCT upper(position.symbol), ','), '') AS symbols
                  FROM broker_connections connection
                  LEFT JOIN LATERAL (
                      SELECT run.id
                        FROM account_sync_runs run
                       WHERE run.user_id = connection.user_id
                         AND run.broker_connection_id = connection.id
                         AND run.status = 'SUCCEEDED'
                       ORDER BY run.completed_at DESC, run.id DESC
                       LIMIT 1
                  ) success ON true
                  LEFT JOIN position_snapshots position
                    ON position.sync_run_id = success.id
                 WHERE connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                 GROUP BY connection.user_id, connection.id
                """, (resultSet, rowNumber) -> new Target(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getObject("id", UUID.class),
                parseSymbols(resultSet.getString("symbols"))));
    }

    private static Set<String> parseSymbols(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        var values = new HashSet<String>();
        for (var symbol : value.split(",")) {
            values.add(symbol.trim());
        }
        return Set.copyOf(values);
    }

    private static String error(RuntimeException exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public record IngestionResult(
            boolean leaseAcquired,
            List<MarketEventProviderId> providersSucceeded,
            List<MarketEventProviderId> providersFailed,
            int insertedEvents
    ) {

        static IngestionResult notAcquired() {
            return new IngestionResult(false, List.of(), List.of(), 0);
        }
    }

    private record Target(UUID userId, UUID connectionId, Set<String> symbols) {
    }

    private record LatestRun(
            String status,
            OffsetDateTime nextRetryAt,
            int attempt,
            String lastError
    ) {
    }
}
