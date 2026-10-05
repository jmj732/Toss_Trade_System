package com.jmj.trade.investment.tactical;

import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.AnchorType;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.AnchoredVwap;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator.MetricValue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TacticalOverlayJsonContractTest {

    @Test
    void anchoredVwapJsonExposesExplicitNameAndKeepsValueAccessorProperty() throws Exception {
        var expected = new BigDecimal("12.34");
        var result = new AnchoredVwap("position-entry", LocalDate.parse("2026-09-01"),
                AnchorType.POSITION_ENTRY, LocalDate.parse("2026-10-02"),
                MetricValue.available(expected), MetricValue.available(new BigDecimal("0.023")));

        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));

        assertThat(json.path("anchoredVwap").path("value").decimalValue()).isEqualByComparingTo(expected);
        assertThat(json.path("value").path("value").decimalValue()).isEqualByComparingTo(expected);
        assertThat(json.path("distancePct").path("value").decimalValue()).isEqualByComparingTo("0.023");
        assertThat(json.path("asOf").asText()).isEqualTo("2026-10-02");
    }
}
