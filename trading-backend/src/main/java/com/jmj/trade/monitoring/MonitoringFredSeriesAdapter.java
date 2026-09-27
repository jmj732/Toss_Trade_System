package com.jmj.trade.monitoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Reads the numeric payload already preserved in official FRED source IDs. */
final class MonitoringFredSeriesAdapter {

    private static final Map<String, Series> SERIES = Map.ofEntries(
            Map.entry("DGS10", new Series("rates.nominal_10y", "percent", false)),
            Map.entry("DFII10", new Series("rates.real_10y", "percent", false)),
            Map.entry("T10YIE", new Series("rates.breakeven_10y", "percent", false)),
            Map.entry("BAMLH0A0HYM2", new Series("credit.hy_oas", "bps", true)),
            Map.entry("BAMLC0A0CM", new Series("credit.ig_oas", "bps", true)),
            Map.entry("SOFR", new Series("funding.sofr", "percent", false)),
            Map.entry("DFF", new Series("funding.fed_funds", "percent", false)),
            Map.entry("DEXJPUS", new Series("fx.usd_jpy", "jpy_per_usd", false)),
            Map.entry("DEXKOUS", new Series("fx.usd_krw", "krw_per_usd", false)),
            Map.entry("DTWEXBGS", new Series("fx.broad_dollar_index", "index", false)));

    private MonitoringFredSeriesAdapter() {
    }

    static Optional<Observation> parse(
            String sourceEventId,
            String seriesId,
            Instant asOf,
            Instant collectedAt
    ) {
        if (sourceEventId == null || seriesId == null || asOf == null || collectedAt == null
                || !sourceEventId.startsWith(seriesId + ":")) {
            return Optional.empty();
        }
        var definition = SERIES.get(seriesId);
        if (definition == null) {
            return Optional.empty();
        }
        var valueText = sourceEventId.substring(sourceEventId.lastIndexOf(':') + 1).trim();
        try {
            var value = new BigDecimal(valueText);
            if (definition.basisPoints()) {
                value = value.movePointRight(2);
            }
            return Optional.of(new Observation(
                    definition.metric(), value.stripTrailingZeros().toPlainString(), definition.unit(),
                    "FRED", "DAILY", asOf, collectedAt));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    static List<Observation> latestByMetricAndDate(List<Observation> observations) {
        var latest = new LinkedHashMap<String, Observation>();
        for (var observation : observations == null ? List.<Observation>of() : observations) {
            var key = observation.metric() + ":" + observation.asOf();
            latest.merge(key, observation, (previous, candidate) ->
                    candidate.collectedAt().isAfter(previous.collectedAt()) ? candidate : previous);
        }
        return latest.values().stream()
                .sorted(Comparator.comparing(Observation::metric).thenComparing(Observation::asOf))
                .toList();
    }

    record Observation(
            String metric,
            String value,
            String unit,
            String source,
            String cadence,
            Instant asOf,
            Instant collectedAt
    ) {
    }

    private record Series(String metric, String unit, boolean basisPoints) {
    }
}
