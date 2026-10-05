package com.jmj.trade.investment.tactical;

import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Bar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Cohort;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Event;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.EventType;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.IndicatorBar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.MetricStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.MetricValue;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.OverlayStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Result;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Equal-weight theme and explicit-universe calculations over immutable security snapshots. */
public final class TacticalOverlayAggregationCalculator {

    private static final int SCALE = 8;
    private static final BigDecimal ONE = BigDecimal.ONE;

    public Aggregate calculateTheme(
            String themeId,
            LocalDate commonAsOf,
            LocalDate commonBaseDate,
            LocalDate cohortMaturityCutoff,
            int recentTradingBars,
            Map<String, Result> results,
            Map<String, List<Bar>> barsByTicker,
            List<MembershipInterval> memberships
    ) {
        requireText(themeId, "themeId");
        var normalizedResults = normalizeResults(results);
        var normalizedBars = normalizeBars(barsByTicker);
        var intervals = memberships == null ? List.<MembershipInterval>of() : List.copyOf(memberships);
        if (intervals.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("memberships must not contain null");
        }
        var members = intervals.stream().filter(interval -> interval.themeId().equals(themeId.trim()))
                .filter(interval -> interval.contains(commonAsOf)).map(MembershipInterval::ticker)
                .distinct().sorted().toList();
        return calculate("THEME", themeId.trim(), commonAsOf, commonBaseDate, cohortMaturityCutoff,
                recentTradingBars, members, normalizedResults, normalizedBars, intervals, themeId.trim());
    }

    public Aggregate calculateMarket(
            String entityKey,
            LocalDate commonAsOf,
            LocalDate commonBaseDate,
            LocalDate cohortMaturityCutoff,
            int recentTradingBars,
            List<String> trackedSymbols,
            Map<String, Result> results,
            Map<String, List<Bar>> barsByTicker
    ) {
        requireText(entityKey, "entityKey");
        if (recentTradingBars <= 0) throw new IllegalArgumentException("recentTradingBars must be positive");
        var normalizedResults = normalizeResults(results);
        var normalizedBars = normalizeBars(barsByTicker);
        var members = normalizeSymbols(trackedSymbols);
        return calculate("MARKET", entityKey.trim(), commonAsOf, commonBaseDate, cohortMaturityCutoff,
                recentTradingBars, members, normalizedResults, normalizedBars, List.of(), null);
    }

