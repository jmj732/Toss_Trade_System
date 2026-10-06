package com.jmj.trade.investment.tactical;

import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.AnchorType;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.AnchoredVwap;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.IndicatorBar;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.MetricStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.MetricValue;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.OverlayStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.PriceAdjustmentStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Result;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.Stage;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.VwapMethod;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TacticalOverlayJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anchoredVwapJsonExposesDailyNameAndKeepsExistingProperties() throws Exception {
        var expected = new BigDecimal("12.34");
        var result = new AnchoredVwap("position-entry", LocalDate.parse("2026-09-01"),
                AnchorType.POSITION_ENTRY, LocalDate.parse("2026-10-02"),
                MetricValue.available(expected), MetricValue.available(new BigDecimal("0.023")));

        var json = mapper.readTree(mapper.writeValueAsString(result));

        assertThat(json.path("dailyAvwap").path("value").decimalValue()).isEqualByComparingTo(expected);
        assertThat(json.path("dailyAvwap").path("status").asText()).isEqualTo("OK");
        assertThat(json.path("anchoredVwap").path("value").decimalValue()).isEqualByComparingTo(expected);
        assertThat(json.path("value").path("value").decimalValue()).isEqualByComparingTo(expected);
        assertThat(json.path("distancePct").path("value").decimalValue()).isEqualByComparingTo("0.023");
        assertThat(json.path("asOf").asText()).isEqualTo("2026-10-02");

        var missing = new AnchoredVwap("position-entry", LocalDate.parse("2026-09-01"),
                AnchorType.POSITION_ENTRY, LocalDate.parse("2026-10-02"),
                MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY),
                MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY));
        var missingJson = mapper.readTree(mapper.writeValueAsString(missing));
        assertThat(missingJson.path("dailyAvwap").path("value").isNull()).isTrue();
        assertThat(missingJson.path("dailyAvwap").path("status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(missingJson.path("value").path("status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    void dailyVwap20ProxyJsonAliasesDailyBarProxyAndPreservesMissingStatus() throws Exception {
        var available = MetricValue.available(new BigDecimal("12.34"));
        var json = mapper.readTree(mapper.writeValueAsString(indicator(available)));

        assertThat(json.path("dailyVwap20Proxy").path("value").decimalValue()).isEqualByComparingTo("12.34");
        assertThat(json.path("dailyVwap20Proxy").path("status").asText()).isEqualTo("OK");
        assertThat(json.path("dailyRolling20Vwap").path("value").decimalValue()).isEqualByComparingTo("12.34");

        var unavailable = MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY);
        var missingJson = mapper.readTree(mapper.writeValueAsString(indicator(unavailable)));
        assertThat(missingJson.path("dailyVwap20Proxy").path("value").isNull()).isTrue();
        assertThat(missingJson.path("dailyVwap20Proxy").path("status").asText())
                .isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(missingJson.path("dailyRolling20Vwap").path("value").isNull()).isTrue();
    }

    @Test
    void resultJsonExposesDailyAvwapsAndRetainsAnchoredVwaps() throws Exception {
        var anchor = new AnchoredVwap("entry", LocalDate.parse("2026-09-01"), AnchorType.POSITION_ENTRY,
                LocalDate.parse("2026-10-02"), MetricValue.available(new BigDecimal("12.34")),
                MetricValue.available(new BigDecimal("0.023")));
        var result = new Result("ABC", LocalDate.parse("2026-10-02"), "TOSS", OverlayStatus.OK,
                PriceAdjustmentStatus.ADJUSTED, Stage.BASE, OverlayStatus.OK, VwapMethod.DAILY_ROLLING_20,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(anchor), null);

        var json = mapper.readTree(mapper.writeValueAsString(result));

        assertThat(json.path("vwapMethod").asText()).isEqualTo("DAILY_ROLLING_20");
        assertThat(json.path("dailyAvwaps").path(0).path("dailyAvwap").path("value").decimalValue())
                .isEqualByComparingTo("12.34");
        assertThat(json.path("anchoredVwaps").path(0).path("value").path("value").decimalValue())
                .isEqualByComparingTo("12.34");
    }

    private static IndicatorBar indicator(MetricValue dailyVwap) {
        var unavailable = MetricValue.unavailable(MetricStatus.INSUFFICIENT_HISTORY);
        return new IndicatorBar(LocalDate.parse("2026-10-02"), unavailable, unavailable, unavailable,
                dailyVwap, unavailable, unavailable, unavailable, unavailable, unavailable);
    }
}
