package com.jmj.trade.investment.tactical;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Pure calculations over caller-validated daily source bars. */
public final class TacticalOverlayCalculator {

    private static final int SCALE = 10;

    private final TacticalOverlayProperties properties;

    public TacticalOverlayCalculator(TacticalOverlayProperties properties) {
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
    }

    public Result calculate(Input input) {
        java.util.Objects.requireNonNull(input, "input");
        var bars = input.bars().stream().filter(bar -> !bar.date().isAfter(input.asOf())).toList();
        var status = overallStatus(input, bars);
        if (!input.configured() || input.sourceConflict() || bars.isEmpty()) {
            return emptyResult(input, bars, status);
        }

        var series = calculateIndicators(input, bars);
        var events = calculateEvents(input, bars, series);
        var cohorts = calculateCohorts(input, bars, events);
        var stage = calculateStage(bars, series, input);
        var stageStatus = stage == null ? stageUnavailableStatus(input, bars) : OverlayStatus.OK;
        var anchoredVwaps = calculateAnchoredVwaps(input, bars);
        var performance = calculatePerformance(input, bars);
        return new Result(input.symbol(), bars.getLast().date(), input.source(), status,
                input.priceAdjustmentStatus(), stage, stageStatus, VwapMethod.DAILY_ROLLING_20,
                emaSeeds(bars), series.indicators(), events, cohorts, anchoredVwaps, performance);
    }

    private static OverlayStatus overallStatus(Input input, List<Bar> bars) {
        if (!input.configured()) return OverlayStatus.NOT_CONFIGURED;
        if (input.sourceConflict()) return OverlayStatus.SOURCE_CONFLICT;
        if (bars.isEmpty()) return OverlayStatus.DATA_MISSING;
        if (bars.size() < 50) return OverlayStatus.INSUFFICIENT_HISTORY;
        return input.historyComplete() ? OverlayStatus.OK : OverlayStatus.PARTIAL;
    }

    private static Result emptyResult(Input input, List<Bar> bars, OverlayStatus status) {
        var performance = unavailablePerformance(input.performanceEntry(), status);
        return new Result(input.symbol(), bars.isEmpty() ? null : bars.getLast().date(), input.source(), status,
                input.priceAdjustmentStatus(), null, status, VwapMethod.DAILY_ROLLING_20, List.of(), List.of(),
                List.of(), List.of(), List.of(), performance);
    }

    private IndicatorSeries calculateIndicators(Input input, List<Bar> bars) {
        var ema9 = ema(bars, 9);
        var ema21 = ema(bars, 21);
        var ema50 = ema(bars, 50);
        var dailyVwap = new BigDecimal[bars.size()];
        var rvol = new BigDecimal[bars.size()];
        var return20 = new BigDecimal[bars.size()];
        var return60 = new BigDecimal[bars.size()];
        var relativeStrength20 = new BigDecimal[bars.size()];
        var relativeStrength60 = new BigDecimal[bars.size()];
        var vwapStatus = new MetricStatus[bars.size()];
        var rvolStatus = new MetricStatus[bars.size()];
        var return20Status = new MetricStatus[bars.size()];
        var return60Status = new MetricStatus[bars.size()];
        var rs20Status = new MetricStatus[bars.size()];
        var rs60Status = new MetricStatus[bars.size()];
        var spyByDate = new java.util.HashMap<LocalDate, BenchmarkBar>();
        input.spyBars().stream().filter(bar -> !bar.date().isAfter(input.asOf()))
                .forEach(bar -> spyByDate.put(bar.date(), bar));
        var splitDates = java.util.Set.copyOf(input.splitConflictDates());

        for (int index = 0; index < bars.size(); index++) {
            calculateRollingVwap(bars, index, dailyVwap, vwapStatus);
            calculateRvol(bars, index, rvol, rvolStatus);
            calculateReturn(bars, index, 20, return20, return20Status, splitDates);
            calculateReturn(bars, index, 60, return60, return60Status, splitDates);
            calculateRelativeStrength(bars, index, 20, spyByDate, relativeStrength20, rs20Status, splitDates);
            calculateRelativeStrength(bars, index, 60, spyByDate, relativeStrength60, rs60Status, splitDates);
        }

        var indicators = new java.util.ArrayList<IndicatorBar>(bars.size());
        for (int index = 0; index < bars.size(); index++) {
            var bar = bars.get(index);
            indicators.add(new IndicatorBar(bar.date(), emaValue(ema9, index, 9), emaValue(ema21, index, 21),
                    emaValue(ema50, index, 50), value(dailyVwap[index], vwapStatus[index]),
                    value(rvol[index], rvolStatus[index]), value(return20[index], return20Status[index]),
                    value(return60[index], return60Status[index]),
                    value(relativeStrength20[index], rs20Status[index]),
                    value(relativeStrength60[index], rs60Status[index])));
        }
        return new IndicatorSeries(indicators, ema9, ema21, ema50, rvol, rvolStatus,
                return20, return60, relativeStrength20, relativeStrength60);
    }