    private Aggregate calculate(
            String scope,
            String entityKey,
            LocalDate asOf,
            LocalDate baseDate,
            LocalDate maturityCutoff,
            int recentTradingBars,
            List<String> members,
            Map<String, Result> results,
            Map<String, List<Bar>> bars,
            List<MembershipInterval> memberships,
            String themeId
    ) {
        if (recentTradingBars <= 0) throw new IllegalArgumentException("recentTradingBars must be positive");
        var currentEligible = members.stream().filter(symbol -> memberHasCurrentData(symbol, asOf, results, bars)).count();
        var coverageReasons = new LinkedHashSet<String>();
        if (members.isEmpty()) coverageReasons.add("NO_EFFECTIVE_MEMBERS");
        if (currentEligible < members.size()) coverageReasons.add("MEMBER_DATA_MISSING");

        var inputRefs = new LinkedHashSet<UUID>();
        var sources = new LinkedHashSet<String>();
        if (themeId != null) {
            memberships.stream().filter(interval -> interval.themeId().equals(themeId))
                    .filter(interval -> interval.contains(asOf))
                    .forEach(interval -> { inputRefs.addAll(interval.inputRefs()); sources.add(interval.source()); });
        }
        members.forEach(symbol -> {
            var result = results.get(symbol);
            if (result != null) sources.add(result.source());
        });

        var metrics = new LinkedHashMap<String, Metric>();
        metrics.put("ThemeReturn20D", returnMetric(members, results, bars, asOf, baseDate, false, coverageReasons));
        metrics.put("ThemeRS20_SPY", returnMetric(members, results, bars, asOf, baseDate, true, coverageReasons));
        metrics.put("ThemeBreadthEMA21", breadthMetric(members, results, bars, asOf, true, coverageReasons));
        metrics.put("ThemeBreadthEMA50", breadthMetric(members, results, bars, asOf, false, coverageReasons));
        metrics.put("MedianRVOL", rvolMetric(members, results, bars, asOf, coverageReasons));

        var recentBreakouts = recentBreakouts(results, bars, memberships, themeId, members, asOf, recentTradingBars);
        recentBreakouts.values().forEach(breakout -> {
            var result = results.get(breakout.key().ticker());
            if (result != null) sources.add(result.source());
            if (themeId != null) memberships.stream()
                    .filter(interval -> interval.themeId().equals(themeId)
                            && interval.ticker().equals(breakout.key().ticker())
                            && interval.contains(breakout.key().date()))
                    .forEach(interval -> { inputRefs.addAll(interval.inputRefs()); sources.add(interval.source()); });
        });
        metrics.put("BreakoutCount", numericMetric(BigDecimal.valueOf(recentBreakouts.size()), members.size(),
                (int) currentEligible, coverageReasons, MetricStatus.OK));

        var maturityLimit = earlier(asOf, maturityCutoff);
        var cohorts3 = maturedCohorts(recentBreakouts, results, maturityLimit, 3);
        var cohorts5 = maturedCohorts(recentBreakouts, results, maturityLimit, 5);
        metrics.put("BreakoutSuccessRate3D", rateMetric(recentBreakouts.size(), cohorts3,
                Cohort::success, "NO_MATURED_3D_BREAKOUTS"));
        metrics.put("BreakoutSuccessRate5D", rateMetric(recentBreakouts.size(), cohorts5,
                Cohort::success, "NO_MATURED_5D_BREAKOUTS"));
        metrics.put("FailedBreakoutRate", rateMetric(recentBreakouts.size(), cohorts5,
                Cohort::failedByHorizon, "NO_MATURED_5D_BREAKOUTS"));
        var postBreakoutReturns = cohorts5.stream().filter(cohort -> cohort.returnPct() != null)
                .map(Cohort::returnPct).toList();
        var cohortReasons = cohortReasons(recentBreakouts.size(), cohorts5.size(), "NO_MATURED_5D_BREAKOUTS");
        metrics.put("MedianPostBreakoutReturn5D", numericMetric(median(postBreakoutReturns), recentBreakouts.size(),
                postBreakoutReturns.size(), cohortReasons, MetricStatus.INSUFFICIENT_HISTORY));

        var status = members.isEmpty() ? OverlayStatus.NOT_CONFIGURED
                : currentEligible < members.size() || metrics.values().stream().anyMatch(metric -> metric.status() != MetricStatus.OK)
                ? (currentEligible == 0 ? OverlayStatus.DATA_MISSING : OverlayStatus.PARTIAL)
                : OverlayStatus.OK;
        var reasons = new LinkedHashSet<>(coverageReasons);
        metrics.values().forEach(metric -> reasons.addAll(metric.reasons()));
        if (members.isEmpty()) reasons.add("NO_EFFECTIVE_MEMBERS");
        return new Aggregate(scope, entityKey, asOf, baseDate, status, List.copyOf(reasons), members.size(),
                (int) currentEligible, List.copyOf(inputRefs), sources.stream().filter(Objects::nonNull).sorted().toList(), metrics);
    }

