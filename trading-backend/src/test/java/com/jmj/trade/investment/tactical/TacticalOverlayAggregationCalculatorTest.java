package com.jmj.trade.investment.tactical;

import com.jmj.trade.investment.tactical.TacticalOverlayAggregationCalculator.MembershipInterval;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Bar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Cohort;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Event;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.EventType;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.IndicatorBar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.MetricStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.MetricValue;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.OverlayStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.PriceAdjustmentStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Result;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Stage;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.VwapMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TacticalOverlayAggregationCalculatorTest {

    private static final TacticalOverlayAggregationCalculator CALCULATOR = new TacticalOverlayAggregationCalculator();
    private static final List<LocalDate> DATES = tradingDates(21);
    private static final LocalDate BASE_DATE = DATES.getFirst();
    private static final LocalDate AS_OF = DATES.getLast();
    private static final UUID A_MEMBERSHIP = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID B_MEMBERSHIP = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Test
    void calculatesHalfBreadthEvenMedianAndFailureRateOverAllMatureBreakouts() {
        var results = Map.of(
                "A", result("A", bd("110"), bd("100"), bd("1"), false, bd("0.1")),
                "B", result("B", bd("90"), bd("100"), bd("3"), false, bd("0.3")),
                "C", result("C", bd("105"), bd("100"), bd("2"), true, bd("0.5")));
        var bars = Map.of(
                "A", bars("A", "110"), "B", bars("B", "90"), "C", bars("C", "105"),
                "SPY", bars("SPY", "105"));
        var memberships = List.of(
                membership("GROWTH", "A", DATES.getFirst(), null, A_MEMBERSHIP),
                membership("GROWTH", "B", DATES.getFirst(), null, B_MEMBERSHIP));

        var theme = CALCULATOR.calculateTheme("GROWTH", AS_OF, BASE_DATE, AS_OF, 20,
                results, bars, memberships);
        var market = CALCULATOR.calculateMarket("TRACKED_SECURITIES", AS_OF, BASE_DATE, AS_OF, 20,
                List.of("A", "B", "C"), results, bars);

        assertThat(theme.metrics().get("ThemeBreadthEMA21").value()).isEqualByComparingTo("0.50000000");
        assertThat(theme.metrics().get("ThemeBreadthEMA50").value()).isEqualByComparingTo("0.50000000");
        assertThat(theme.metrics().get("MedianRVOL").value()).isEqualByComparingTo("2.00000000");
        assertThat(theme.metrics().get("ThemeReturn20D").value()).isEqualByComparingTo("0E-8");
        assertThat(theme.metrics().get("ThemeRS20_SPY").value()).isEqualByComparingTo("-0.04761905");
        assertThat(theme.inputRefs()).containsExactly(A_MEMBERSHIP, B_MEMBERSHIP);

        assertThat(market.metrics().get("BreakoutCount").value()).isEqualByComparingTo("3");
        assertThat(market.metrics().get("BreakoutSuccessRate3D").value()).isEqualByComparingTo("0.66666667");
        assertThat(market.metrics().get("BreakoutSuccessRate5D").value()).isEqualByComparingTo("0.66666667");
        assertThat(market.metrics().get("FailedBreakoutRate").value()).isEqualByComparingTo("0.33333333");
        assertThat(market.metrics().get("FailedBreakoutRate").eligibleCount()).isEqualTo(3);
        assertThat(market.metrics().get("MedianPostBreakoutReturn5D").value()).isEqualByComparingTo("0.30000000");
    }

    @Test
    void missingCurrentMemberMakesThemeMetricsPartial() {
        var result = result("A", bd("110"), bd("100"), bd("1"), false, bd("0.1"));
        var memberships = List.of(
                membership("GROWTH", "A", DATES.getFirst(), null, A_MEMBERSHIP),
                membership("GROWTH", "B", DATES.getFirst(), null, B_MEMBERSHIP));

        var aggregate = CALCULATOR.calculateTheme("GROWTH", AS_OF, BASE_DATE, AS_OF, 20,
                Map.of("A", result), Map.of("A", bars("A", "110"), "SPY", bars("SPY", "105")), memberships);

        assertThat(aggregate.status()).isEqualTo(OverlayStatus.PARTIAL);
        assertThat(aggregate.expectedMembers()).isEqualTo(2);
        assertThat(aggregate.eligibleMembers()).isEqualTo(1);
        assertThat(aggregate.metrics().get("ThemeReturn20D").status()).isEqualTo(MetricStatus.PARTIAL);
        assertThat(aggregate.metrics().get("ThemeReturn20D").expectedCount()).isEqualTo(2);
        assertThat(aggregate.metrics().get("ThemeReturn20D").eligibleCount()).isEqualTo(1);
        assertThat(aggregate.reasons()).contains("MEMBER_DATA_MISSING");
    }

    @Test
    void historicalThemeCohortUsesMembershipOnBreakoutDate() {
        var result = result("A", bd("110"), bd("100"), bd("1"), false, bd("0.1"));
        var memberships = List.of(
                membership("OLD_THEME", "A", DATES.getFirst(), DATES.get(16), A_MEMBERSHIP),
                membership("NEW_THEME", "A", DATES.get(16), null, B_MEMBERSHIP));

        var oldTheme = CALCULATOR.calculateTheme("OLD_THEME", AS_OF, BASE_DATE, AS_OF, 20,
                Map.of("A", result), Map.of("A", bars("A", "110")), memberships);
        var newTheme = CALCULATOR.calculateTheme("NEW_THEME", AS_OF, BASE_DATE, AS_OF, 20,
                Map.of("A", result), Map.of("A", bars("A", "110")), memberships);

        assertThat(oldTheme.metrics().get("BreakoutCount").value()).isEqualByComparingTo("1");
        assertThat(oldTheme.metrics().get("BreakoutSuccessRate5D").value()).isEqualByComparingTo("1.00000000");
        assertThat(newTheme.metrics().get("BreakoutCount").value()).isEqualByComparingTo("0");
        assertThat(newTheme.metrics().get("BreakoutSuccessRate5D").eligibleCount()).isZero();
    }

    @Test
    void excludesCohortsWhoseExactHorizonEndpointIsAfterMaturityCutoff() {
        var result = result("A", bd("110"), bd("100"), bd("1"), false, bd("0.1"), true, true);
        var market = CALCULATOR.calculateMarket("TRACKED_SECURITIES", AS_OF, BASE_DATE, DATES.get(19), 20,
                List.of("A"), Map.of("A", result), Map.of("A", bars("A", "110"), "SPY", bars("SPY", "105")));

        var success5 = market.metrics().get("BreakoutSuccessRate5D");
        var failure = market.metrics().get("FailedBreakoutRate");
        assertThat(market.metrics().get("BreakoutCount").value()).isEqualByComparingTo("1");
        assertThat(success5.value()).isNull();
        assertThat(success5.expectedCount()).isEqualTo(1);
        assertThat(success5.eligibleCount()).isZero();
        assertThat(success5.status()).isEqualTo(MetricStatus.INSUFFICIENT_HISTORY);
        assertThat(success5.reasons()).contains("NO_MATURED_5D_BREAKOUTS");
        assertThat(failure.value()).isNull();
        assertThat(failure.eligibleCount()).isZero();
    }

    private static Result result(String symbol, BigDecimal close, BigDecimal ema21, BigDecimal rvol,
                                 boolean failed, BigDecimal return5) {
        return result(symbol, close, ema21, rvol, failed, return5, false, false);
    }

    private static Result result(String symbol, BigDecimal close, BigDecimal ema21, BigDecimal rvol,
                                 boolean failed, BigDecimal return5, boolean immature, boolean oneBreakout) {
        var eventDate = DATES.get(oneBreakout ? 19 : 15);
        var event = new Event(EventType.BREAKOUT, eventDate, eventDate, bd("101"), bd("102"), bd("101"));
        var horizon3End = immature ? DATES.get(20) : DATES.get(18);
        var horizon5End = immature ? DATES.get(20) : DATES.get(20);
        var cohorts = List.of(
                new Cohort(eventDate, bd("101"), bd("102"), 3, !immature, immature ? null : !failed,
                        immature ? null : failed, horizon3End, immature ? null : return5,
                        immature ? MetricStatus.INSUFFICIENT_HISTORY : MetricStatus.OK),
                new Cohort(eventDate, bd("101"), bd("102"), 5, !immature, immature ? null : !failed,
                        immature ? null : failed, horizon5End, immature ? null : return5,
                        immature ? MetricStatus.INSUFFICIENT_HISTORY : MetricStatus.OK));
        var indicator = new IndicatorBar(AS_OF, MetricValue.available(close), MetricValue.available(ema21),
                MetricValue.available(ema21), MetricValue.available(close), MetricValue.available(rvol),
                MetricValue.available(BigDecimal.ZERO), MetricValue.available(BigDecimal.ZERO),
                MetricValue.available(BigDecimal.ZERO), MetricValue.available(BigDecimal.ZERO));
        return new Result(symbol, AS_OF, "TOSS", OverlayStatus.OK, PriceAdjustmentStatus.ADJUSTED,
                Stage.BASE, OverlayStatus.OK, VwapMethod.DAILY_ROLLING_20, List.of(), List.of(indicator),
                List.of(event), cohorts, List.of(), null);
    }

    private static MembershipInterval membership(String theme, String ticker, LocalDate from, LocalDate to,
                                                  UUID ref) {
        return new MembershipInterval(theme, ticker, from, to, "USER_INPUT", List.of(ref));
    }

    private static List<Bar> bars(String ticker, String endingClose) {
        var bars = new ArrayList<Bar>();
        for (var index = 0; index < DATES.size(); index++) {
            var close = index == DATES.size() - 1 ? bd(endingClose) : bd("100");
            bars.add(new Bar(DATES.get(index), close.add(ONE), close.add(ONE), close.subtract(ONE), close, bd("100")));
        }
        return List.copyOf(bars);
    }

    private static List<LocalDate> tradingDates(int count) {
        var dates = new ArrayList<LocalDate>();
        var date = LocalDate.parse("2026-01-05");
        while (dates.size() < count) {
            if (date.getDayOfWeek().getValue() < 6) dates.add(date);
            date = date.plusDays(1);
        }
        return List.copyOf(dates);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static final BigDecimal ONE = BigDecimal.ONE;
}
