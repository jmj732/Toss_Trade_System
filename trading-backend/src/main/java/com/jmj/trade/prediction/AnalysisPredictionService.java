package com.jmj.trade.prediction;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import java.util.function.BooleanSupplier;

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
    private final PredictionModelRegistryService registry;
    private final TransactionTemplate transactions;

    public AnalysisPredictionService(
            JdbcTemplate jdbc,
            BrokerAdapter brokerAdapter,
            PredictionModelRegistryService registry,
            TransactionTemplate transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.brokerAdapter = Objects.requireNonNull(brokerAdapter, "brokerAdapter");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    PredictionView create(UUID userId, UUID connectionId, CreateCommand command, Instant now) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        validate(command);
        requireOwnedConnection(userId, connectionId);
        return createNew(userId, connectionId, null, command, now);
    }

    BatchView createBatch(
            UUID userId,
            UUID connectionId,
            List<BatchCommand> commands,
            Instant now
    ) {
        return createBatch(userId, connectionId, commands, now, null);
    }

    BatchView createBatch(
            UUID userId,
            UUID connectionId,
            List<BatchCommand> commands,
            Instant now,
            ModelContractScope scope
    ) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (commands == null || commands.isEmpty() || commands.size() > 100) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.INVALID_INPUT);
        }
        requireOwnedConnection(userId, connectionId);

        var results = new ArrayList<BatchItemResult>(commands.size());
        for (var command : commands) {
            results.add(createBatchItem(userId, connectionId, command, now, scope));
        }
        return new BatchView(results);
    }

    private BatchItemResult createBatchItem(
            UUID userId,
            UUID connectionId,
            BatchCommand batchCommand,
            Instant now,
            ModelContractScope scope
    ) {
        if (batchCommand == null
                || isBlank(batchCommand.clientRequestId())
                || batchCommand.clientRequestId().length() > 100) {
            return BatchItemResult.failed(
                    batchCommand == null ? null : batchCommand.clientRequestId(),
                    BatchErrorCode.INVALID_INPUT);
        }
        if (scope != null && !scope.matches(batchCommand.command())) {
            return BatchItemResult.failed(
                    batchCommand.clientRequestId(), BatchErrorCode.API_KEY_SCOPE_MISMATCH);
        }

        var existing = findByClientRequestId(userId, batchCommand.clientRequestId());
        if (existing.isPresent()) {
            return replay(connectionId, batchCommand, existing.get());
        }

        try {
            var created = createNew(
                    userId, connectionId, batchCommand.clientRequestId(), batchCommand.command(), now);
            return BatchItemResult.created(batchCommand.clientRequestId(), created);
        } catch (DuplicateKeyException exception) {
            return findByClientRequestId(userId, batchCommand.clientRequestId())
                    .map(prediction -> replay(connectionId, batchCommand, prediction))
                    .orElseThrow(() -> exception);
        } catch (AnalysisPredictionException exception) {
            return BatchItemResult.failed(
                    batchCommand.clientRequestId(),
                    switch (exception.code()) {
                        case INVALID_INPUT -> BatchErrorCode.INVALID_INPUT;
                        case MODEL_VERSION_NOT_ACTIVE -> BatchErrorCode.MODEL_VERSION_NOT_ACTIVE;
                        case QUOTE_CURRENCY_MISMATCH -> BatchErrorCode.QUOTE_CURRENCY_MISMATCH;
                        case QUOTE_UNAVAILABLE -> BatchErrorCode.QUOTE_UNAVAILABLE;
                    });
        } catch (BrokerException exception) {
            return BatchItemResult.failed(batchCommand.clientRequestId(), BatchErrorCode.QUOTE_FAILED);
        }
    }

    private PredictionView createNew(
            UUID userId,
            UUID connectionId,
            String clientRequestId,
            CreateCommand command,
            Instant now
    ) {
        validate(command);
        requireActiveVersion(userId, command);

        var quote = brokerAdapter.getQuote(new BrokerConnectionRef(connectionId), command.symbol()).value();
        if (quote.currency() != command.currency()) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.QUOTE_CURRENCY_MISMATCH);
        }
        var price = quote.lastPrice();
        if (price == null || price.signum() <= 0) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.QUOTE_UNAVAILABLE);
        }

        var id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            if (!registry.lockActive(userId, command.modelVersion(), command.contractVersion())) {
                throw new AnalysisPredictionException(
                        AnalysisPredictionException.Code.MODEL_VERSION_NOT_ACTIVE);
            }
            jdbc.update("""
                    INSERT INTO analysis_predictions (
                        id, user_id, broker_connection_id, symbol, currency, predicted_direction,
                        model_version, contract_version, baseline_price, predicted_at, created_at,
                        client_request_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, userId, connectionId, command.symbol(), command.currency().name(),
                    command.predictedDirection().name(), command.modelVersion(), command.contractVersion(),
                    price, offset(now), offset(now), clientRequestId);
        });

        return new PredictionView(
                id, connectionId, command.symbol(), command.currency(), command.predictedDirection(),
                command.modelVersion(), command.contractVersion(), price, now, Map.of());
    }

    private Optional<PredictionView> findByClientRequestId(
            UUID userId,
            String clientRequestId
    ) {
        return jdbc.query("""
                SELECT id, broker_connection_id, symbol, currency, predicted_direction,
                       model_version, contract_version, baseline_price, predicted_at
                  FROM analysis_predictions
                 WHERE user_id = ?
                   AND client_request_id = ?
                """, (result, row) -> new PredictionView(
                        result.getObject("id", UUID.class),
                        result.getObject("broker_connection_id", UUID.class),
                        result.getString("symbol"),
                        Currency.valueOf(result.getString("currency")),
                        PredictedDirection.valueOf(result.getString("predicted_direction")),
                        result.getString("model_version"),
                        result.getString("contract_version"),
                        result.getBigDecimal("baseline_price"),
                        result.getObject("predicted_at", OffsetDateTime.class).toInstant(),
                        Map.of()),
                userId, clientRequestId).stream().findFirst();
    }

    private BatchItemResult replay(
            UUID connectionId,
            BatchCommand batchCommand,
            PredictionView prediction
    ) {
        var command = batchCommand.command();
        if (command != null
                && prediction.connectionId().equals(connectionId)
                && Objects.equals(prediction.symbol(), command.symbol())
                && prediction.currency() == command.currency()
                && prediction.predictedDirection() == command.predictedDirection()
                && Objects.equals(prediction.modelVersion(), command.modelVersion())
                && Objects.equals(prediction.contractVersion(), command.contractVersion())) {
            return BatchItemResult.duplicate(batchCommand.clientRequestId(), prediction);
        }
        return BatchItemResult.failed(
                batchCommand.clientRequestId(), BatchErrorCode.CLIENT_REQUEST_CONFLICT);
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

    private void requireActiveVersion(UUID userId, CreateCommand command) {
        if (!registry.isActive(userId, command.modelVersion(), command.contractVersion())) {
            throw new AnalysisPredictionException(
                    AnalysisPredictionException.Code.MODEL_VERSION_NOT_ACTIVE);
        }
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
     * worse than leaving them ungraded until a later tick catches each one nearer
     * its own due time.
     *
     * <p>A quote failure for the one pair being graded (e.g. the broker is briefly
     * unavailable) is skipped, not fatal — it stays ungraded and is retried on the next
     * tick, since nothing here is allowed to fabricate a price.
     */
    int evaluateDue(Instant now) {
        return evaluateDue(now, 100, Integer.MAX_VALUE, () -> true);
    }

    int evaluateDue(
            Instant now,
            int batchSize,
            int maxPerTick,
            BooleanSupplier continueBeforeBatch
    ) {
        return evaluateDueWithResult(now, batchSize, maxPerTick, continueBeforeBatch).succeeded();
    }

    EvaluationTickResult evaluateDueWithResult(
            Instant now,
            int batchSize,
            int maxPerTick,
            BooleanSupplier continueBeforeBatch
    ) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(continueBeforeBatch, "continueBeforeBatch");
        if (batchSize <= 0 || maxPerTick <= 0) {
            throw new IllegalArgumentException("batchSize and maxPerTick must be positive");
        }

        var attempted = new HashSet<UUID>();
        var quotes = new HashMap<QuoteKey, Optional<ObservedQuote>>();
        var succeeded = 0;
        var quoteFailed = 0;
        DueCursor cursor = null;
        while (attempted.size() < maxPerTick && continueBeforeBatch.getAsBoolean()) {
            var limit = Math.min(batchSize, maxPerTick - attempted.size());
            var batch = fetchDuePredictions(now, cursor, attempted, limit);
            if (batch.isEmpty()) {
                break;
            }
            for (var prediction : batch) {
                attempted.add(prediction.id());
                switch (evaluateOne(prediction, prediction.horizon(), prediction.dueAt(), quotes)) {
                    case GRADED -> succeeded++;
                    case QUOTE_FAILED -> quoteFailed++;
                    case DUPLICATE -> {
                        // The database uniqueness constraint already preserved the outcome.
                    }
                }
            }
            var last = batch.getLast();
            cursor = new DueCursor(last.dueAt(), last.id());
        }
        return new EvaluationTickResult(
                attempted.size(), succeeded, quoteFailed, attempted.size() >= maxPerTick);
    }

    private List<DuePrediction> fetchDuePredictions(
            Instant now,
            DueCursor cursor,
            HashSet<UUID> attempted,
            int limit
    ) {
        var sql = new StringBuilder("""
                WITH due_predictions AS (
                    SELECT prediction.id AS prediction_id,
                           prediction.broker_connection_id,
                           prediction.symbol,
                           prediction.predicted_direction,
                           prediction.baseline_price,
                           'D1' AS horizon,
                           prediction.predicted_at + INTERVAL '1 day' AS target_due_at
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
                       AND prediction.predicted_at <= ?
                    UNION ALL
                    SELECT prediction.id,
                           prediction.broker_connection_id,
                           prediction.symbol,
                           prediction.predicted_direction,
                           prediction.baseline_price,
                           'D5',
                           prediction.predicted_at + INTERVAL '5 days'
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
                       AND prediction.predicted_at <= ?
                    UNION ALL
                    SELECT prediction.id,
                           prediction.broker_connection_id,
                           prediction.symbol,
                           prediction.predicted_direction,
                           prediction.baseline_price,
                           'D20',
                           prediction.predicted_at + INTERVAL '20 days'
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
                       AND prediction.predicted_at <= ?
                )
                SELECT prediction_id, broker_connection_id, symbol, predicted_direction,
                       baseline_price, horizon, target_due_at
                  FROM due_predictions
                 WHERE 1 = 1
                """);
        var arguments = new ArrayList<>();
        arguments.add(offset(now.minus(Duration.ofDays(1))));
        arguments.add(offset(now.minus(Duration.ofDays(5))));
        arguments.add(offset(now.minus(Duration.ofDays(20))));
        if (cursor != null) {
            sql.append(" AND (target_due_at, prediction_id) > (?, ?)");
            arguments.add(offset(cursor.dueAt()));
            arguments.add(cursor.predictionId());
        }
        if (!attempted.isEmpty()) {
            sql.append(" AND prediction_id NOT IN (")
                    .append(String.join(",", attempted.stream().map(id -> "?").toList()))
                    .append(")");
            arguments.addAll(attempted);
        }
        sql.append(" ORDER BY target_due_at, prediction_id LIMIT ?");
        arguments.add(limit);

        return jdbc.query(sql.toString(), (resultSet, rowNum) -> new DuePrediction(
                resultSet.getObject("prediction_id", UUID.class),
                resultSet.getObject("broker_connection_id", UUID.class),
                resultSet.getString("symbol"),
                PredictedDirection.valueOf(resultSet.getString("predicted_direction")),
                resultSet.getBigDecimal("baseline_price"),
                Horizon.valueOf(resultSet.getString("horizon")),
                resultSet.getObject("target_due_at", OffsetDateTime.class).toInstant()
        ), arguments.toArray());
    }

    private EvaluationResult evaluateOne(
            DuePrediction prediction,
            Horizon horizon,
            Instant dueAt,
            Map<QuoteKey, Optional<ObservedQuote>> quotes
    ) {
        var key = new QuoteKey(prediction.connectionId(), prediction.symbol());
        var quote = quotes.computeIfAbsent(key, ignored -> fetchQuote(key.connectionId(), key.symbol()))
                .orElse(null);
        if (quote == null || quote.observationTime().isBefore(dueAt)) {
            return EvaluationResult.QUOTE_FAILED;
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
                    Duration.between(dueAt, quote.observationTime()).toMillis()) == 1
                    ? EvaluationResult.GRADED
                    : EvaluationResult.DUPLICATE;
        } catch (DuplicateKeyException ignored) {
            // Another evaluator already graded this pair — the unique constraint wins either way.
            return EvaluationResult.DUPLICATE;
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
            Horizon horizon,
            Instant dueAt
    ) {
    }

    private record DueCursor(Instant dueAt, UUID predictionId) {
    }

    private record QuoteKey(UUID connectionId, String symbol) {
    }

    private record ObservedQuote(BigDecimal price, Instant observationTime) {
    }

    private enum EvaluationResult {
        GRADED,
        QUOTE_FAILED,
        DUPLICATE
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

    public record BatchCommand(String clientRequestId, CreateCommand command) {
    }

    public record ModelContractScope(String modelVersion, String contractVersion) {
        boolean matches(CreateCommand command) {
            return command != null
                    && Objects.equals(modelVersion, command.modelVersion())
                    && Objects.equals(contractVersion, command.contractVersion());
        }
    }

    public enum BatchItemStatus {
        CREATED,
        DUPLICATE,
        FAILED
    }

    public enum BatchErrorCode {
        INVALID_INPUT,
        MODEL_VERSION_NOT_ACTIVE,
        QUOTE_CURRENCY_MISMATCH,
        QUOTE_UNAVAILABLE,
        QUOTE_FAILED,
        CLIENT_REQUEST_CONFLICT,
        API_KEY_SCOPE_MISMATCH
    }

    public record BatchItemResult(
            String clientRequestId,
            BatchItemStatus status,
            PredictionView prediction,
            BatchErrorCode errorCode
    ) {
        private static BatchItemResult created(String clientRequestId, PredictionView prediction) {
            return new BatchItemResult(clientRequestId, BatchItemStatus.CREATED, prediction, null);
        }

        private static BatchItemResult duplicate(String clientRequestId, PredictionView prediction) {
            return new BatchItemResult(clientRequestId, BatchItemStatus.DUPLICATE, prediction, null);
        }

        private static BatchItemResult failed(String clientRequestId, BatchErrorCode errorCode) {
            return new BatchItemResult(clientRequestId, BatchItemStatus.FAILED, null, errorCode);
        }
    }

    public record BatchView(List<BatchItemResult> results) {
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

    record EvaluationTickResult(
            int attempted,
            int succeeded,
            int quoteFailed,
            boolean countLimitReached
    ) {
    }
}