    private Metric returnMetric(List<String> members, Map<String, Result> results, Map<String, List<Bar>> bars,
                                LocalDate asOf, LocalDate baseDate, boolean relative,
                                Set<String> coverageReasons) {
        var values = new ArrayList<BigDecimal>();
        var reasons = new LinkedHashSet<>(coverageReasons);
        var spyBars = bars.getOrDefault("SPY", List.of());
        var spyBase = exactBar(spyBars, baseDate);
        var spyEnd = exactBar(spyBars, asOf);
        for (var symbol : members) {
            var result = eligibleResult(symbol, asOf, results);
            var indicator = indicatorAt(result, asOf);
            var metric = indicator == null ? null : relative ? indicator.relativeStrength20() : indicator.return20();
            if (metric == null || metric.status() != MetricStatus.OK || metric.value() == null) {
                reasons.add(metric == null ? "INDICATOR_DATA_MISSING" : metric.status().name());
                continue;
            }
            var base = exactBar(bars.getOrDefault(symbol, List.of()), baseDate);
            var end = exactBar(bars.getOrDefault(symbol, List.of()), asOf);
            if (baseDate == null || asOf == null || baseDate.isAfter(asOf) || base == null || end == null) {
                reasons.add("COMMON_ENDPOINT_MISSING");
                continue;
            }
            if (!twentyTradingIntervals(bars.getOrDefault(symbol, List.of()), baseDate, asOf)) {
                reasons.add("TWENTY_BAR_ALIGNMENT_MISSING");
                continue;
            }
            var stockReturn = end.close().divide(base.close(), SCALE + 4, RoundingMode.HALF_UP).subtract(ONE);
            if (!relative) {
                values.add(stockReturn);
                continue;
            }
            if (spyBase == null || spyEnd == null
                    || !twentyTradingIntervals(spyBars, baseDate, asOf)) {
                reasons.add("SPY_COMMON_ENDPOINT_MISSING");
                continue;
            }
            var spyReturn = spyEnd.close().divide(spyBase.close(), SCALE + 4, RoundingMode.HALF_UP).subtract(ONE);
            values.add(ONE.add(stockReturn).divide(ONE.add(spyReturn), SCALE + 4, RoundingMode.HALF_UP)
                    .subtract(ONE));
        }
        var expected = members.size();
        return metric(mean(values), expected, values.size(), reasons, MetricStatus.INSUFFICIENT_HISTORY);
    }

    private Metric breadthMetric(List<String> members, Map<String, Result> results, Map<String, List<Bar>> bars,
                                 LocalDate asOf, boolean ema21, Set<String> coverageReasons) {
        var above = 0;
        var eligible = 0;
        var reasons = new LinkedHashSet<>(coverageReasons);
        for (var symbol : members) {
            var result = eligibleResult(symbol, asOf, results);
            var indicator = indicatorAt(result, asOf);
            var ema = indicator == null ? null : ema21 ? indicator.ema21() : indicator.ema50();
            var bar = exactBar(bars.getOrDefault(symbol, List.of()), asOf);
            if (ema == null || ema.status() != MetricStatus.OK || ema.value() == null || bar == null) {
                reasons.add(ema == null ? "EMA_DATA_MISSING" : ema.status().name());
                continue;
            }
            eligible++;
            if (bar.close().compareTo(ema.value()) > 0) above++;
        }
        var value = eligible == 0 ? null : BigDecimal.valueOf(above)
                .divide(BigDecimal.valueOf(eligible), SCALE, RoundingMode.HALF_UP);
        return metric(value, members.size(), eligible, reasons, MetricStatus.INSUFFICIENT_HISTORY);
    }

    private Metric rvolMetric(List<String> members, Map<String, Result> results, Map<String, List<Bar>> bars,
                              LocalDate asOf, Set<String> coverageReasons) {
        var values = new ArrayList<BigDecimal>();
        var reasons = new LinkedHashSet<>(coverageReasons);
        for (var symbol : members) {
            var result = eligibleResult(symbol, asOf, results);
            var indicator = indicatorAt(result, asOf);
            var rvol = indicator == null ? null : indicator.relativeVolume();
            if (rvol == null || rvol.status() != MetricStatus.OK || rvol.value() == null
                    || exactBar(bars.getOrDefault(symbol, List.of()), asOf) == null) {
                reasons.add(rvol == null ? "RVOL_DATA_MISSING" : rvol.status().name());
                continue;
            }
            values.add(rvol.value());
        }
        return metric(median(values), members.size(), values.size(), reasons, MetricStatus.INSUFFICIENT_HISTORY);
    }

