package com.jmj.trade.investment.tactical;

import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.AnchorType;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Bar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.BenchmarkBar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.EventType;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Input;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Mark;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.MetricStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.OverlayStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.PerformanceEntry;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.PriceAdjustmentStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Stage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TacticalOverlayCalculatorTest {

    private static final LocalDate FIRST_DATE = LocalDate.parse("2026-01-01");

    @Test
    void calculatesEmaSeedsDailyRollingVwapRvolAndTradingBarReturns() {
        var bars = bars(70, index -> bar(index, bd("100").add(bd(String.valueOf(index)))));
        var result = calculate(input(bars, date(69), List.of(), List.of(), null));

        assertThat(result.indicators()).hasSize(70);
        assertThat(result.vwapMethod().name()).isEqualTo("DAILY_ROLLING_20");
        assertThat(result.indicators().get(7).ema9().status()).isEqualTo(MetricStatus.INSUFFICIENT_HISTORY);
        assertThat(result.indicators().get(8).ema9().value()).isEqualByComparingTo("104");
        assertThat(result.indicators().get(20).ema21().value()).isEqualByComparingTo("110");
        assertThat(result.indicators().get(49).ema50().value()).isEqualByComparingTo("124.5");

        var expectedVwap = averageWeightedClose(0, 19);
        assertThat(result.indicators().get(19).dailyRolling20Vwap().value())
                .isEqualByComparingTo(expectedVwap);
        assertThat(result.indicators().get(20).relativeVolume().value())
                .isEqualByComparingTo(bd("120").divide(bd("109.5"), 10, java.math.RoundingMode.HALF_UP));
        assertThat(result.indicators().get(20).return20().value())
                .isEqualByComparingTo(bd("120").divide(bd("100"), 10,
                        java.math.RoundingMode.HALF_UP).subtract(BigDecimal.ONE));
        assertThat(result.indicators().get(60).return60().value())
                .isEqualByComparingTo(bd("160").divide(bd("100"), 10,
                        java.math.RoundingMode.HALF_UP).subtract(BigDecimal.ONE));
        assertThat(result.emaSeeds()).extracting(TacticalOverlayCalculator.EmaSeed::period)
                .containsExactly(9, 21, 50);
        assertThat(result.emaSeeds().getLast().seedDate()).isEqualTo(date(0));
        assertThat(result.emaSeeds().getLast().observations()).isEqualTo(50);
    }

    @Test
    void relativeStrengthUsesExactAlignedSpyEndpointsAndGrossReturnRatio() {
        var stocks = bars(65, index -> bar(index, bd("100").add(bd(String.valueOf(index)))));
        var spy = new ArrayList<BenchmarkBar>();
        for (int index = 1; index < 65; index++) {
            spy.add(new BenchmarkBar(date(index), bd("200").add(bd(String.valueOf(index)))));
        }
        var result = calculate(input(stocks, date(64), spy, List.of(), null));

        assertThat(result.indicators().get(20).relativeStrength20().status())
                .isEqualTo(MetricStatus.INSUFFICIENT_HISTORY);
        var stockGross = bd("121").divide(bd("101"), 10, java.math.RoundingMode.HALF_UP);
        var spyGross = bd("221").divide(bd("201"), 10, java.math.RoundingMode.HALF_UP);
        assertThat(result.indicators().get(21).relativeStrength20().value())
                .isEqualByComparingTo(stockGross.divide(spyGross, 10,
                        java.math.RoundingMode.HALF_UP).subtract(BigDecimal.ONE));
    }

    @Test
    void suppressesRelativeStrengthWhenRequiredSpyEndpointHasSourceConflict() {
        var stocks = bars(65, index -> bar(index, bd("100").add(bd(String.valueOf(index)))));
        var spy = new ArrayList<BenchmarkBar>();
        for (int index = 0; index < 65; index++) {
            spy.add(new BenchmarkBar(date(index), bd("200").add(bd(String.valueOf(index))), index == 64));
        }

        var result = calculate(input(stocks, date(64), spy, List.of(), null));
        var asOf = result.indicators().get(64);

        assertThat(asOf.return20().status()).isEqualTo(MetricStatus.OK);
        assertThat(asOf.return20().value()).isNotNull();
        assertThat(asOf.relativeStrength20().status()).isEqualTo(MetricStatus.SOURCE_CONFLICT);
        assertThat(asOf.relativeStrength20().value()).isNull();
        assertThat(asOf.relativeStrength60().status()).isEqualTo(MetricStatus.SOURCE_CONFLICT);
        assertThat(asOf.relativeStrength60().value()).isNull();
    }

    @Test
    void suppressesReturnsThatCrossKnownSplitConflictButExposesAdjustmentStatus() {
        var bars = bars(65, index -> bar(index, bd("100").add(bd(String.valueOf(index)))));
        var input = input(bars, date(64), List.of(), List.of(), null);
        var unadjusted = new Input(input.symbol(), input.asOf(), input.evaluatedAt(), input.configured(),
                input.bars(), input.spyBars(), input.source(), input.sourceConflict(), input.historyComplete(),
                PriceAdjustmentStatus.UNADJUSTED, List.of(date(50)), input.anchors(), input.currentMark(),
                input.performanceEntry());

        var result = calculate(unadjusted);

        assertThat(result.sourcePricesAdjusted()).isFalse();
        assertThat(result.priceAdjustmentStatus()).isEqualTo(PriceAdjustmentStatus.UNADJUSTED);
        assertThat(result.indicators().get(64).return20().status())
                .isEqualTo(MetricStatus.CORPORATE_ACTION_CONFLICT);
        assertThat(result.indicators().get(64).return60().status())
                .isEqualTo(MetricStatus.CORPORATE_ACTION_CONFLICT);
    }

    @Test
    void anchoredVwapStartsAtExplicitAnchorInclusiveAndPreservesItsType() {
        var bars = bars(5, index -> bar(index, bd(String.valueOf(10 + index))));
        var anchor = new TacticalOverlayCalculator.AvwapAnchor("entry-1", date(2), AnchorType.POSITION_ENTRY);
        var result = calculate(input(bars, date(4), List.of(), List.of(anchor), null));

        assertThat(result.anchoredVwaps()).hasSize(1);
        assertThat(result.anchoredVwaps().getFirst().anchorType()).isEqualTo(AnchorType.POSITION_ENTRY);
        assertThat(result.anchoredVwaps().getFirst().value().value())
                .isEqualByComparingTo(bd("4019").divide(bd("309"), 10,
                        java.math.RoundingMode.HALF_UP));
        assertThat(result.anchoredVwaps().getFirst().distancePct().status()).isEqualTo(MetricStatus.OK);
    }

    @Test
    void anchoredVwapIsUnavailableAcrossKnownSplitConflict() {
        var base = input(bars(5, index -> bar(index, bd(String.valueOf(10 + index)))), date(4),
                List.of(), List.of(new TacticalOverlayCalculator.AvwapAnchor(
                        "catalyst-1", date(1), AnchorType.CATALYST_DAY)), null);
        var input = new Input(base.symbol(), base.asOf(), base.evaluatedAt(), base.configured(), base.bars(),
                base.spyBars(), base.source(), base.sourceConflict(), base.historyComplete(),
                PriceAdjustmentStatus.UNADJUSTED, List.of(date(3)), base.anchors(), base.currentMark(),
                base.performanceEntry());

        var avwap = calculate(input).anchoredVwaps().getFirst();

        assertThat(avwap.value().status()).isEqualTo(MetricStatus.CORPORATE_ACTION_CONFLICT);
        assertThat(avwap.value().value()).isNull();
        assertThat(avwap.distancePct().status()).isEqualTo(MetricStatus.CORPORATE_ACTION_CONFLICT);
    }

    @Test
    void emitsExactStageValuesAndLeavesStageNullUntilEma50Warmup() {
        assertThat(stage(flatCloses(70, "100"))).isEqualTo(Stage.BASE);
        assertThat(stage(closes(70, index -> bd("100").add(bd(String.valueOf(index)).multiply(bd("0.5"))))))
                .isEqualTo(Stage.CONFIRMED_UPTREND);

        var extended = closes(70, index -> bd("100").add(bd(String.valueOf(index)).multiply(bd("0.5"))));
        extended.set(69, extended.get(69).add(bd("25")));
        assertThat(stage(extended)).isEqualTo(Stage.EXTENDED);

        var early = closes(51, index -> index < 50
                ? bd("150").subtract(bd(String.valueOf(index)).multiply(bd("0.5")))
                : bd("150"));
        assertThat(stage(early)).isEqualTo(Stage.EARLY_UPTREND);

        var distribution = closes(70, index -> bd("100").add(bd(String.valueOf(index)).multiply(bd("0.5"))));
        distribution.set(69, distribution.get(69).subtract(bd("12")));
        assertThat(stage(distribution)).isEqualTo(Stage.DISTRIBUTION);

        var breakdown = closes(85, index -> index < 55
                ? bd("100").add(bd(String.valueOf(index)).multiply(bd("0.5")))
                : bd("127").subtract(bd(String.valueOf(index - 55)).multiply(bd("2"))));
        assertThat(stage(breakdown)).isEqualTo(Stage.BREAKDOWN);

        var shortHistory = calculate(input(bars(49, index -> bar(index, bd("100"))), date(48),
                List.of(), List.of(), null));
        assertThat(shortHistory.stage()).isNull();
        assertThat(shortHistory.stageStatus()).isEqualTo(OverlayStatus.INSUFFICIENT_HISTORY);
    }

    @Test
    void emitsReclaimBreakoutHighRvolFailureAndTrendBreakdownEvents() {
        var bars = bars(70, index -> bar(index, bd("100")));
        bars.set(52, new Bar(date(52), bd("110"), bd("111"), bd("109"), bd("110"), bd("300")));
        bars.set(53, bar(53, bd("100")));

        var result = calculate(input(bars, date(69), List.of(), List.of(), null));
        var types = result.events().stream().map(TacticalOverlayCalculator.Event::type).toList();

        assertThat(types).contains(EventType.EMA21_RECLAIM, EventType.EMA50_RECLAIM, EventType.BREAKOUT,
                EventType.HIGH_RVOL_BREAKOUT, EventType.FAILED_BREAKOUT, EventType.TREND_BREAKDOWN);
    }

    @Test
    void cohortsMatureOnlyAtExactTradingBarHorizonAndFailurePersistsThroughHorizon() {
        var bars = bars(36, index -> bar(index, bd("100")));
        bars.set(22, new Bar(date(22), bd("110"), bd("111"), bd("109"), bd("110"), bd("300")));
        bars.set(23, bar(23, bd("100")));
        bars.set(28, bar(28, bd("105")));

        var immature = calculate(input(bars, date(24), List.of(), List.of(), null));
        assertThat(immature.cohorts()).hasSize(2);
        assertThat(immature.cohorts()).allSatisfy(cohort -> {
            assertThat(cohort.matured()).isFalse();
            assertThat(cohort.success()).isNull();
            assertThat(cohort.failedByHorizon()).isNull();
            assertThat(cohort.returnPct()).isNull();
        });

        var matured = calculate(input(bars, date(35), List.of(), List.of(), null));
        assertThat(matured.cohorts()).hasSize(2);
        assertThat(matured.cohorts()).allSatisfy(cohort -> {
            assertThat(cohort.matured()).isTrue();
            assertThat(cohort.success()).isFalse();
            assertThat(cohort.failedByHorizon()).isTrue();
            assertThat(cohort.returnPct()).isNotNull();
        });
    }

    @Test
    void successfulCohortRequiresNoBelowLevelCloseAndHorizonCloseAboveLevel() {
        var bars = bars(36, index -> bar(index, bd("100")));
        bars.set(22, new Bar(date(22), bd("110"), bd("111"), bd("109"), bd("110"), bd("300")));
        for (int index = 23; index <= 28; index++) bars.set(index, bar(index, bd("102")));

        var result = calculate(input(bars, date(29), List.of(), List.of(), null));

        assertThat(result.cohorts()).hasSize(2);
        assertThat(result.cohorts()).allSatisfy(cohort -> {
            assertThat(cohort.matured()).isTrue();
            assertThat(cohort.success()).isTrue();
            assertThat(cohort.failedByHorizon()).isFalse();
            assertThat(cohort.status()).isEqualTo(MetricStatus.OK);
        });
    }

    @Test
    void performanceUsesExplicitRiskAndFreshMarkExcludesEntryDayExcursions() {
        var bars = bars(16, index -> bar(index, bd("100")));
        bars.set(10, new Bar(date(10), bd("110"), bd("200"), bd("1"), bd("110"), bd("100")));
        bars.set(11, new Bar(date(11), bd("108"), bd("115"), bd("85"), bd("108"), bd("100")));
        bars.set(12, new Bar(date(12), bd("120"), bd("125"), bd("95"), bd("120"), bd("100")));
        bars.set(13, bar(13, bd("120")));
        bars.set(14, bar(14, bd("130")));
        bars.set(15, new Bar(date(15), bd("110"), bd("1000"), bd("109"), bd("110"), bd("100")));
        var exits = List.of(new TacticalOverlayCalculator.Exit(date(14), bd("130"), bd("1")),
                new TacticalOverlayCalculator.Exit(date(13), bd("120"), bd("2")));
        var entry = new PerformanceEntry("decision-7", date(10), bd("110"), bd("100"), bd("3"), exits);
        var mark = new Mark(bd("108"), evaluation(date(15)).minus(Duration.ofMinutes(5)));

        var result = calculate(input(bars, date(15), List.of(), List.of(), entry, mark));
        var performance = result.performance();

        assertThat(performance.key()).isEqualTo("decision-7");
        assertThat(performance.initialRiskPerShare().value()).isEqualByComparingTo("10");
        assertThat(performance.initialRiskAmount().value()).isEqualByComparingTo("30");
        assertThat(performance.initialR().value()).isEqualByComparingTo("1");
        assertThat(performance.currentR().value()).isEqualByComparingTo("-0.2");
        assertThat(performance.mae().value()).isEqualByComparingTo("2.5");
        assertThat(performance.mfe().value()).isEqualByComparingTo("1.5");
        assertThat(performance.excursionWindow()).isEqualTo("POST_ENTRY_PRE_EXIT_COMPLETED_DAILY_BARS");
        assertThat(performance.realizedR().value()).isEqualByComparingTo("1.3333333333");
        assertThat(performance.forwardReturns()).filteredOn(value -> value.horizon() == 5)
                .singleElement().satisfies(value -> assertThat(value.returnPct().value())
                        .isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(performance.forwardReturns()).filteredOn(value -> value.horizon() == 20)
                .singleElement().satisfies(value -> assertThat(value.returnPct().status())
                        .isEqualTo(MetricStatus.INSUFFICIENT_HISTORY));
    }

    @Test
    void staleMarksAndMissingQuantityOrExitsRemainExplicit() {
        var bars = bars(16, index -> bar(index, bd("100")));
        var entry = new PerformanceEntry("position-4", date(10), bd("100"), bd("90"), null, List.of());
        var staleMark = new Mark(bd("110"), evaluation(date(15)).minus(Duration.ofMinutes(16)));

        var performance = calculate(input(bars, date(15), List.of(), List.of(), entry, staleMark)).performance();

        assertThat(performance.initialRiskAmount().status()).isEqualTo(MetricStatus.DATA_MISSING);
        assertThat(performance.currentR().status()).isEqualTo(MetricStatus.STALE);
        assertThat(performance.currentR().value()).isNull();
        assertThat(performance.realizedR().status()).isEqualTo(MetricStatus.DATA_MISSING);
        assertThat(performance.realizedR().value()).isNull();
    }

    @Test
    void partialExitKeepsRemainingPositionExcursionsOpenThroughAsOf() {
        var bars = bars(16, index -> bar(index, bd("100")));
        bars.set(15, new Bar(date(15), bd("100"), bd("130"), bd("99"), bd("100"), bd("100")));
        var entry = new PerformanceEntry("position-partial", date(10), bd("100"), bd("90"), bd("3"),
                List.of(new TacticalOverlayCalculator.Exit(date(12), bd("110"), bd("1"))));

        var performance = calculate(input(bars, date(15), List.of(), List.of(), entry)).performance();

        assertThat(performance.mfe().value()).isEqualByComparingTo("3");
        assertThat(performance.excursionWindow()).isEqualTo("POST_ENTRY_COMPLETED_DAILY_BARS");
    }

    @Test
    void missingEntryDateBarCannotProduceForwardReturnsOrExcursions() {
        var allBars = bars(16, index -> bar(index, bd("100")));
        var entry = new PerformanceEntry("position-5", date(9), bd("100"), bd("90"), bd("2"), List.of());
        var mark = new Mark(bd("110"), evaluation(date(15)).minus(Duration.ofMinutes(5)));

        var result = calculate(input(allBars.subList(10, 16), date(15), List.of(), List.of(), entry, mark));

        assertThat(result.performance().currentR().status()).isEqualTo(MetricStatus.OK);
        assertThat(result.performance().mae().status()).isEqualTo(MetricStatus.INSUFFICIENT_HISTORY);
        assertThat(result.performance().mfe().status()).isEqualTo(MetricStatus.INSUFFICIENT_HISTORY);
        assertThat(result.performance().forwardReturns())
                .allSatisfy(value -> assertThat(value.returnPct().status())
                        .isEqualTo(MetricStatus.INSUFFICIENT_HISTORY));
    }

    @Test
    void reportsInputQualityAndValidatesOrderedPositiveOhlcv() {
        var bars = bars(60, index -> bar(index, bd("100")));
        var sourceConflict = input(bars, date(59), List.of(), List.of(), null);
        var conflict = new Input(sourceConflict.symbol(), sourceConflict.asOf(), sourceConflict.evaluatedAt(),
                sourceConflict.configured(), sourceConflict.bars(), sourceConflict.spyBars(), sourceConflict.source(),
                true, sourceConflict.historyComplete(), sourceConflict.priceAdjustmentStatus(),
                sourceConflict.splitConflictDates(), sourceConflict.anchors(), sourceConflict.currentMark(),
                sourceConflict.performanceEntry());
        assertThat(calculate(conflict).status()).isEqualTo(OverlayStatus.SOURCE_CONFLICT);
        assertThat(calculate(conflict).stage()).isNull();

        var partial = input(bars, date(59), List.of(), List.of(), null, null, false, true);
        assertThat(calculate(partial).status()).isEqualTo(OverlayStatus.PARTIAL);
        var missing = input(List.of(), date(0), List.of(), List.of(), null);
        assertThat(calculate(missing).status()).isEqualTo(OverlayStatus.DATA_MISSING);
        var notConfigured = new Input("ABC", date(59), evaluation(date(59)), false, bars, List.of(), "test", false, true,
                PriceAdjustmentStatus.ADJUSTED, List.of(), List.of(), null, null);
        assertThat(calculate(notConfigured).status()).isEqualTo(OverlayStatus.NOT_CONFIGURED);

        assertThatThrownBy(() -> new Bar(date(0), bd("10"), bd("9"), bd("8"), bd("10"), bd("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input(List.of(bar(2, bd("10")), bar(1, bd("10"))), date(2),
                List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bar(date(0), bd("10"), bd("11"), bd("9"), bd("10"), bd("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Input("ABC", date(60), evaluation(date(59)), true, bars,
                List.of(), "TEST_SOURCE", false, true, PriceAdjustmentStatus.ADJUSTED,
                List.of(), List.of(), null, null)).isInstanceOf(IllegalArgumentException.class);

        var zeroVolume = bars(21, index -> new Bar(date(index), bd("10"), bd("11"), bd("9"), bd("10"),
                index == 20 ? bd("1") : BigDecimal.ZERO));
        var zeroVolumeResult = calculate(input(zeroVolume, date(20), List.of(), List.of(), null));
        assertThat(zeroVolumeResult.indicators().get(19).dailyRolling20Vwap().status())
                .isEqualTo(MetricStatus.DATA_MISSING);
        assertThat(zeroVolumeResult.indicators().get(20).relativeVolume().status())
                .isEqualTo(MetricStatus.DATA_MISSING);
    }

    private static Stage stage(List<BigDecimal> closes) {
        var values = new ArrayList<Bar>();
        for (int index = 0; index < closes.size(); index++) {
            values.add(bar(index, closes.get(index)));
        }
        return calculate(input(values, date(values.size() - 1), List.of(), List.of(), null)).stage();
    }

    private static List<BigDecimal> flatCloses(int count, String value) {
        return closes(count, ignored -> bd(value));
    }

    private static List<BigDecimal> closes(int count, IntFunction<BigDecimal> value) {
        var closes = new ArrayList<BigDecimal>();
        for (int index = 0; index < count; index++) {
            closes.add(value.apply(index));
        }
        return closes;
    }

    private static List<Bar> bars(int count, IntFunction<Bar> value) {
        var bars = new ArrayList<Bar>();
        for (int index = 0; index < count; index++) {
            bars.add(value.apply(index));
        }
        return bars;
    }

    private static Bar bar(int index, BigDecimal close) {
        return new Bar(date(index), close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                bd(String.valueOf(100 + index)));
    }

    private static LocalDate date(int index) {
        return FIRST_DATE.plusDays(index);
    }

    private static Input input(List<Bar> bars, LocalDate asOf, List<BenchmarkBar> spy,
                               List<TacticalOverlayCalculator.AvwapAnchor> anchors,
                               PerformanceEntry entry) {
        return input(bars, asOf, spy, anchors, entry, null, true, true);
    }

    private static Input input(List<Bar> bars, LocalDate asOf, List<BenchmarkBar> spy,
                               List<TacticalOverlayCalculator.AvwapAnchor> anchors,
                               PerformanceEntry entry, Mark mark) {
        return input(bars, asOf, spy, anchors, entry, mark, true, true);
    }

    private static Input input(List<Bar> bars, LocalDate asOf, List<BenchmarkBar> spy,
                               List<TacticalOverlayCalculator.AvwapAnchor> anchors,
                               PerformanceEntry entry, Mark mark, boolean historyComplete, boolean configured) {
        return new Input("ABC", asOf, evaluation(asOf), configured, bars, spy, "TEST_SOURCE", false,
                historyComplete, PriceAdjustmentStatus.ADJUSTED, List.of(), anchors, mark, entry);
    }

    private static Instant evaluation(LocalDate date) {
        return date.atTime(16, 5).toInstant(java.time.ZoneOffset.UTC);
    }

    private static BigDecimal averageWeightedClose(int from, int to) {
        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;
        for (int index = from; index <= to; index++) {
            var close = bd("100").add(bd(String.valueOf(index)));
            var volume = bd(String.valueOf(100 + index));
            numerator = numerator.add(close.multiply(volume));
            denominator = denominator.add(volume);
        }
        return numerator.divide(denominator, 10, java.math.RoundingMode.HALF_UP);
    }

    private static TacticalOverlayCalculator.Result calculate(Input input) {
        return new TacticalOverlayCalculator(TacticalOverlayProperties.defaults()).calculate(input);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
