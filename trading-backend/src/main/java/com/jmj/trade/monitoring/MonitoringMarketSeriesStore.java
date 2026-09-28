package com.jmj.trade.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
class MonitoringMarketSeriesStore {

    private static final List<String> RATIO_METRICS = List.of(
            "equity.rsp_spy", "equity.iwm_spy", "equity.soxx_spy", "credit.hyg_lqd", "credit.kre_xlf");
    private static final String SOURCE = "MarketDataAdapter.getCandles(1d,adjusted)";
    private static final Duration LIVE_SOURCE_MAX_AGE = Duration.ofMinutes(30);

    private final JdbcTemplate jdbc;

    MonitoringMarketSeriesStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void saveRatios(UUID userId, List<MonitoringEvaluationContract.MetricSeries> series) {
        if (userId == null || series == null) return;
        for (var metric : series) {
            if (!RATIO_METRICS.contains(metric.metric()) || metric.points() == null) continue;
            for (var point : metric.points()) {
                if (point.value() == null || point.asOf() == null || point.collectedAt() == null) continue;
                final BigDecimal value;
                try {
                    value = new BigDecimal(point.value());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                jdbc.update("""
                        INSERT INTO monitoring_market_observations (
                            user_id, metric, source, unit, cadence, value, as_of, collected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, metric, as_of) DO UPDATE SET
                            source = EXCLUDED.source, unit = EXCLUDED.unit, cadence = EXCLUDED.cadence,
                            value = EXCLUDED.value, collected_at = EXCLUDED.collected_at
                        WHERE EXCLUDED.collected_at > monitoring_market_observations.collected_at
                        """, userId, metric.metric(), metric.source(), metric.unit(), metric.cadence(), value,
                        OffsetDateTime.ofInstant(point.asOf(), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(point.collectedAt(), ZoneOffset.UTC));
            }
        }
    }

    List<MonitoringEvaluationContract.MetricSeries> loadRatios(UUID userId) {
        return loadRatios(userId, Instant.now());
    }

    /**
     * Loads bounded daily ratio history. A stored observation whose {@code collected_at} is older than the live
     * source freshness window (or in the future) relative to {@code now} keeps its point, source and observation
     * time but reports a {@code null} value: stale provider data must reach the evaluator as UNKNOWN, never as a
     * live value coerced from a previous fetch. Mirrors the quote adapter's freshness rule.
     */
    List<MonitoringEvaluationContract.MetricSeries> loadRatios(UUID userId, Instant now) {
        return RATIO_METRICS.stream().map(metric -> {
            var points = jdbc.query("""
                    SELECT value, as_of, collected_at FROM monitoring_market_observations
                     WHERE user_id = ? AND metric = ?
                     ORDER BY as_of DESC LIMIT 60
                    """, (resultSet, rowNum) -> {
                        var collectedAt = resultSet.getObject("collected_at", OffsetDateTime.class).toInstant();
                        var fresh = !collectedAt.isAfter(now)
                                && Duration.between(collectedAt, now).compareTo(LIVE_SOURCE_MAX_AGE) <= 0;
                        return new MonitoringEvaluationContract.MetricPoint(
                                fresh ? decimalText(resultSet.getBigDecimal("value")) : null,
                                resultSet.getObject("as_of", OffsetDateTime.class).toInstant(),
                                collectedAt);
                    }, userId, metric)
                    .stream().sorted(java.util.Comparator.comparing(
                            MonitoringEvaluationContract.MetricPoint::asOf)).toList();
            return new MonitoringEvaluationContract.MetricSeries(metric, "ratio", SOURCE, "DAILY", points);
        }).toList();
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