    private LinkedHashMap<BreakoutKey, Breakout> recentBreakouts(
            Map<String, Result> results, Map<String, List<Bar>> bars,
            List<MembershipInterval> memberships, String themeId, List<String> currentMembers,
            LocalDate asOf, int recentTradingBars
    ) {
        var output = new LinkedHashMap<BreakoutKey, Breakout>();
        var marketMembers = Set.copyOf(currentMembers);
        if (themeId == null && currentMembers.isEmpty()) return output;
        for (var result : results.values()) {
            if (!usable(result) || result.symbol() == null) continue;
            var ticker = normalizeSymbol(result.symbol());
            if (themeId == null && !marketMembers.contains(ticker)) continue;
            var recentDates = recentDates(bars.getOrDefault(ticker, List.of()), asOf, recentTradingBars);
            if (recentDates.isEmpty()) continue;
            for (var event : result.events()) {
                if (event.type() != EventType.BREAKOUT || event.date() == null || event.date().isAfter(asOf)
                        || !recentDates.contains(event.date())) continue;
                var date = event.breakoutDate() == null ? event.date() : event.breakoutDate();
                if (themeId != null && !memberOn(memberships, themeId, ticker, date)) continue;
                var key = new BreakoutKey(ticker, date, normalized(event.breakoutLevel()));
                output.putIfAbsent(key, new Breakout(key));
            }
        }
        return output;
    }

    private List<Cohort> maturedCohorts(Map<BreakoutKey, Breakout> breakouts, Map<String, Result> results,
                                        LocalDate cutoff, int horizon) {
        if (cutoff == null || breakouts.isEmpty()) return List.of();
        var byKey = new LinkedHashMap<BreakoutKey, Cohort>();
        for (var result : results.values()) {
            if (!usable(result) || result.symbol() == null) continue;
            var ticker = normalizeSymbol(result.symbol());
            for (var cohort : result.cohorts()) {
                if (cohort.horizon() != horizon || cohort.breakoutDate() == null || cohort.endpointDate() == null
                        || !cohort.matured() || cohort.endpointDate().isAfter(cutoff)
                        || cohort.status() != MetricStatus.OK) continue;
                var key = new BreakoutKey(ticker, cohort.breakoutDate(), normalized(cohort.breakoutLevel()));
                if (breakouts.containsKey(key)) byKey.putIfAbsent(key, cohort);
            }
        }
        return List.copyOf(byKey.values());
    }

    private Metric rateMetric(int expected, List<Cohort> matured, java.util.function.Function<Cohort, Boolean> selector,
                              String noMaturityReason) {
        var eligible = matured.stream().filter(cohort -> selector.apply(cohort) != null).toList();
        var trueCount = eligible.stream().filter(cohort -> Boolean.TRUE.equals(selector.apply(cohort))).count();
        var reasons = cohortReasons(expected, eligible.size(), noMaturityReason);
        var value = eligible.isEmpty() ? null : BigDecimal.valueOf(trueCount)
                .divide(BigDecimal.valueOf(eligible.size()), SCALE, RoundingMode.HALF_UP);
        return metric(value, expected, eligible.size(), reasons, MetricStatus.INSUFFICIENT_HISTORY);
    }

    private LinkedHashSet<String> cohortReasons(int expected, int eligible, String noMaturityReason) {
        var reasons = new LinkedHashSet<String>();
        if (expected == 0) reasons.add("NO_RECENT_BREAKOUTS");
        else if (eligible < expected) reasons.add(noMaturityReason);
        return reasons;
    }

    private Metric metric(BigDecimal value, int expected, int eligible, Set<String> reasons,
                          MetricStatus noValueStatus) {
        var status = value != null
                ? (eligible < expected || !reasons.isEmpty() ? MetricStatus.PARTIAL : MetricStatus.OK)
                : chooseUnavailableStatus(reasons, expected, noValueStatus);
        return new Metric(value, expected, eligible, status, List.copyOf(reasons));
    }

