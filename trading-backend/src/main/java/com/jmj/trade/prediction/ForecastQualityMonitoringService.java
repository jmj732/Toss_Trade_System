package com.jmj.trade.prediction;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Read-only quality projection over immutable forecast and outcome rows. */
public final class ForecastQualityMonitoringService {

    static final int DEFAULT_MINIMUM_SAMPLE_COUNT = 10;
    private static final BigDecimal HIT_RATE_DRIFT = new BigDecimal("0.10");
    private static final BigDecimal ERROR_DRIFT = new BigDecimal("0.05");
    private static final Duration DEFAULT_PERIOD = Duration.ofDays(30);

    private static final String SQL = """
            SELECT forecast.id AS forecast_id, forecast.symbol, forecast.model_version,
                   forecast.contract_version, forecast.response::text,
                   prediction.id AS prediction_id, outcome.horizon,
                   outcome.actual_return, outcome.direction_correct
              FROM stock_forecasts forecast
              JOIN analysis_predictions prediction
                ON prediction.id = forecast.prediction_id
               AND prediction.broker_connection_id = ?
              LEFT JOIN analysis_prediction_outcomes outcome
                ON outcome.prediction_id = prediction.id
             WHERE forecast.user_id = ?
               AND forecast.evaluated_at >= ?
               AND forecast.evaluated_at < ?
               AND (CAST(? AS VARCHAR) IS NULL OR forecast.model_version = ?)
               AND (CAST(? AS VARCHAR) IS NULL OR forecast.contract_version = ?)
               AND (CAST(? AS VARCHAR) IS NULL OR forecast.symbol = ?)
             ORDER BY forecast.symbol, forecast.model_version, forecast.contract_version,
                      forecast.id, outcome.horizon
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final int minimumSampleCount;

    public ForecastQualityMonitoringService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            int minimumSampleCount
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (minimumSampleCount <= 0) {
            throw new IllegalArgumentException("minimumSampleCount must be positive");
        }
        this.minimumSampleCount = minimumSampleCount;
    }

    ForecastQualityView read(
            UUID userId,
            UUID connectionId,
            Instant from,
            Instant to,
            String modelVersion,
            String contractVersion,
            String symbol,
            Instant now
    ) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(now, "now");
        requireOwnedConnection(userId, connectionId);

        var period = period(from, to, now);
        var baseline = new Period(
                period.from().minus(Duration.between(period.from(), period.to())),
                period.from());
        var normalizedSymbol = normalizeSymbol(symbol);
        var current = fetch(
                userId, connectionId, period.from(), period.to(), modelVersion, contractVersion, normalizedSymbol);
        var previous = fetch(
                userId, connectionId, baseline.from(), baseline.to(), modelVersion, contractVersion, normalizedSymbol);
        return new ForecastQualityView(
                period,
                baseline,
                minimumSampleCount,
                aggregate(current, previous, minimumSampleCount));
    }

    private Period period(Instant from, Instant to, Instant now) {
        var effectiveTo = to == null ? now : to;
        var effectiveFrom = from == null ? effectiveTo.minus(DEFAULT_PERIOD) : from;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.INVALID_INPUT);
        }
        return new Period(effectiveFrom, effectiveTo);
    }

    private List<Observation> fetch(
            UUID userId,
            UUID connectionId,
            Instant from,
            Instant to,
            String modelVersion,
            String contractVersion,
            String symbol
    ) {
        var captures = new LinkedHashMap<UUID, Capture>();
        var rows = jdbc.query(SQL, (result, rowNumber) -> new DatabaseRow(
                result.getObject("forecast_id", UUID.class),
                result.getString("symbol"),
                result.getString("model_version"),
                result.getString("contract_version"),
                result.getString("response"),
                result.getObject("prediction_id", UUID.class),
                result.getString("horizon"),
                result.getBigDecimal("actual_return"),
                result.getBoolean("direction_correct")),
                connectionId, userId, offset(from), offset(to), modelVersion, modelVersion,
                contractVersion, contractVersion, symbol, symbol);
        for (var row : rows) {
            var forecastId = row.forecastId;
            var capture = captures.computeIfAbsent(forecastId, ignored -> new Capture(
                    row.symbol,
                    row.modelVersion,
                    row.contractVersion,
                    row.predictionId,
                    metrics(row.response)));
            var horizon = row.horizon;
            if (horizon != null) {
                capture.outcomes.put(
                        Horizon.valueOf(horizon),
                        new Outcome(
                                row.actualReturn,
                                row.directionCorrect));
            }
        }

        var observations = new ArrayList<Observation>();
        for (var capture : captures.values()) {
            for (var horizon : Horizon.values()) {
                var outcome = capture.outcomes.get(horizon);
                observations.add(new Observation(
                        capture.symbol,
                        capture.modelVersion,
                        capture.contractVersion,
                        horizon,
                        capture.metrics.get(metricName(horizon)),
                        outcome == null ? null : outcome.actualReturn,
                        outcome != null && outcome.directionCorrect,
                        outcome != null,
                        capture.predictionId != null));
            }
        }
        return observations;
    }

    private Map<String, BigDecimal> metrics(String response) {
        try {
            var root = objectMapper.readTree(response);
            var result = new LinkedHashMap<String, BigDecimal>();
            for (var metric : root.path("forecasts")) {
                var value = metric.path("value");
                if (value.isMissingNode() || value.isNull()) {
                    result.put(metric.path("name").asText(), null);
                    continue;
                }
                try {
                    result.put(metric.path("name").asText(), new BigDecimal(value.asText()));
                } catch (NumberFormatException ignored) {
                    result.put(metric.path("name").asText(), null);
                }
            }
            return result;
        } catch (RuntimeException ignored) {
            // A corrupt immutable row is visible as missing forecast data for that item.
            return Map.of();
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
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.INVALID_INPUT);
        }
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        var normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 30 || !normalized.matches("[A-Z0-9]+(?:[.-][A-Z0-9]+)*")) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.INVALID_INPUT);
        }
        return normalized;
    }

    static List<QualityRow> aggregate(
            List<Observation> current,
            List<Observation> baseline,
            int minimumSampleCount
    ) {
        if (minimumSampleCount <= 0) {
            throw new IllegalArgumentException("minimumSampleCount must be positive");
        }
        var currentGroups = group(current);
        var baselineGroups = group(baseline);
        var result = new ArrayList<QualityRow>();
        for (var entry : currentGroups.entrySet()) {
            var summary = summarize(entry.getValue(), minimumSampleCount);
            var baselineObservations = baselineGroups.get(entry.getKey());
            var baselineSummary = baselineObservations == null
                    ? null : summarize(baselineObservations, minimumSampleCount);
            result.add(new QualityRow(
                    entry.getKey().symbol,
                    entry.getKey().modelVersion,
                    entry.getKey().contractVersion,
                    entry.getKey().horizon,
                    summary.status,
                    summary.sampleCount,
                    summary.calibrationSampleCount,
                    summary.eligibleForecastCount,
                    summary.pendingCount,
                    summary.missingForecastCount,
                    minimumSampleCount,
                    summary.hitRate,
                    summary.meanError,
                    summary.meanAbsoluteError,
                    summary.calibrationError,
                    summary.brierScore,
                    drift(summary, baselineSummary)));
        }
        return result;
    }

    private static Map<Key, List<Observation>> group(List<Observation> observations) {
        var groups = new LinkedHashMap<Key, List<Observation>>();
        for (var observation : observations) {
            groups.computeIfAbsent(
                    new Key(observation.symbol(), observation.modelVersion(),
                            observation.contractVersion(), observation.horizon()),
                    ignored -> new ArrayList<>()).add(observation);
        }
        return groups;
    }

    private static Summary summarize(List<Observation> observations, int minimumSampleCount) {
        var eligible = 0;
        var pending = 0;
        var missing = 0;
        var sample = 0;
        var calibrationSample = 0;
        var hits = 0;
        var errorSum = BigDecimal.ZERO;
        var absoluteErrorSum = BigDecimal.ZERO;
        var calibrationErrorSum = BigDecimal.ZERO;
        var brierSum = BigDecimal.ZERO;
        for (var observation : observations) {
            if (observation.forecastValue() == null) {
                missing++;
                continue;
            }
            eligible++;
            if (!observation.outcomePresent()) {
                if (observation.gradable()) {
                    pending++;
                }
                continue;
            }
            sample++;
            var actual = observation.actualReturn();
            if (observation.horizon() == Horizon.D1) {
                if (observation.directionCorrect()) {
                    hits++;
                }
                if (actual.signum() != 0) {
                    calibrationSample++;
                    var actualUp = actual.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    var error = actualUp.subtract(observation.forecastValue());
                    calibrationErrorSum = calibrationErrorSum.add(error);
                    brierSum = brierSum.add(error.multiply(error));
                }
            } else {
                if (actual.signum() != 0
                        && actual.signum() == observation.forecastValue().signum()) {
                    hits++;
                }
                var error = actual.subtract(observation.forecastValue());
                errorSum = errorSum.add(error);
                absoluteErrorSum = absoluteErrorSum.add(error.abs());
            }
        }
        var status = sample >= minimumSampleCount
                ? Status.SUFFICIENT
                : sample == 0 && eligible == 0 ? Status.NO_DATA : Status.DATA_SHORTAGE;
        var enough = status == Status.SUFFICIENT;
        return new Summary(
                status,
                sample,
                calibrationSample,
                eligible,
                pending,
                missing,
                enough ? divide(hits, sample) : null,
                enough && !isD1(observations) ? divide(errorSum, sample, 10) : null,
                enough && !isD1(observations) ? divide(absoluteErrorSum, sample, 10) : null,
                enough && isD1(observations) && calibrationSample > 0
                        ? divide(calibrationErrorSum, calibrationSample, 10) : null,
                enough && isD1(observations) && calibrationSample > 0
                        ? divide(brierSum, calibrationSample, 10) : null);
    }

    private static boolean isD1(List<Observation> observations) {
        return observations.getFirst().horizon() == Horizon.D1;
    }

    private static DriftView drift(Summary current, Summary baseline) {
        if (baseline == null || baseline.status == Status.NO_DATA) {
            return new DriftView(DriftStatus.NO_BASELINE, 0, null, null, null, false);
        }
        if (current.status != Status.SUFFICIENT || baseline.status != Status.SUFFICIENT) {
            return new DriftView(
                    DriftStatus.DATA_SHORTAGE, baseline.sampleCount, null, null, null, false);
        }
        var hitDelta = delta(current.hitRate, baseline.hitRate);
        var errorDelta = delta(current.meanAbsoluteError, baseline.meanAbsoluteError);
        var calibrationDelta = delta(current.calibrationError, baseline.calibrationError);
        var drift = exceeds(hitDelta, HIT_RATE_DRIFT)
                || exceeds(errorDelta, ERROR_DRIFT)
                || exceeds(calibrationDelta, ERROR_DRIFT);
        var degraded = (hitDelta != null && hitDelta.compareTo(HIT_RATE_DRIFT.negate()) <= 0)
                || (errorDelta != null && errorDelta.compareTo(ERROR_DRIFT) >= 0)
                || (calibrationDelta != null
                && current.calibrationError.abs().compareTo(baseline.calibrationError.abs().add(ERROR_DRIFT)) >= 0);
        return new DriftView(
                drift ? DriftStatus.DRIFT : DriftStatus.STABLE,
                baseline.sampleCount,
                hitDelta,
                errorDelta,
                calibrationDelta,
                degraded);
    }

    private static boolean exceeds(BigDecimal value, BigDecimal threshold) {
        return value != null && value.abs().compareTo(threshold) >= 0;
    }

    private static BigDecimal delta(BigDecimal current, BigDecimal baseline) {
        return current == null || baseline == null ? null : current.subtract(baseline);
    }

    private static BigDecimal divide(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal divide(BigDecimal numerator, int denominator, int scale) {
        return numerator.divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }

    private static String metricName(Horizon horizon) {
        return switch (horizon) {
            case D1 -> "forecast.d1_up_probability";
            case D5 -> "forecast.d5_expected_return";
            case D20 -> "forecast.d20_expected_return";
        };
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record Period(Instant from, Instant to) {
    }

    public enum Status {
        SUFFICIENT,
        DATA_SHORTAGE,
        NO_DATA
    }

    public enum DriftStatus {
        STABLE,
        DRIFT,
        DATA_SHORTAGE,
        NO_BASELINE
    }

    public record ForecastQualityView(
            Period period,
            Period baselinePeriod,
            int minimumSampleCount,
            List<QualityRow> rows
    ) {
    }

    public record QualityRow(
            String symbol,
            String modelVersion,
            String contractVersion,
            Horizon horizon,
            Status status,
            int sampleCount,
            int calibrationSampleCount,
            int eligibleForecastCount,
            int pendingCount,
            int missingForecastCount,
            int minimumSampleCount,
            BigDecimal hitRate,
            BigDecimal meanError,
            BigDecimal meanAbsoluteError,
            BigDecimal calibrationError,
            BigDecimal brierScore,
            DriftView drift
    ) {
    }

    public record DriftView(
            DriftStatus status,
            int baselineSampleCount,
            BigDecimal hitRateDelta,
            BigDecimal meanAbsoluteErrorDelta,
            BigDecimal calibrationErrorDelta,
            boolean degraded
    ) {
    }

    record Observation(
            String symbol,
            String modelVersion,
            String contractVersion,
            Horizon horizon,
            BigDecimal forecastValue,
            BigDecimal actualReturn,
            boolean directionCorrect,
            boolean outcomePresent,
            boolean gradable
    ) {
    }

    private record Key(String symbol, String modelVersion, String contractVersion, Horizon horizon) {
    }

    private record Outcome(BigDecimal actualReturn, boolean directionCorrect) {
    }

    private record DatabaseRow(
            UUID forecastId,
            String symbol,
            String modelVersion,
            String contractVersion,
            String response,
            UUID predictionId,
            String horizon,
            BigDecimal actualReturn,
            boolean directionCorrect
    ) {
    }

    private record Summary(
            Status status,
            int sampleCount,
            int calibrationSampleCount,
            int eligibleForecastCount,
            int pendingCount,
            int missingForecastCount,
            BigDecimal hitRate,
            BigDecimal meanError,
            BigDecimal meanAbsoluteError,
            BigDecimal calibrationError,
            BigDecimal brierScore
    ) {
    }

    private static final class Capture {
        private final String symbol;
        private final String modelVersion;
        private final String contractVersion;
        private final UUID predictionId;
        private final Map<String, BigDecimal> metrics;
        private final Map<Horizon, Outcome> outcomes = new EnumMap<>(Horizon.class);

        private Capture(
                String symbol,
                String modelVersion,
                String contractVersion,
                UUID predictionId,
                Map<String, BigDecimal> metrics
        ) {
            this.symbol = symbol;
            this.modelVersion = modelVersion;
            this.contractVersion = contractVersion;
            this.predictionId = predictionId;
            this.metrics = metrics;
        }
    }
}
