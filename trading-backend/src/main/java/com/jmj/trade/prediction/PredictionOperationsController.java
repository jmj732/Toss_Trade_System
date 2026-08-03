package com.jmj.trade.prediction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/prediction-operations")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
final class PredictionOperationsController {

    private static final String SQL = """
            WITH due_predictions AS (
                SELECT prediction.predicted_at + INTERVAL '1 day' AS target_due_at
                  FROM analysis_predictions prediction
                  JOIN broker_connections connection
                    ON connection.user_id = prediction.user_id
                   AND connection.id = prediction.broker_connection_id
                  LEFT JOIN analysis_prediction_outcomes current_outcome
                    ON current_outcome.prediction_id = prediction.id
                   AND current_outcome.horizon = 'D1'
                 WHERE prediction.user_id = ?
                   AND connection.status = 'ACTIVE'
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
                 WHERE prediction.user_id = ?
                   AND connection.status = 'ACTIVE'
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
                 WHERE prediction.user_id = ?
                   AND connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                   AND current_outcome.id IS NULL
                   AND prediction.predicted_at <= CURRENT_TIMESTAMP - INTERVAL '20 days'
            )
            SELECT count(*) AS backlog,
                   COALESCE(GREATEST(0, floor(EXTRACT(
                       EPOCH FROM (CURRENT_TIMESTAMP - min(target_due_at))) * 1000)), 0)::bigint
                       AS max_lag_ms,
                   count(*) FILTER (WHERE target_due_at <= CURRENT_TIMESTAMP
                       - CAST(? AS bigint) * INTERVAL '1 millisecond') AS long_ungraded_count,
                   min(target_due_at) FILTER (WHERE target_due_at <= CURRENT_TIMESTAMP
                       - CAST(? AS bigint) * INTERVAL '1 millisecond') AS oldest_long_ungraded_due_at,
                   CURRENT_TIMESTAMP AS measured_at
              FROM due_predictions
            """;

    private final JdbcTemplate jdbc;
    private final boolean evaluationEnabled;
    private final Duration longUngradedAfter;

    PredictionOperationsController(
            JdbcTemplate jdbc,
            @Value("${prediction.evaluation.enabled:false}") boolean evaluationEnabled,
            @Value("${prediction.evaluation.long-ungraded-after:PT24H}") Duration longUngradedAfter
    ) {
        this.jdbc = jdbc;
        this.evaluationEnabled = evaluationEnabled;
        if (!longUngradedAfter.isPositive()) {
            throw new IllegalArgumentException("longUngradedAfter must be positive");
        }
        this.longUngradedAfter = longUngradedAfter;
    }

    @GetMapping
    OperationsView read(Principal principal) {
        var userId = UUID.fromString(principal.getName());
        return jdbc.queryForObject(SQL, (result, row) -> new OperationsView(
                evaluationEnabled,
                result.getLong("backlog"),
                result.getLong("max_lag_ms"),
                result.getLong("long_ungraded_count"),
                result.getObject("oldest_long_ungraded_due_at", OffsetDateTime.class) == null
                        ? null
                        : result.getObject("oldest_long_ungraded_due_at", OffsetDateTime.class).toInstant(),
                result.getObject("measured_at", OffsetDateTime.class).toInstant()),
                userId, userId, userId, longUngradedAfter.toMillis(), longUngradedAfter.toMillis());
    }

    record OperationsView(
            boolean evaluationEnabled,
            long backlog,
            long maxLagMs,
            long longUngradedCount,
            Instant oldestLongUngradedDueAt,
            Instant measuredAt
    ) {
    }
}