    private Metric numericMetric(BigDecimal value, int expected, int eligible, Set<String> reasons,
                                 MetricStatus noValueStatus) {
        return metric(value, expected, eligible, reasons, noValueStatus);
    }

    private MetricStatus chooseUnavailableStatus(Set<String> reasons, int expected, MetricStatus fallback) {
        if (reasons.contains("SOURCE_CONFLICT")) return MetricStatus.SOURCE_CONFLICT;
        if (reasons.contains("CORPORATE_ACTION_CONFLICT")) return MetricStatus.CORPORATE_ACTION_CONFLICT;
        if (reasons.contains("NOT_CONFIGURED") || (expected == 0 && reasons.contains("NO_EFFECTIVE_MEMBERS"))) {
            return MetricStatus.NOT_CONFIGURED;
        }
        if (expected == 0) return MetricStatus.INSUFFICIENT_HISTORY;
        if (reasons.contains("MEMBER_DATA_MISSING") || reasons.contains("COMMON_ENDPOINT_MISSING")
                || reasons.contains("SPY_COMMON_ENDPOINT_MISSING")) return MetricStatus.DATA_MISSING;
        return fallback;
    }

    private boolean memberHasCurrentData(String ticker, LocalDate asOf, Map<String, Result> results,
                                         Map<String, List<Bar>> bars) {
        return eligibleResult(ticker, asOf, results) != null
                && exactBar(bars.getOrDefault(ticker, List.of()), asOf) != null;
    }

    private Result eligibleResult(String ticker, LocalDate asOf, Map<String, Result> results) {
        var result = results.get(ticker);
        return usable(result) && result.asOf() != null && asOf != null && !result.asOf().isBefore(asOf)
                && indicatorAt(result, asOf) != null ? result : null;
    }

    private boolean usable(Result result) {
        return result != null && (result.status() == OverlayStatus.OK || result.status() == OverlayStatus.PARTIAL);
    }

    private IndicatorBar indicatorAt(Result result, LocalDate date) {
        if (result == null || date == null) return null;
        return result.indicators().stream().filter(indicator -> date.equals(indicator.date())).findFirst().orElse(null);
    }

    private List<LocalDate> recentDates(List<Bar> bars, LocalDate asOf, int window) {
        if (asOf == null || exactBar(bars, asOf) == null) return List.of();
        var dates = bars.stream().filter(bar -> !bar.date().isAfter(asOf)).map(Bar::date).toList();
        return dates.subList(Math.max(0, dates.size() - window), dates.size());
    }

    private boolean memberOn(List<MembershipInterval> memberships, String themeId, String ticker, LocalDate date) {
        return memberships.stream().anyMatch(interval -> interval.themeId().equals(themeId)
                && interval.ticker().equals(ticker) && interval.contains(date));
    }

    private Bar exactBar(List<Bar> bars, LocalDate date) {
        if (date == null) return null;
        return bars.stream().filter(bar -> date.equals(bar.date())).findFirst().orElse(null);
    }

    private boolean twentyTradingIntervals(List<Bar> bars, LocalDate baseDate, LocalDate asOf) {
        var baseIndex = -1;
        var asOfIndex = -1;
        for (var index = 0; index < bars.size(); index++) {
            if (baseDate.equals(bars.get(index).date())) baseIndex = index;
            if (asOf.equals(bars.get(index).date())) asOfIndex = index;
        }
        return baseIndex >= 0 && asOfIndex - baseIndex == 20;
    }

