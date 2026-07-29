package com.jmj.trade.prediction;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores caller-submitted directional predictions (a person or, later, a real model — this
 * service never generates a prediction itself) with a live-quote baseline price, and grades
 * them against a later live quote once each horizon's wall-clock time has actually passed.
 * Never imports from {@code com.jmj.trade.order} — this is a read-only reporting feature,
 * not an order or auto-trading integration.
 *
 * <p>Not a component-scanned {@code @Service} — like every other {@link BrokerAdapter}
 * consumer in this codebase, it's wired as a {@code @Bean} inside
 * {@code CredentialVaultConfiguration}, which only exists when
 * {@code broker.credentials.enabled=true}. A plain always-on {@code @Service} here would
 * require a {@link BrokerAdapter} bean even in contexts where credentials aren't
 * configured at all.
 */
public final class AnalysisPredictionService {

    private final JdbcTemplate jdbc;
    private final BrokerAdapter brokerAdapter;

    public AnalysisPredictionService(JdbcTemplate jdbc, BrokerAdapter brokerAdapter) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.brokerAdapter = Objects.requireNonNull(brokerAdapter, "brokerAdapter");
    }

    PredictionView create(UUID userId, UUID connectionId, CreateCommand command, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        validate(command);
        requireOwnedConnection(userId, connectionId);

        var quote = brokerAdapter.getQuote(new BrokerConnectionRef(connectionId), command.symbol()).value();
        if (quote.currency() != command.currency()) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.QUOTE_CURRENCY_MISMATCH);
        }
        var price = quote.lastPrice();
        if (price == null || price.signum() <= 0) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.QUOTE_UNAVAILABLE);
        }

        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_predictions (
                    id, user_id, broker_connection_id, symbol, currency, predicted_direction,
                    model_version, contract_version, baseline_price, predicted_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, connectionId, command.symbol(), command.currency().name(),
                command.predictedDirection().name(), command.modelVersion(), command.contractVersion(),
                price, offset(now), offset(now));

        return new PredictionView(
                id, connectionId, command.symbol(), command.currency(), command.predictedDirection(),
                command.modelVersion(), command.contractVersion(), price, now, Map.of());
    }

    PredictionPerformanceView read(
            UUID userId,
            UUID connectionId,
            Instant from,
            Instant to,
            String modelVersion,
            String contractVersion,
            Instant now
    ) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        var effectiveFrom = from == null ? Instant.EPOCH : from;
        var effectiveTo = to == null ? now : to;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.INVALID_INPUT);
        }
        requireOwnedConnection(userId, connectionId);

        var predictions = fetchPredictions(userId, connectionId, effectiveFrom, effectiveTo, modelVersion, contractVersion);
        var byVersion = aggregate(predictions);
        return new PredictionPerformanceView(predictions, byVersion);
    }

    private void validate(CreateCommand command) {
        if (command == null
                || isBlank(command.symbol())
                || command.currency() == null
                || command.predictedDirection() == null
                || isBlank(command.modelVersion())
                || isBlank(command.contractVersion())) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.INVALID_INPUT);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void requireOwnedConnection(UUID userId, UUID connectionId) {
        if (jdbc.queryForList("""
                SELECT 1
                  FROM broker_connections
                 WHERE id = ?
                   AND user_id = ?
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                """, Integer.class, connectionId, userId).isEmpty()) {
            throw BrokerConnectionException.notFound();
        }
    }

    /**
     * Grades at most one due-and-ungraded horizon per prediction per tick — never more, even
     * if several horizons (e.g. D1 and D5) happen to be simultaneously overdue because a
     * connection went unread for a while. Horizons are graded in ascending order (D1, D5,
     * D20), and grading stops at the first one that isn't due yet, since a shorter horizon
     * not being due implies a longer one isn't either. Without this cap, several horizons
     * overdue at once would all be graded against the *same* live quote in one pass,
     * silently collapsing D1/D5/D20 into numerically identical returns — that would be
     * worse than leaving them ungraded until a later read happens to catch each one nearer
     * its own due time.
     *
     * <p>A quote failure for the one pair being graded (e.g. the broker is briefly
     * unavailable) is skipped, not fatal — it stays ungraded and is retried on the next
     * tick, since nothing here is allowed to fabricate a price.
     */
    int evaluateDue(Instant now) {
        Objects.requireNonNull(now, "now");
        var predictions = jdbc.query("""
                SELECT prediction.id, prediction.broker_connection_id, prediction.symbol,
                       prediction.predicted_direction, prediction.baseline_price, prediction.predicted_at
                  FROM analysis_predictions prediction
                  JOIN broker_connections connection
                    ON connection.user_id = prediction.user_id
                   AND connection.id = prediction.broker_connection_id
                 WHERE connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                """, (resultSet, rowNum) -> new DuePrediction(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("broker_connection_id", UUID.class),
                resultSet.getString("symbol"),
                PredictedDirection.valueOf(resultSet.getString("predicted_direction")),
                resultSet.getBigDecimal("baseline_price"),
                resultSet.getObject("predicted_at", OffsetDateTime.class).toInstant()
        ));
        if (predictions.isEmpty()) {
            return 0;
        }

        var existing = new HashSet<PredictionHorizon>(jdbc.query("""
                SELECT prediction_id, horizon
                  FROM analysis_prediction_outcomes
                """, (resultSet, rowNum) -> new PredictionHorizon(
                resultSet.getObject("prediction_id", UUID.class),
                Horizon.valueOf(resultSet.getString("horizon"))
        )));

        var quotes = new HashMap<QuoteKey, Optional<ObservedQuote>>();
        var graded = 0;
        for (var prediction : predictions) {
            for (var horizon : Horizon.values()) {
                if (existing.contains(new PredictionHorizon(prediction.id(), horizon))) {
                    continue;
                }
                var dueAt = prediction.predictedAt().plus(horizon.days(), ChronoUnit.DAYS);
                if (now.isBefore(dueAt)) {
                    break;
                }
                if (evaluateOne(prediction, horizon, dueAt, quotes)) {
                    graded++;
                }
                break;
            }
        }
        return graded;
    }

    private boolean evaluateOne(
            DuePrediction prediction,
            Horizon horizon,
            Instant dueAt,
            Map<QuoteKey, Optional<ObservedQuote>> quotes
    ) {
        var key = new QuoteKey(prediction.connectionId(), prediction.symbol());
        var quote = quotes.computeIfAbsent(key, ignored -> fetchQuote(key.connectionId(), key.symbol()))
                .orElse(null);
        if (quote == null || quote.observationTime().isBefore(dueAt)) {
            return false;
        }
        var price = quote.price();
        var actualReturn = price.subtract(prediction.baselinePrice())
                .divide(prediction.baselinePrice(), 10, RoundingMode.HALF_UP);
        var directionCorrect = prediction.predictedDirection() == PredictedDirection.UP
                ? actualReturn.signum() > 0
                : actualReturn.signum() < 0;
        try {
            return jdbc.update("""
                    INSERT INTO analysis_prediction_outcomes (
                        id, prediction_id, horizon, price, actual_return, direction_correct,
                        target_due_at, observation_time, lag_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (prediction_id, horizon) DO NOTHING
                    """, UUID.randomUUID(), prediction.id(), horizon.name(), price, actualReturn,
                    directionCorrect, offset(dueAt), offset(quote.observationTime()),
                    Duration.between(dueAt, quote.observationTime()).toMillis()) == 1;
        } catch (DuplicateKeyException ignored) {
            // Another evaluator already graded this pair — the unique constraint wins either way.
            return false;
        }
    }

    private Optional<ObservedQuote> fetchQuote(UUID connectionId, String symbol) {
        Quote quote;
        try {
            quote = brokerAdapter.getQuote(new BrokerConnectionRef(connectionId), symbol).value();
        } catch (BrokerException exception) {
            return Optional.empty();
        }
        var price = quote.lastPrice();
        return price != null && price.signum() > 0
                ? Optional.of(new ObservedQuote(price, quote.observedAt()))
                : Optional.empty();
    }

    private List<PredictionView> fetchPredictions(
            UUID userId,
            UUID connectionId,
            Instant from,
            Instant to,
            String modelVersion,
            String contractVersion
    ) {
        var rows = jdbc.query("""
                SELECT id, symbol, currency, predicted_direction, model_version, contract_version,
                       baseline_price, predicted_at
                  FROM analysis_predictions
                 WHERE user_id = ?
                   AND broker_connection_id = ?
                   AND predicted_at >= ?
                   AND predicted_at <= ?
                   AND (CAST(? AS VARCHAR) IS NULL OR model_version = ?)
                   AND (CAST(? AS VARCHAR) IS NULL OR contract_version = ?)
                 ORDER BY predicted_at DESC
                """, (resultSet, rowNum) -> new PredictionRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("symbol"),
                Currency.valueOf(resultSet.getString("currency")),
                PredictedDirection.valueOf(resultSet.getString("predicted_direction")),
                resultSet.getString("model_version"),
                resultSet.getString("contract_version"),
                resultSet.getBigDecimal("baseline_price"),
                resultSet.getObject("predicted_at", OffsetDateTime.class).toInstant()
        ), userId, connectionId, offset(from), offset(to), modelVersion, modelVersion, contractVersion, contractVersion);

        if (rows.isEmpty()) {
            return List.of();
        }
        var ids = rows.stream().map(PredictionRow::id).toList();
        var outcomesByPrediction = fetchOutcomes(ids);
        return rows.stream()
                .map(row -> new PredictionView(
                        row.id(), connectionId, row.symbol(), row.currency(), row.predictedDirection(),
                        row.modelVersion(), row.contractVersion(), row.baselinePrice(), row.predictedAt(),
                        outcomesByPrediction.getOrDefault(row.id(), Map.of())))
                .toList();
    }

    private Map<UUID, Map<Horizon, OutcomeView>> fetchOutcomes(List<UUID> predictionIds) {
        if (predictionIds.isEmpty()) {
            return Map.of();
        }
        var placeholders = String.join(",", predictionIds.stream().map(id -> "?").toList());
        var rows = jdbc.query("""
                SELECT prediction_id, horizon, price, actual_return, direction_correct,
                       target_due_at, observation_time, lag_ms
                  FROM analysis_prediction_outcomes
                 WHERE prediction_id IN (%s)
                """.formatted(placeholders), (resultSet, rowNum) -> Map.entry(
                resultSet.getObject("prediction_id", UUID.class),
                new OutcomeRow(
                        Horizon.valueOf(resultSet.getString("horizon")),
                        resultSet.getBigDecimal("price"),
                        resultSet.getBigDecimal("actual_return"),
                        resultSet.getBoolean("direction_correct"),
                        resultSet.getObject("target_due_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("observation_time", OffsetDateTime.class).toInstant(),
                        Duration.ofMillis(resultSet.getLong("lag_ms")))
        ), predictionIds.toArray());

        var byPrediction = new HashMap<UUID, Map<Horizon, OutcomeView>>();
        for (var entry : rows) {
            var outcome = entry.getValue();
            byPrediction.computeIfAbsent(entry.getKey(), key -> new EnumMap<>(Horizon.class))
                    .put(outcome.horizon(), new OutcomeView(
                            outcome.price(), outcome.actualReturn(), outcome.directionCorrect(),
                            outcome.targetDueAt(), outcome.observationTime(), outcome.lag()));
        }
        return byPrediction;
    }

    /**
     * Groups by (modelVersion, contractVersion, horizon). Hit rate and average directional
     * return use only that horizon's own grade. Max adverse excursion for horizon H uses only
     * observations up to and including H (baseline plus any evaluated horizon whose day count
     * is &lt;= H's) — a D20 row must never be informed by a coincidentally-already-evaluated
     * D20 outcome when computing the D5 row's excursion, since that would use data from beyond
     * the D5 horizon itself.
     */
    private static List<PerformanceRow> aggregate(List<PredictionView> predictions) {
        record Key(String modelVersion, String contractVersion, Horizon horizon) {
        }
        var groups = new LinkedHashMap<Key, List<PredictionView>>();
        for (var prediction : predictions) {
            for (var horizon : Horizon.values()) {
                if (!prediction.outcomes().containsKey(horizon)) {
                    continue;
                }
                groups.computeIfAbsent(
                        new Key(prediction.modelVersion(), prediction.contractVersion(), horizon),
                        key -> new ArrayList<>()).add(prediction);
            }
        }

        var result = new ArrayList<PerformanceRow>();
        for (var entry : groups.entrySet()) {
            var key = entry.getKey();
            var group = entry.getValue();
            var wins = 0;
            var directionalReturnSum = BigDecimal.ZERO;
            var maxAdverseExcursionSum = BigDecimal.ZERO;
            for (var prediction : group) {
                var ownOutcome = prediction.outcomes().get(key.horizon());
                if (ownOutcome.directionCorrect()) {
                    wins++;
                }
                var sign = prediction.predictedDirection() == PredictedDirection.UP ? 1 : -1;
                directionalReturnSum = directionalReturnSum.add(ownOutcome.actualReturn().multiply(BigDecimal.valueOf(sign)));
                maxAdverseExcursionSum = maxAdverseExcursionSum.add(
                        maxAdverseExcursion(prediction, key.horizon()));
            }
            var count = group.size();
            result.add(new PerformanceRow(
                    key.modelVersion(), key.contractVersion(), key.horizon(), count,
                    BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP),
                    directionalReturnSum.divide(BigDecimal.valueOf(count), 10, RoundingMode.HALF_UP),
                    maxAdverseExcursionSum.divide(BigDecimal.valueOf(count), 10, RoundingMode.HALF_UP)));
        }
        return result;
    }

    private static BigDecimal maxAdverseExcursion(PredictionView prediction, Horizon upTo) {
        var worst = BigDecimal.ZERO;
        for (var horizon : Horizon.values()) {
            if (horizon.days() > upTo.days()) {
                continue;
            }
            var outcome = prediction.outcomes().get(horizon);
            if (outcome == null) {
                continue;
            }
            var adverse = prediction.predictedDirection() == PredictedDirection.UP
                    ? outcome.actualReturn().negate()
                    : outcome.actualReturn();
            if (adverse.compareTo(worst) > 0) {
                worst = adverse;
            }
        }
        return worst;
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record DuePrediction(
            UUID id,
            UUID connectionId,
            String symbol,
            PredictedDirection predictedDirection,
            BigDecimal baselinePrice,
            Instant predictedAt
    ) {
    }

    private record QuoteKey(UUID connectionId, String symbol) {
    }

    private record ObservedQuote(BigDecimal price, Instant observationTime) {
    }

    private record PredictionHorizon(UUID predictionId, Horizon horizon) {
    }

    private record PredictionRow(
            UUID id,
            String symbol,
            Currency currency,
            PredictedDirection predictedDirection,
            String modelVersion,
            String contractVersion,
            BigDecimal baselinePrice,
            Instant predictedAt
    ) {
    }

    private record OutcomeRow(
            Horizon horizon,
            BigDecimal price,
            BigDecimal actualReturn,
            boolean directionCorrect,
            Instant targetDueAt,
            Instant observationTime,
            Duration lag
    ) {
    }

    public record CreateCommand(
            String symbol,
            Currency currency,
            PredictedDirection predictedDirection,
            String modelVersion,
            String contractVersion
    ) {
    }

    public record PredictionView(
            UUID id,
            UUID connectionId,
            String symbol,
            Currency currency,
            PredictedDirection predictedDirection,
            String modelVersion,
            String contractVersion,
            BigDecimal baselinePrice,
            Instant predictedAt,
            Map<Horizon, OutcomeView> outcomes
    ) {
    }

    public record OutcomeView(
            BigDecimal price,
            BigDecimal actualReturn,
            boolean directionCorrect,
            Instant targetDueAt,
            Instant observationTime,
            Duration lag
    ) {
    }

    public record PerformanceRow(
            String modelVersion,
            String contractVersion,
            Horizon horizon,
            int sampleCount,
            BigDecimal hitRate,
            BigDecimal avgDirectionalReturn,
            BigDecimal avgMaxAdverseExcursion
    ) {
    }

    public record PredictionPerformanceView(
            List<PredictionView> predictions,
            List<PerformanceRow> byVersion
    ) {
    }
}
