package com.jmj.trade.investment.tactical;

import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Bar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.BenchmarkBar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Input;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.PriceAdjustmentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketRiskEvaluatorTest {

    private final TacticalOverlayCalculator calculator = new TacticalOverlayCalculator(
            TacticalOverlayProperties.defaults());
    private final MarketRiskEvaluator evaluator = new MarketRiskEvaluator();

    @Test
    void healthyTossSeriesReportsDrawdownVolatilityAndRelativeStrengthWithoutFundamentalClaims() {
        var input = input(80, index -> bd(100 + index), index -> bd("100"), List.of());
        var result = calculator.calculate(input);

        assertThat(result.marketRisk().status()).isEqualTo(TacticalOverlayCalculator.OverlayStatus.OK);
        assertThat(result.marketRisk().source()).isEqualTo("TOSS");
        assertThat(result.marketRisk().maximumDrawdown20().value()).isEqualByComparingTo("0");
        assertThat(result.marketRisk().maximumDrawdown60().value()).isEqualByComparingTo("0");
        assertThat(result.marketRisk().annualizedVolatility20().value()).isNotNull();
        assertThat(result.marketRisk().relativeStrength20().value().signum()).isPositive();

        var assessment = evaluator.assess(result.marketRisk(), "STRATEGIC", "REVIEW_TRIGGER", true,
                bd("150"), bd("180"), Instant.parse("2026-03-25T20:00:00Z"));
        assertThat(assessment.marketHealth()).isEqualTo("INTACT");
        assertThat(assessment.fundamentalHealth()).isEqualTo("NOT_EVALUABLE");
        assertThat(assessment.priceRiskState()).isEqualTo("CLEAR");
        assertThat(assessment.recommendedAction()).isEqualTo("NO_PRICE_ACTION");
    }

    @Test
    void marketBreakdownAndApprovedTriggerProduceHorizonSpecificReviewWithoutSelling() {
        var input = input(80, index -> index < 55 ? bd(100 + index) : bd(154 - (index - 54) * 2),
                index -> index == 79 ? bd("500") : bd("100"), List.of());
        var result = calculator.calculate(input);

        assertThat(result.marketRisk().maximumDrawdown60().value()).isGreaterThan(bd("0.20"));
        assertThat(result.marketRisk().relativeStrength20().value().signum()).isNegative();
        assertThat(result.marketRisk().priceVolumeSignal())
                .isEqualTo(MarketRiskEvaluator.PriceVolumeSignal.SELLING_VOLUME_SPIKE);

        var tactical = evaluator.assess(result.marketRisk(), "TACTICAL", "EXIT_EVALUATION", true,
                bd("120"), bd("110"), Instant.parse("2026-03-25T20:00:00Z"));
        assertThat(tactical.marketHealth()).isEqualTo("BREAKDOWN");
        assertThat(tactical.priceRiskState()).isEqualTo("TRIGGER_BREACHED");
        assertThat(tactical.recommendedAction()).isEqualTo("EXIT_EVALUATION");
        assertThat(tactical.fundamentalHealth()).isEqualTo("NOT_EVALUABLE");

        var strategic = evaluator.assess(result.marketRisk(), "STRATEGIC", "REVIEW_TRIGGER", true,
                bd("120"), bd("110"), Instant.parse("2026-03-25T20:00:00Z"));
        assertThat(strategic.recommendedAction()).isEqualTo("THESIS_REVALIDATION");
    }

    @Test
    void missingHistoryAndCorporateActionConflictsStayExplicit() {
        var shortResult = calculator.calculate(input(30, index -> bd(100 + index),
                index -> bd("100"), List.of()));
        assertThat(shortResult.marketRisk().maximumDrawdown60().status())
                .isEqualTo(TacticalOverlayCalculator.MetricStatus.INSUFFICIENT_HISTORY);
        assertThat(shortResult.marketRisk().annualizedVolatility20().status())
                .isEqualTo(TacticalOverlayCalculator.MetricStatus.OK);
        assertThat(evaluator.assess(shortResult.marketRisk(), "TACTICAL", "EXIT_EVALUATION", false,
                null, bd("129"), Instant.parse("2026-02-04T20:00:00Z")).marketHealth())
                .isEqualTo("NOT_EVALUABLE");

        var conflicted = calculator.calculate(input(80, index -> bd(100 + index), index -> bd("100"),
                List.of(LocalDate.parse("2026-02-01"))));
        assertThat(conflicted.marketRisk().maximumDrawdown60().status())
                .isEqualTo(TacticalOverlayCalculator.MetricStatus.CORPORATE_ACTION_CONFLICT);
        assertThat(conflicted.marketRisk().annualizedVolatility20().status())
                .isEqualTo(TacticalOverlayCalculator.MetricStatus.OK);
    }

    @Test
    void staleRegularCloseBarsDoNotProduceCurrentMarketHealth() {
        var result = calculator.calculate(input(80, index -> bd(100 + index), index -> bd("100"), List.of()));

        var assessment = evaluator.assess(result.marketRisk(), "TACTICAL", "EXIT_EVALUATION", false,
                null, bd("180"), Instant.parse("2026-11-07T15:00:00Z"),
                Instant.parse("2026-11-07T15:00:00Z"), Duration.ofDays(7));

        assertThat(assessment.marketDataStatus()).isEqualTo("STALE");
        assertThat(assessment.marketHealth()).isEqualTo("NOT_EVALUABLE");
        assertThat(assessment.fundamentalHealth()).isEqualTo("NOT_EVALUABLE");
    }

    private static Input input(int count, java.util.function.IntFunction<BigDecimal> close,
                                java.util.function.IntFunction<BigDecimal> volume,
                                List<LocalDate> splitConflicts) {
        var bars = new ArrayList<Bar>();
        var benchmark = new ArrayList<BenchmarkBar>();
        for (int index = 0; index < count; index++) {
            var date = LocalDate.parse("2026-01-05").plusDays(index);
            var value = close.apply(index);
            bars.add(new Bar(date, value, value.add(BigDecimal.ONE), value.subtract(BigDecimal.ONE),
                    value, volume.apply(index)));
            benchmark.add(new BenchmarkBar(date, bd("200")));
        }
        var asOf = bars.getLast().date();
        return new Input("ABC", asOf, asOf.atTime(22, 0).toInstant(java.time.ZoneOffset.UTC), true,
                bars, benchmark, "TOSS", false, true, PriceAdjustmentStatus.ADJUSTED,
                splitConflicts, List.of(), null, null);
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