    private static LocalDate earlier(LocalDate left, LocalDate right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        var sorted = values.stream().sorted().toList();
        var middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, Result> normalizeResults(Map<String, Result> values) {
        var output = new LinkedHashMap<String, Result>();
        if (values == null) return Map.of();
        values.forEach((key, result) -> {
            if (key == null || key.isBlank() || result == null) throw new IllegalArgumentException("results are invalid");
            var normalized = normalizeSymbol(key);
            if (output.putIfAbsent(normalized, result) != null) throw new IllegalArgumentException("duplicate result symbol");
        });
        return Collections.unmodifiableMap(output);
    }

    private static Map<String, List<Bar>> normalizeBars(Map<String, List<Bar>> values) {
        var output = new LinkedHashMap<String, List<Bar>>();
        if (values == null) return Map.of();
        values.forEach((key, sourceBars) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("bar symbol is invalid");
            var ticker = normalizeSymbol(key);
            var ordered = sourceBars == null ? List.<Bar>of() : List.copyOf(sourceBars);
            for (var index = 1; index < ordered.size(); index++) {
                if (!ordered.get(index).date().isAfter(ordered.get(index - 1).date())) {
                    throw new IllegalArgumentException("bars must have unique ascending dates");
                }
            }
            if (output.putIfAbsent(ticker, ordered) != null) throw new IllegalArgumentException("duplicate bar symbol");
        });
        return Collections.unmodifiableMap(output);
    }

    private static List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null) return List.of();
        var output = new LinkedHashSet<String>();
        for (var symbol : symbols) {
            requireText(symbol, "trackedSymbol");
            var normalized = normalizeSymbol(symbol);
            if (!"SPY".equals(normalized)) output.add(normalized);
        }
        return output.stream().sorted().toList();
    }

    private static String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    public record MembershipInterval(String themeId, String ticker, LocalDate fromInclusive, LocalDate toExclusive,
                                     String source, List<UUID> inputRefs) {
        public MembershipInterval {
            requireText(themeId, "themeId");
            requireText(ticker, "ticker");
            themeId = themeId.trim();
            ticker = normalizeSymbol(ticker);
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            if (toExclusive != null && !toExclusive.isAfter(fromInclusive)) {
                throw new IllegalArgumentException("toExclusive must follow fromInclusive");
            }
            requireText(source, "source");
            source = source.trim();
            inputRefs = inputRefs == null ? List.of() : inputRefs.stream().distinct().sorted().toList();
        }

        public boolean contains(LocalDate date) {
            return date != null && !date.isBefore(fromInclusive) && (toExclusive == null || date.isBefore(toExclusive));
        }
    }

    public record Metric(BigDecimal value, int expectedCount, int eligibleCount,
                         MetricStatus status, List<String> reasons) {
        public Metric {
            Objects.requireNonNull(status, "status");
            if (expectedCount < 0 || eligibleCount < 0 || eligibleCount > expectedCount) {
                throw new IllegalArgumentException("metric counts are invalid");
            }
            if (status == MetricStatus.OK && value == null) throw new IllegalArgumentException("OK metric requires value");
            reasons = reasons == null ? List.of() : reasons.stream().distinct().toList();
        }
    }

    public record Aggregate(String scope, String entityKey, LocalDate asOf, LocalDate baseDate,
                            OverlayStatus status, List<String> reasons, int expectedMembers, int eligibleMembers,
                            List<UUID> inputRefs, List<String> sources, Map<String, Metric> metrics) {
        public Aggregate {
            requireText(scope, "scope");
            requireText(entityKey, "entityKey");
            Objects.requireNonNull(status, "status");
            if (expectedMembers < 0 || eligibleMembers < 0 || eligibleMembers > expectedMembers) {
                throw new IllegalArgumentException("aggregate counts are invalid");
            }
            reasons = reasons == null ? List.of() : reasons.stream().distinct().toList();
            inputRefs = inputRefs == null ? List.of() : inputRefs.stream().distinct().sorted().toList();
            sources = sources == null ? List.of() : sources.stream().filter(Objects::nonNull).distinct().sorted().toList();
            metrics = metrics == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        }
    }

    private record BreakoutKey(String ticker, LocalDate date, BigDecimal level) { }
    private record Breakout(BreakoutKey key) { }
}
