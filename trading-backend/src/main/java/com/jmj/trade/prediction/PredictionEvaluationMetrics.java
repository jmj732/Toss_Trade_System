package com.jmj.trade.prediction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

@Component
public final class PredictionEvaluationMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(PredictionEvaluationMetrics.class);
    private static final Snapshot EMPTY = new Snapshot(0, null);
    private static final String BACKLOG_SQL = """
            WITH due_predictions AS (
                SELECT prediction.predicted_at + INTERVAL '1 day' AS target_due_at
                  FROM analysis_predictions prediction
                  JOIN broker_connections connection
                    ON connection.user_id = prediction.user_id
                   AND connection.id = prediction.broker_connection_id
                  LEFT JOIN analysis_prediction_outcomes current_outcome
                    ON current_outcome.prediction_id = prediction.id
                   AND current_outcome.horizon = 'D1'
                 WHERE connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                   AND current_outcome.id IS NULL
                   AND prediction.predicted_at <= CURRENT_TIMESTAMP - INTERVAL '1 day'
                UNION ALL
                SELECT prediction.predicted_at + INTERVAL '5 days'
                  FROM analysis_predictions prediction
                  JOIN broker_connections connection
                    ON connection.user_id = prediction.user_id
                   AND connection.id = prediction.broker_connection_id
                  JOIN analysis_prediction_outcomes d1
                    ON d1.prediction_id = prediction.id
                   AND d1.horizon = 'D1'
                  LEFT JOIN analysis_prediction_outcomes current_outcome
                    ON current_outcome.prediction_id = prediction.id
                   AND current_outcome.horizon = 'D5'
                 WHERE connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                   AND current_outcome.id IS NULL
                   AND prediction.predicted_at <= CURRENT_TIMESTAMP - INTERVAL '5 days'
                UNION ALL
                SELECT prediction.predicted_at + INTERVAL '20 days'
                  FROM analysis_predictions prediction
                  JOIN broker_connections connection
                    ON connection.user_id = prediction.user_id
                   AND connection.id = prediction.broker_connection_id
                  JOIN analysis_prediction_outcomes d1
                    ON d1.prediction_id = prediction.id
                   AND d1.horizon = 'D1'
                  JOIN analysis_prediction_outcomes d5
                    ON d5.prediction_id = prediction.id
                   AND d5.horizon = 'D5'
                  LEFT JOIN analysis_prediction_outcomes current_outcome
                    ON current_outcome.prediction_id = prediction.id
                   AND current_outcome.horizon = 'D20'
                 WHERE connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                   AND current_outcome.id IS NULL
                   AND prediction.predicted_at <= CURRENT_TIMESTAMP - INTERVAL '20 days'
            )
            SELECT count(*) AS backlog_count,
                   min(target_due_at) AS oldest_target_due_at
              FROM due_predictions
            """;

    private final JdbcTemplate jdbc;
    private final Duration cacheTtl;
    private final Clock clock;
    private final Counter attempted;
    private final Counter succeeded;
    private final Counter quoteFailed;
    private final Counter itemFailed;
    private final Counter leaseAcquireFailed;
    private final Counter leaseRenewFailed;
    private final Counter countStopped;
    private final Counter timeStopped;
    private final Object refreshLock = new Object();

    private volatile Snapshot snapshot = EMPTY;
    private volatile Instant refreshAfter = Instant.MIN;

    @Autowired
    public PredictionEvaluationMetrics(
            JdbcTemplate jdbc,
            MeterRegistry registry,
            @Value("${prediction.evaluation.metrics-cache-ttl:PT30S}") Duration cacheTtl
    ) {
        this(jdbc, registry, cacheTtl, Clock.systemUTC());
    }

    PredictionEvaluationMetrics(
            JdbcTemplate jdbc,
            MeterRegistry registry,
            Duration cacheTtl,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        if (!cacheTtl.isPositive()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }

        Gauge.builder("trade.prediction.evaluation.backlog", this, ignored -> backlog())
                .description("Earliest due and ungraded prediction horizons")
                .register(registry);
        Gauge.builder("trade.prediction.evaluation.max.lag.ms", this, ignored -> maxLagMillis())
                .description("Maximum lag of earliest due and ungraded prediction horizons")
                .baseUnit("milliseconds")
                .register(registry);
        attempted = counter(registry, "trade.prediction.evaluation.attempted");
        succeeded = counter(registry, "trade.prediction.evaluation.succeeded");
        quoteFailed = counter(registry, "trade.prediction.evaluation.quote.failed");
        itemFailed = counter(registry, "trade.prediction.evaluation.item.failed");
        leaseAcquireFailed = taggedCounter(
                registry, "trade.prediction.evaluation.lease.failure", "stage", "acquire");
        leaseRenewFailed = taggedCounter(
                registry, "trade.prediction.evaluation.lease.failure", "stage", "renew");
        countStopped = taggedCounter(
                registry, "trade.prediction.evaluation.early.stop", "reason", "count");
        timeStopped = taggedCounter(
                registry, "trade.prediction.evaluation.early.stop", "reason", "time");
    }

    void recordTick(int attempted, int succeeded, int quoteFailed) {
        recordTick(attempted, succeeded, quoteFailed, 0);
    }

    void recordTick(int attempted, int succeeded, int quoteFailed, int itemFailed) {
        if (attempted < 0 || succeeded < 0 || quoteFailed < 0 || itemFailed < 0) {
            throw new IllegalArgumentException("tick counts must not be negative");
        }
        this.attempted.increment(attempted);
        this.succeeded.increment(succeeded);
        this.quoteFailed.increment(quoteFailed);
        this.itemFailed.increment(itemFailed);
    }

    void recordLeaseFailure(LeaseStage stage) {
        Objects.requireNonNull(stage, "stage");
        switch (stage) {
            case ACQUIRE -> leaseAcquireFailed.increment();
            case RENEW -> leaseRenewFailed.increment();
        }
    }

    void recordEarlyStop(EarlyStopReason reason) {
        Objects.requireNonNull(reason, "reason");
        switch (reason) {
            case COUNT -> countStopped.increment();
            case TIME -> timeStopped.increment();
        }
    }

    private double backlog() {
        return currentSnapshot().backlog();
    }

    private double maxLagMillis() {
        var oldestDueAt = currentSnapshot().oldestDueAt();
        if (oldestDueAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldestDueAt, clock.instant()).toMillis());
    }

    private Snapshot currentSnapshot() {
        var now = clock.instant();
        if (now.isBefore(refreshAfter)) {
            return snapshot;
        }
        synchronized (refreshLock) {
            now = clock.instant();
            if (now.isBefore(refreshAfter)) {
                return snapshot;
            }
            refreshAfter = now.plus(cacheTtl);
            try {
                snapshot = map(jdbc.queryForMap(BACKLOG_SQL));
            } catch (DataAccessException exception) {
                LOG.atWarn()
                        .addKeyValue("operation", "prediction_evaluation_metrics_refresh")
                        .addKeyValue("error_type", exception.getClass().getSimpleName())
                        .log("prediction evaluation metrics refresh failed");
            }
            return snapshot;
        }
    }

    private static Snapshot map(Map<String, Object> row) {
        var count = ((Number) row.get("backlog_count")).longValue();
        return new Snapshot(count, instant(row.get("oldest_target_due_at")));
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            case Instant instant -> instant;
            default -> throw new IllegalArgumentException(
                    "unsupported oldest_target_due_at type: " + value.getClass().getName());
        };
    }

    private static Counter counter(MeterRegistry registry, String name) {
        return Counter.builder(name).register(registry);
    }

    private static Counter taggedCounter(
            MeterRegistry registry,
            String name,
            String tagName,
            String tagValue
    ) {
        return Counter.builder(name).tag(tagName, tagValue).register(registry);
    }

    enum LeaseStage {
        ACQUIRE,
        RENEW
    }

    enum EarlyStopReason {
        COUNT,
        TIME
    }

    private record Snapshot(long backlog, Instant oldestDueAt) {
    }
}
