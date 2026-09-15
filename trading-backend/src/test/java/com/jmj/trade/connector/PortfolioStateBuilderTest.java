package com.jmj.trade.connector;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioStateBuilderTest {

    @Test
    void buildsCalculationReadyUsdStateFromPortfolioAndOrderableCash() {
        var portfolio = new ConnectorResponse.Portfolio(
                Instant.parse("2026-09-15T00:00:00Z"), false, null, false, List.of(), List.of(),
                new ConnectorResponse.Account(
                        null, null, Map.of(), Map.of("USD", new BigDecimal("900")), Map.of(),
                        Map.of(), Map.of(), Map.of(), null, null, null, null),
                List.of(position("AAPL", "600", "0.10"), position("MSFT", "300", "0.165")),
                Map.of("USD", new ConnectorResponse.BuyingPower(new BigDecimal("100"), null)));

        var state = PortfolioStateBuilder.build(
                portfolio,
                List.of(new ConnectorResponse.Order(
                        "order-1", ConnectorResponse.BrokerOrderSide.BUY,
                        ConnectorResponse.BrokerOrderType.LIMIT, "NVDA", new BigDecimal("1"),
                        BigDecimal.ZERO, new BigDecimal("100"), "USD",
                        ConnectorResponse.BrokerOrderLifecycle.PENDING,
                        ConnectorResponse.BrokerOrderGroup.OPEN, null, null, null, null)));

        assertThat(state.currency()).isEqualTo("USD");
        assertThat(state.account().totalValue()).isEqualByComparingTo("1000");
        assertThat(state.account().cash()).isEqualByComparingTo("100");
        assertThat(state.account().cashPct()).isEqualByComparingTo("10");
        assertThat(state.risk().investedPct()).isEqualByComparingTo("90");
        assertThat(state.risk().largestPositionPct()).isEqualByComparingTo("60");
        assertThat(state.positions().get(1).weightPct()).isEqualByComparingTo("30");
        assertThat(state.positions().get(1).unrealizedPnlPct()).isEqualByComparingTo("16.5");
        assertThat(state.openOrders()).hasSize(1);
    }

    @Test
    void doesNotInventTotalsWhenOrderableCashIsMissing() {
        var portfolio = new ConnectorResponse.Portfolio(
                Instant.now(), false, null, false, List.of(), List.of(),
                new ConnectorResponse.Account(
                        null, null, Map.of(), Map.of("USD", new BigDecimal("900")), Map.of(),
                        Map.of(), Map.of(), Map.of(), null, null, null, null),
                List.of(), Map.of());

        var state = PortfolioStateBuilder.build(portfolio, List.of());

        assertThat(state.account().totalValue()).isNull();
        assertThat(state.account().cash()).isNull();
        assertThat(state.risk().investedPct()).isNull();
        assertThat(state.partial()).isTrue();
        assertThat(state.missingSections()).contains("CASH");
    }

    private static ConnectorResponse.Position position(String symbol, String marketValue, String pnlRate) {
        return new ConnectorResponse.Position(
                symbol, symbol, "US", BigDecimal.ONE, "USD", new BigDecimal("10"),
                new BigDecimal("10"), new BigDecimal(marketValue), new BigDecimal(marketValue),
                new BigDecimal(marketValue), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(pnlRate), new BigDecimal(pnlRate), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, null);
    }
}