    private static BigDecimal[] ema(List<Bar> bars, int period) {
        var values = new BigDecimal[bars.size()];
        if (bars.size() < period) return values;
        var seed = BigDecimal.ZERO;
        for (int index = 0; index < period; index++) {
            seed = seed.add(bars.get(index).close());
        }
        var prior = seed.divide(BigDecimal.valueOf(period), SCALE, java.math.RoundingMode.HALF_UP);
        values[period - 1] = prior;
        var alpha = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), SCALE,
                java.math.RoundingMode.HALF_UP);
        for (int index = period; index < bars.size(); index++) {
            prior = bars.get(index).close().multiply(alpha)
                    .add(prior.multiply(BigDecimal.ONE.subtract(alpha)))
                    .setScale(SCALE, java.math.RoundingMode.HALF_UP);
            values[index] = prior;
        }
        return values;
    }

    private static void calculateRollingVwap(List<Bar> bars, int index, BigDecimal[] values,
                                             MetricStatus[] statuses) {
        if (index < 19) {
            statuses[index] = MetricStatus.INSUFFICIENT_HISTORY;
            return;
        }
        var numerator = BigDecimal.ZERO;
        var volume = BigDecimal.ZERO;
        for (int offset = index - 19; offset <= index; offset++) {
            var bar = bars.get(offset);
            var typical = bar.high().add(bar.low()).add(bar.close())
                    .divide(BigDecimal.valueOf(3), SCALE, java.math.RoundingMode.HALF_UP);
            numerator = numerator.add(typical.multiply(bar.volume()));
            volume = volume.add(bar.volume());
        }
        if (volume.signum() == 0) {
            statuses[index] = MetricStatus.DATA_MISSING;
            return;
        }
        values[index] = numerator.divide(volume, SCALE, java.math.RoundingMode.HALF_UP);
        statuses[index] = MetricStatus.OK;
    }

    private static void calculateRvol(List<Bar> bars, int index, BigDecimal[] values,
                                      MetricStatus[] statuses) {
        if (index < 20) {
            statuses[index] = MetricStatus.INSUFFICIENT_HISTORY;
            return;
        }
        var priorVolume = BigDecimal.ZERO;
        for (int offset = index - 20; offset < index; offset++) {
            priorVolume = priorVolume.add(bars.get(offset).volume());
        }
        if (priorVolume.signum() == 0) {
            statuses[index] = MetricStatus.DATA_MISSING;
            return;
        }
        var priorAverage = priorVolume.divide(BigDecimal.valueOf(20), SCALE,
                java.math.RoundingMode.HALF_UP);
        values[index] = bars.get(index).volume().divide(priorAverage, SCALE,
                java.math.RoundingMode.HALF_UP);
        statuses[index] = MetricStatus.OK;
    }

    private static void calculateReturn(List<Bar> bars, int index, int window, BigDecimal[] values,
                                        MetricStatus[] statuses, java.util.Set<LocalDate> splitDates) {
        if (index < window) {
            statuses[index] = MetricStatus.INSUFFICIENT_HISTORY;
            return;
        }
        var baseDate = bars.get(index - window).date();
        var currentDate = bars.get(index).date();
        if (crossesSplitConflict(splitDates, baseDate, currentDate)) {
            statuses[index] = MetricStatus.CORPORATE_ACTION_CONFLICT;
            return;
        }
        values[index] = ratio(bars.get(index).close(), bars.get(index - window).close())
                .subtract(BigDecimal.ONE);
        statuses[index] = MetricStatus.OK;
    }

    private static void calculateRelativeStrength(List<Bar> bars, int index, int window,
                                                  java.util.Map<LocalDate, BenchmarkBar> spyByDate,
                                                  BigDecimal[] values, MetricStatus[] statuses,
                                                  java.util.Set<LocalDate> splitDates) {
        if (index < window) {
            statuses[index] = MetricStatus.INSUFFICIENT_HISTORY;
            return;
        }
        var current = bars.get(index);
        var base = bars.get(index - window);
        if (crossesSplitConflict(splitDates, base.date(), current.date())) {
            statuses[index] = MetricStatus.CORPORATE_ACTION_CONFLICT;
            return;
        }
        var spyCurrent = spyByDate.get(current.date());
        var spyBase = spyByDate.get(base.date());
        if (spyCurrent == null || spyBase == null) {
            statuses[index] = MetricStatus.INSUFFICIENT_HISTORY;
            return;
        }
        if (spyCurrent.sourceConflict() || spyBase.sourceConflict()) {
            statuses[index] = MetricStatus.SOURCE_CONFLICT;
            return;
        }
        var stockGross = ratio(current.close(), base.close());
        var spyGross = ratio(spyCurrent.close(), spyBase.close());
        values[index] = ratio(stockGross, spyGross).subtract(BigDecimal.ONE);
        statuses[index] = MetricStatus.OK;
    }

    private static boolean crossesSplitConflict(java.util.Set<LocalDate> splitDates,
                                                LocalDate startExclusive, LocalDate endInclusive) {
        return splitDates.stream().anyMatch(date -> date.isAfter(startExclusive) && !date.isAfter(endInclusive));
    }

    private static MetricValue emaValue(BigDecimal[] values, int index, int period) {
        return values[index] == null
                ? MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY)
                : MetricValue.available(values[index]);
    }

    private static MetricValue value(BigDecimal number, MetricStatus status) {
        return number == null ? MetricValue.unavailable(status) : MetricValue.available(number);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, SCALE, java.math.RoundingMode.HALF_UP);
    }

    private List<Event> calculateEvents(Input input, List<Bar> bars, IndicatorSeries series) {
        var events = new java.util.ArrayList<Event>();
        var activeBreakouts = new java.util.ArrayList<ActiveBreakout>();
        var splitDates = java.util.Set.copyOf(input.splitConflictDates());
        var lookback = properties.breakoutLookback();
        for (int index = 0; index < bars.size(); index++) {
            var bar = bars.get(index);
            for (var iterator = activeBreakouts.iterator(); iterator.hasNext();) {
                var breakout = iterator.next();
                if (bar.close().compareTo(breakout.level()) < 0) {
                    events.add(new Event(EventType.FAILED_BREAKOUT, bar.date(), breakout.date(),
                            breakout.level(), bar.close(), null));
                    iterator.remove();
                }
            }

            if (index > 0) {
                addReclaimEvent(events, bars, series.ema21(), index, 21, EventType.EMA21_RECLAIM);
                addReclaimEvent(events, bars, series.ema50(), index, 50, EventType.EMA50_RECLAIM);
                var previousEma50 = series.ema50()[index - 1];
                var currentEma50 = series.ema50()[index];
                if (previousEma50 != null && currentEma50 != null
                        && bars.get(index - 1).close().compareTo(previousEma50) >= 0
                        && bar.close().compareTo(currentEma50) < 0) {
                    events.add(new Event(EventType.TREND_BREAKDOWN, bar.date(), null, null, bar.close(), null));
                }
            }

            if (index >= lookback) {
                var priorHigh = bars.subList(index - lookback, index).stream()
                        .map(Bar::high).max(BigDecimal::compareTo).orElseThrow();
                var threshold = priorHigh.multiply(BigDecimal.ONE.add(properties.breakoutBufferRatio()));
                if (bar.close().compareTo(threshold) > 0) {
                    events.add(new Event(EventType.BREAKOUT, bar.date(), bar.date(), priorHigh,
                            bar.close(), threshold));
                    activeBreakouts.add(new ActiveBreakout(bar.date(), priorHigh, bar.close()));
                    if (series.rvolStatus()[index] == MetricStatus.OK
                            && series.rvol()[index].compareTo(properties.highRvolThreshold()) >= 0) {
                        events.add(new Event(EventType.HIGH_RVOL_BREAKOUT, bar.date(), bar.date(), priorHigh,
                                bar.close(), threshold));
                    }
                }
            }
        }
        return List.copyOf(events);
    }

    private static void addReclaimEvent(List<Event> events, List<Bar> bars, BigDecimal[] ema,
                                        int index, int period, EventType type) {
        var priorEma = ema[index - 1];
        var currentEma = ema[index];
        if (priorEma == null || currentEma == null) return;
        if (bars.get(index - 1).close().compareTo(priorEma) <= 0
                && bars.get(index).close().compareTo(currentEma) > 0) {
            events.add(new Event(type, bars.get(index).date(), null, null, bars.get(index).close(), null));
        }
    }

    private List<Cohort> calculateCohorts(Input input, List<Bar> bars, List<Event> events) {
        var indexes = new java.util.HashMap<LocalDate, Integer>();
        for (int index = 0; index < bars.size(); index++) indexes.put(bars.get(index).date(), index);
        var cohorts = new java.util.ArrayList<Cohort>();
        var splitDates = java.util.Set.copyOf(input.splitConflictDates());
        var breakoutEvents = events.stream().filter(event -> event.type() == EventType.BREAKOUT).toList();
        for (var event : breakoutEvents) {
            var breakoutIndex = indexes.get(event.date());
            for (int horizon : properties.cohortHorizons()) {
                var endpointIndex = breakoutIndex + horizon;
                if (endpointIndex >= bars.size()) {
                    cohorts.add(new Cohort(event.date(), event.breakoutLevel(), event.close(), horizon,
                            false, null, null, null, null, MetricStatus.INSUFFICIENT_HISTORY));
                    continue;
                }
                var endpoint = bars.get(endpointIndex);
                if (crossesSplitConflict(splitDates, event.date(), endpoint.date())) {
                    cohorts.add(new Cohort(event.date(), event.breakoutLevel(), event.close(), horizon,
                            true, null, null, endpoint.date(), null, MetricStatus.CORPORATE_ACTION_CONFLICT));
                    continue;
                }
                var failed = false;
                for (int index = breakoutIndex + 1; index <= endpointIndex; index++) {
                    if (bars.get(index).close().compareTo(event.breakoutLevel()) < 0) {
                        failed = true;
                        break;
                    }
                }
                var success = endpoint.close().compareTo(event.breakoutLevel()) > 0 && !failed;
                cohorts.add(new Cohort(event.date(), event.breakoutLevel(), event.close(), horizon,
                        true, success, failed, endpoint.date(), ratio(endpoint.close(), event.close())
                        .subtract(BigDecimal.ONE), MetricStatus.OK));
            }
        }
        return List.copyOf(cohorts);
    }

    private Stage calculateStage(List<Bar> bars, IndicatorSeries series, Input input) {
        var index = bars.size() - 1;
        if (!input.configured() || input.sourceConflict() || index < 49) return null;
        var ema9 = series.ema9()[index];
        var ema21 = series.ema21()[index];
        var ema50 = series.ema50()[index];
        var priorEma21 = series.ema21()[index - 1];
        if (ema9 == null || ema21 == null || ema50 == null || priorEma21 == null) return null;
        var close = bars.get(index).close();
        if (close.compareTo(ema50) < 0 && ema21.compareTo(ema50) < 0) return Stage.BREAKDOWN;
        if (close.compareTo(ema21) < 0 && ema21.compareTo(ema50) >= 0) return Stage.DISTRIBUTION;
        var bullishStack = ema9.compareTo(ema21) > 0 && ema21.compareTo(ema50) > 0;
        if (bullishStack && ratio(close, ema21).subtract(BigDecimal.ONE)
                .compareTo(properties.extensionThreshold()) > 0) return Stage.EXTENDED;
        if (bullishStack && close.compareTo(ema21) > 0) return Stage.CONFIRMED_UPTREND;
        if (close.compareTo(ema21) > 0 && ema21.compareTo(priorEma21) >= 0) return Stage.EARLY_UPTREND;
        return Stage.BASE;
    }

    private OverlayStatus stageUnavailableStatus(Input input, List<Bar> bars) {
        if (!input.configured()) return OverlayStatus.NOT_CONFIGURED;
        if (input.sourceConflict()) return OverlayStatus.SOURCE_CONFLICT;
        if (bars.isEmpty()) return OverlayStatus.DATA_MISSING;
        if (bars.size() < 50) return OverlayStatus.INSUFFICIENT_HISTORY;
        return input.historyComplete() ? OverlayStatus.INSUFFICIENT_HISTORY : OverlayStatus.PARTIAL;
    }

    private static List<AnchoredVwap> calculateAnchoredVwaps(Input input, List<Bar> bars) {
        if (input.anchors().isEmpty()) return List.of();
        var indexes = new java.util.HashMap<LocalDate, Integer>();
        for (int index = 0; index < bars.size(); index++) indexes.put(bars.get(index).date(), index);
        var result = new java.util.ArrayList<AnchoredVwap>();
        var current = bars.getLast();
        var splitDates = java.util.Set.copyOf(input.splitConflictDates());
        for (var anchor : input.anchors()) {
            var anchorIndex = indexes.get(anchor.date());
            if (anchorIndex == null) {
                result.add(new AnchoredVwap(anchor.id(), anchor.date(), anchor.anchorType(), current.date(),
                        MetricValue.unavailable(MetricStatus.DATA_MISSING),
                        MetricValue.unavailable(MetricStatus.DATA_MISSING)));
                continue;
            }
            if (crossesSplitConflict(splitDates, anchor.date(), current.date())) {
                result.add(new AnchoredVwap(anchor.id(), anchor.date(), anchor.anchorType(), current.date(),
                        MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT),
                        MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT)));
                continue;
            }
            var weighted = BigDecimal.ZERO;
            var volume = BigDecimal.ZERO;
            for (int index = anchorIndex; index < bars.size(); index++) {
                var bar = bars.get(index);
                var typical = bar.high().add(bar.low()).add(bar.close())
                        .divide(BigDecimal.valueOf(3), SCALE, java.math.RoundingMode.HALF_UP);
                weighted = weighted.add(typical.multiply(bar.volume()));
                volume = volume.add(bar.volume());
            }
            if (volume.signum() == 0) {
                result.add(new AnchoredVwap(anchor.id(), anchor.date(), anchor.anchorType(), current.date(),
                        MetricValue.unavailable(MetricStatus.DATA_MISSING),
                        MetricValue.unavailable(MetricStatus.DATA_MISSING)));
                continue;
            }
            var anchoredValue = weighted.divide(volume, SCALE, java.math.RoundingMode.HALF_UP);
            var distance = ratio(current.close(), anchoredValue).subtract(BigDecimal.ONE);
            result.add(new AnchoredVwap(anchor.id(), anchor.date(), anchor.anchorType(), current.date(),
                    MetricValue.available(anchoredValue), MetricValue.available(distance)));
        }
        return List.copyOf(result);
    }

    private Performance calculatePerformance(Input input, List<Bar> bars) {
        var entry = input.performanceEntry();
        if (entry == null) return unavailablePerformance(null, OverlayStatus.NOT_CONFIGURED);
        if (!input.configured()) return unavailablePerformance(entry, OverlayStatus.NOT_CONFIGURED);
        if (input.sourceConflict()) return unavailablePerformance(entry, OverlayStatus.SOURCE_CONFLICT);
        if (bars.isEmpty()) return unavailablePerformance(entry, OverlayStatus.DATA_MISSING);

        var riskPerShare = entry.price().subtract(entry.stopPrice());
        var initialRiskAmount = entry.quantity() == null
                ? MetricValue.unavailable(MetricStatus.DATA_MISSING)
                : MetricValue.available(riskPerShare.multiply(entry.quantity()));
        var initialR = MetricValue.available(BigDecimal.ONE);
        var splitDates = java.util.Set.copyOf(input.splitConflictDates());
        var currentR = calculateCurrentR(input, entry, riskPerShare, splitDates);
        var hasEntryBar = bars.stream().anyMatch(bar -> bar.date().equals(entry.date()));
        var fullExitDate = fullExitDate(input, entry);
        var holdingBars = hasEntryBar
                ? bars.stream().filter(bar -> bar.date().isAfter(entry.date())
                        && (fullExitDate == null || bar.date().isBefore(fullExitDate))).toList()
                : List.<Bar>of();
        var mae = MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY);
        var mfe = MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY);
        if (!holdingBars.isEmpty()) {
            if (crossesSplitConflict(splitDates, entry.date(), holdingBars.getLast().date())) {
                mae = MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT);
                mfe = MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT);
            } else {
                var lowest = holdingBars.stream().map(Bar::low).min(BigDecimal::compareTo).orElseThrow();
                var highest = holdingBars.stream().map(Bar::high).max(BigDecimal::compareTo).orElseThrow();
                mae = MetricValue.available(ratio(entry.price().subtract(lowest).max(BigDecimal.ZERO),
                        riskPerShare));
                mfe = MetricValue.available(ratio(highest.subtract(entry.price()).max(BigDecimal.ZERO),
                        riskPerShare));
            }
        }
        var realizedR = calculateRealizedR(input, entry, riskPerShare, splitDates);
        var forward = calculateForwardPerformance(input, bars, entry, splitDates);
        var perfStatus = currentR.status() == MetricStatus.STALE ? OverlayStatus.STALE
                : (currentR.status() == MetricStatus.OK && !holdingBars.isEmpty()
                ? OverlayStatus.OK : OverlayStatus.PARTIAL);
        var excursionWindow = fullExitDate == null ? "POST_ENTRY_COMPLETED_DAILY_BARS"
                : "POST_ENTRY_PRE_EXIT_COMPLETED_DAILY_BARS";
        return new Performance(entry.key(), perfStatus, initialR, MetricValue.available(riskPerShare),
                initialRiskAmount, currentR, realizedR, mae, mfe, excursionWindow, forward);
    }

    private static LocalDate fullExitDate(Input input, PerformanceEntry entry) {
        if (entry.quantity() == null) return null;
        var eligibleExits = entry.exits().stream().filter(exit -> !exit.date().isAfter(input.asOf())
                && !exit.date().isBefore(entry.date())).toList();
        var exitedQuantity = eligibleExits.stream().map(Exit::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return exitedQuantity.compareTo(entry.quantity()) == 0
                ? eligibleExits.stream().map(Exit::date).max(LocalDate::compareTo).orElse(null) : null;
    }

    private MetricValue calculateCurrentR(Input input, PerformanceEntry entry, BigDecimal riskPerShare,
                                          java.util.Set<LocalDate> splitDates) {
        var mark = input.currentMark();
        if (mark == null) return MetricValue.unavailable(MetricStatus.DATA_MISSING);
        var age = java.time.Duration.between(mark.asOf(), input.evaluatedAt());
        if (age.isNegative() || age.compareTo(properties.markFreshness()) > 0
                || mark.asOf().atZone(java.time.ZoneId.of("America/New_York")).toLocalDate()
                .isBefore(entry.date())) {
            return MetricValue.unavailable(MetricStatus.STALE);
        }
        var markDate = mark.asOf().atZone(java.time.ZoneId.of("America/New_York")).toLocalDate();
        if (crossesSplitConflict(splitDates, entry.date(), markDate)) {
            return MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT);
        }
        return MetricValue.available(ratio(mark.price().subtract(entry.price()), riskPerShare));
    }

    private static MetricValue calculateRealizedR(Input input, PerformanceEntry entry, BigDecimal riskPerShare,
                                                  java.util.Set<LocalDate> splitDates) {
        var exits = entry.exits().stream().filter(exit -> !exit.date().isAfter(input.asOf())
                && !exit.date().isBefore(entry.date())).toList();
        if (exits.isEmpty()) return MetricValue.unavailable(MetricStatus.DATA_MISSING);
        if (exits.stream().anyMatch(exit -> crossesSplitConflict(splitDates, entry.date(), exit.date()))) {
            return MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT);
        }
        var realizedPnl = BigDecimal.ZERO;
        var realizedQuantity = BigDecimal.ZERO;
        for (var exit : exits) {
            realizedPnl = realizedPnl.add(exit.price().subtract(entry.price()).multiply(exit.quantity()));
            realizedQuantity = realizedQuantity.add(exit.quantity());
        }
        var riskAmount = riskPerShare.multiply(realizedQuantity);
        return MetricValue.available(ratio(realizedPnl, riskAmount));
    }

    private List<ForwardPerformance> calculateForwardPerformance(Input input, List<Bar> bars,
                                                                 PerformanceEntry entry,
                                                                 java.util.Set<LocalDate> splitDates) {
        var afterEntry = bars.stream().filter(bar -> bar.date().isAfter(entry.date())).toList();
        var hasEntryBar = bars.stream().anyMatch(bar -> bar.date().equals(entry.date()));
        var results = new java.util.ArrayList<ForwardPerformance>();
        for (int horizon : properties.performanceHorizons()) {
            if (!hasEntryBar || afterEntry.size() < horizon) {
                results.add(new ForwardPerformance(horizon,
                        MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY), null));
                continue;
            }
            var endpoint = afterEntry.get(horizon - 1);
            if (crossesSplitConflict(splitDates, entry.date(), endpoint.date())) {
                results.add(new ForwardPerformance(horizon,
                        MetricValue.unavailable(MetricStatus.CORPORATE_ACTION_CONFLICT), endpoint.date()));
                continue;
            }
            results.add(new ForwardPerformance(horizon,
                    MetricValue.available(ratio(endpoint.close(), entry.price()).subtract(BigDecimal.ONE)),
                    endpoint.date()));
        }
        return List.copyOf(results);
    }

    private static Performance unavailablePerformance(PerformanceEntry entry, OverlayStatus status) {
        var metricStatus = metricStatus(status);
        var unavailable = MetricValue.unavailable(metricStatus);
        return new Performance(entry == null ? null : entry.key(), status, unavailable, unavailable,
                unavailable, unavailable, unavailable, unavailable, unavailable,
                "POST_ENTRY_COMPLETED_DAILY_BARS", List.of());
    }

    private static MetricStatus metricStatus(OverlayStatus status) {
        return switch (status) {
            case OK -> MetricStatus.OK;
            case PARTIAL -> MetricStatus.PARTIAL;
            case DATA_MISSING -> MetricStatus.DATA_MISSING;
            case INSUFFICIENT_HISTORY -> MetricStatus.INSUFFICIENT_HISTORY;
            case SOURCE_CONFLICT -> MetricStatus.SOURCE_CONFLICT;
            case STALE -> MetricStatus.STALE;
            case NOT_CONFIGURED -> MetricStatus.NOT_CONFIGURED;
        };
    }

    private static List<EmaSeed> emaSeeds(List<Bar> bars) {
        var seeds = new java.util.ArrayList<EmaSeed>();
        for (int period : List.of(9, 21, 50)) {
            if (bars.size() >= period) seeds.add(new EmaSeed(period, bars.getFirst().date(), period));
        }
        return List.copyOf(seeds);
    }

    private record IndicatorSeries(List<IndicatorBar> indicators, BigDecimal[] ema9, BigDecimal[] ema21,
                                   BigDecimal[] ema50, BigDecimal[] rvol, MetricStatus[] rvolStatus,
                                   BigDecimal[] return20, BigDecimal[] return60,
                                   BigDecimal[] relativeStrength20, BigDecimal[] relativeStrength60) {
    }

    private record ActiveBreakout(LocalDate date, BigDecimal level, BigDecimal close) {
    }

    public enum OverlayStatus {
        OK, PARTIAL, DATA_MISSING, INSUFFICIENT_HISTORY, SOURCE_CONFLICT, STALE, NOT_CONFIGURED
    }

    public enum MetricStatus {
        OK, PARTIAL, DATA_MISSING, INSUFFICIENT_HISTORY, SOURCE_CONFLICT, STALE,
        NOT_CONFIGURED, CORPORATE_ACTION_CONFLICT
    }

    public enum PriceAdjustmentStatus { ADJUSTED, UNADJUSTED, UNKNOWN }

    public enum AnchorType { EARNINGS_GAP, BREAKOUT_DAY, CATALYST_DAY, MAJOR_LOW, POSITION_ENTRY }

    public enum Stage { BASE, EARLY_UPTREND, CONFIRMED_UPTREND, EXTENDED, DISTRIBUTION, BREAKDOWN }

    public enum EventType {
        EMA21_RECLAIM, EMA50_RECLAIM, BREAKOUT, FAILED_BREAKOUT, HIGH_RVOL_BREAKOUT, TREND_BREAKDOWN
    }

    public enum VwapMethod { DAILY_ROLLING_20 }

    public record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                      BigDecimal close, BigDecimal volume) {
        public Bar {
            java.util.Objects.requireNonNull(date, "date");
            positive(open, "open");
            positive(high, "high");
            positive(low, "low");
            positive(close, "close");
            nonNegative(volume, "volume");
            if (low.compareTo(high) > 0 || low.compareTo(open) > 0 || low.compareTo(close) > 0
                    || high.compareTo(open) < 0 || high.compareTo(close) < 0) {
                throw new IllegalArgumentException("OHLC values are inconsistent");
            }
        }
    }

    public record BenchmarkBar(LocalDate date, BigDecimal close, boolean sourceConflict) {
        public BenchmarkBar(LocalDate date, BigDecimal close) {
            this(date, close, false);
        }

        public BenchmarkBar {
            java.util.Objects.requireNonNull(date, "date");
            positive(close, "close");
        }
    }

    public record Mark(BigDecimal price, Instant asOf) {
        public Mark {
            positive(price, "price");
            java.util.Objects.requireNonNull(asOf, "asOf");
        }
    }

    public record AvwapAnchor(String id, LocalDate date, AnchorType anchorType) {
        public AvwapAnchor {
            requiredText(id, "id");
            java.util.Objects.requireNonNull(date, "date");
            java.util.Objects.requireNonNull(anchorType, "anchorType");
        }
    }

    public record Exit(LocalDate date, BigDecimal price, BigDecimal quantity) {
        public Exit {
            java.util.Objects.requireNonNull(date, "date");
            positive(price, "price");
            positive(quantity, "quantity");
        }
    }

    public record PerformanceEntry(String key, LocalDate date, BigDecimal price, BigDecimal stopPrice,
                                   BigDecimal quantity, List<Exit> exits) {
        public PerformanceEntry {
            requiredText(key, "key");
            java.util.Objects.requireNonNull(date, "date");
            positive(price, "price");
            positive(stopPrice, "stopPrice");
            if (price.compareTo(stopPrice) <= 0) {
                throw new IllegalArgumentException("stopPrice must be below entry price");
            }
            if (quantity != null) positive(quantity, "quantity");
            exits = exits == null ? List.of() : List.copyOf(exits);
            if (exits.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("exits must not contain null");
            }
            var exitedQuantity = BigDecimal.ZERO;
            for (var exit : exits) {
                if (exit.date().isBefore(date)) {
                    throw new IllegalArgumentException("exits must be on or after entry date");
                }
                exitedQuantity = exitedQuantity.add(exit.quantity());
            }
            if (quantity != null && exitedQuantity.compareTo(quantity) > 0) {
                throw new IllegalArgumentException("exit quantity must not exceed entry quantity");
            }
        }
    }

    public record Input(String symbol, LocalDate asOf, Instant evaluatedAt, boolean configured,
                        List<Bar> bars, List<BenchmarkBar> spyBars, String source, boolean sourceConflict,
                        boolean historyComplete, PriceAdjustmentStatus priceAdjustmentStatus,
                        List<LocalDate> splitConflictDates, List<AvwapAnchor> anchors, Mark currentMark,
                        PerformanceEntry performanceEntry) {
        public Input {
            requiredText(symbol, "symbol");
            symbol = symbol.trim().toUpperCase(java.util.Locale.ROOT);
            java.util.Objects.requireNonNull(asOf, "asOf");
            java.util.Objects.requireNonNull(evaluatedAt, "evaluatedAt");
            var evaluatedNewYorkDate = evaluatedAt.atZone(java.time.ZoneId.of("America/New_York")).toLocalDate();
            if (asOf.isAfter(evaluatedNewYorkDate)) {
                throw new IllegalArgumentException("asOf must not be after the evaluated New York date");
            }
            bars = bars == null ? List.of() : List.copyOf(bars);
            spyBars = spyBars == null ? List.of() : List.copyOf(spyBars);
            source = source == null || source.isBlank() ? "UNKNOWN" : source.trim();
            priceAdjustmentStatus = priceAdjustmentStatus == null ? PriceAdjustmentStatus.UNKNOWN
                    : priceAdjustmentStatus;
            splitConflictDates = splitConflictDates == null ? List.of() : List.copyOf(splitConflictDates);
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
            if (bars.stream().anyMatch(java.util.Objects::isNull)
                    || spyBars.stream().anyMatch(java.util.Objects::isNull)
                    || splitConflictDates.stream().anyMatch(java.util.Objects::isNull)
                    || anchors.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("input lists must not contain null");
            }
            requireStrictDateOrder(bars.stream().map(Bar::date).toList(), "bars");
            requireStrictDateOrder(spyBars.stream().map(BenchmarkBar::date).toList(), "spyBars");
            if (anchors.stream().map(AvwapAnchor::id).distinct().count() != anchors.size()) {
                throw new IllegalArgumentException("anchor IDs must be unique");
            }
        }
    }

    public record MetricValue(BigDecimal value, MetricStatus status) {
        public MetricValue {
            java.util.Objects.requireNonNull(status, "status");
            if (status == MetricStatus.OK && value == null) {
                throw new IllegalArgumentException("OK metric requires a value");
            }
        }

        public static MetricValue available(BigDecimal value) {
            return new MetricValue(java.util.Objects.requireNonNull(value, "value"), MetricStatus.OK);
        }

        public static MetricValue unavailable(MetricStatus status) {
            return new MetricValue(null, status);
        }
    }

    public record IndicatorBar(LocalDate date, MetricValue ema9, MetricValue ema21, MetricValue ema50,
                               MetricValue dailyRolling20Vwap, MetricValue relativeVolume,
                               MetricValue return20, MetricValue return60, MetricValue relativeStrength20,
                               MetricValue relativeStrength60) {
        /** Daily OHLCV typical-price/volume proxy; this is not intraday trade-execution VWAP. */
        public MetricValue getDailyVwap20Proxy() {
            return dailyRolling20Vwap;
        }
    }

    public record Event(EventType type, LocalDate date, LocalDate breakoutDate, BigDecimal breakoutLevel,
                        BigDecimal close, BigDecimal thresholdLevel) {
    }

    public record Cohort(LocalDate breakoutDate, BigDecimal breakoutLevel, BigDecimal breakoutClose,
                         int horizon, boolean matured, Boolean success, Boolean failedByHorizon,
                         LocalDate endpointDate, BigDecimal returnPct, MetricStatus status) {
    }

    public record AnchoredVwap(String id, LocalDate date, AnchorType anchorType, LocalDate asOf,
                               MetricValue value, MetricValue distancePct) {
        /** Daily OHLCV AVWAP from the externally specified anchor, inclusive; not an intraday execution VWAP. */
        public MetricValue getDailyAvwap() {
            return value;
        }

        public MetricValue getAnchoredVwap() {
            return value;
        }
    }

    public record EmaSeed(int period, LocalDate seedDate, int observations) {
    }

    public record ForwardPerformance(int horizon, MetricValue returnPct, LocalDate endpointDate) {
    }

    public record Performance(String key, OverlayStatus status, MetricValue initialR,
                              MetricValue initialRiskPerShare, MetricValue initialRiskAmount,
                              MetricValue currentR, MetricValue realizedR, MetricValue mae,
                              MetricValue mfe, String excursionWindow,
                              List<ForwardPerformance> forwardReturns) {
        public Performance {
            forwardReturns = forwardReturns == null ? List.of() : List.copyOf(forwardReturns);
        }
    }

    public record Result(String symbol, LocalDate asOf, String source, OverlayStatus status,
                         PriceAdjustmentStatus priceAdjustmentStatus, Stage stage, OverlayStatus stageStatus,
                         VwapMethod vwapMethod, List<EmaSeed> emaSeeds, List<IndicatorBar> indicators,
                         List<Event> events,
                         List<Cohort> cohorts, List<AnchoredVwap> anchoredVwaps, Performance performance) {
        public Result {
            emaSeeds = List.copyOf(emaSeeds);
            indicators = List.copyOf(indicators);
            events = List.copyOf(events);
            cohorts = List.copyOf(cohorts);
            anchoredVwaps = List.copyOf(anchoredVwaps);
        }

        public boolean sourcePricesAdjusted() {
            return priceAdjustmentStatus == PriceAdjustmentStatus.ADJUSTED;
        }

        public List<AnchoredVwap> getDailyAvwaps() {
            return anchoredVwaps;
        }
    }

    private static void positive(BigDecimal value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void nonNegative(BigDecimal value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireStrictDateOrder(List<LocalDate> dates, String name) {
        for (int index = 1; index < dates.size(); index++) {
            if (!dates.get(index).isAfter(dates.get(index - 1))) {
                throw new IllegalArgumentException(name + " dates must be unique and strictly increasing");
            }
        }
    }
}
