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

    @Test
    void exposesSyncMetadataAndPositionCountFromSourceObservations() {
        var completedAt = Instant.parse("2026-09-15T00:00:30Z");
        var portfolio = new ConnectorResponse.Portfolio(
                completedAt, false, null, false, List.of(), List.of(),
                new ConnectorResponse.Account(
                        null, null, Map.of(), Map.of("USD", new BigDecimal("900")), Map.of(),
                        Map.of("USD", new BigDecimal("50")), Map.of(), Map.of(),
                        new BigDecimal("0.05"), null, null,
                        Instant.parse("2026-09-15T00:00:10Z")),
                List.of(
                        position("AAPL", "600", "0.10", Instant.parse("2026-09-15T00:00:05Z")),
                        position("MSFT", "300", "0.165", Instant.parse("2026-09-15T00:00:20Z"))),
                Map.of("USD", new ConnectorResponse.BuyingPower(
                        new BigDecimal("100"), Instant.parse("2026-09-15T00:00:07Z"))));

        var state = PortfolioStateBuilder.build(portfolio, List.of());

        assertThat(state.asOf()).isEqualTo(completedAt);
        assertThat(state.syncedAt()).isEqualTo(completedAt);
        assertThat(state.sourceAsOf()).isEqualTo(Instant.parse("2026-09-15T00:00:05Z"));
        assertThat(state.risk().positionCount()).isEqualTo(2);
        assertThat(state.account().profitLossAmounts()).containsEntry("USD", new BigDecimal("50"));
        assertThat(state.account().profitLossRate()).isEqualByComparingTo("0.05");
    }

    @Test
    void doesNotTreatCrossCurrencyWeightAsZero() {
        var portfolio = new ConnectorResponse.Portfolio(
                Instant.parse("2026-09-15T00:00:30Z"), false, null, false, List.of(), List.of(),
                new ConnectorResponse.Account(
                        null, null, Map.of(), Map.of("USD", new BigDecimal("900")), Map.of(),
                        Map.of(), Map.of(), Map.of(), null, null, null, Instant.now()),
                List.of(position("005930", "800", "0.10", Instant.now(), "KRW")),
                Map.of("USD", new ConnectorResponse.BuyingPower(new BigDecimal("100"), Instant.now())));

        var state = PortfolioStateBuilder.build(portfolio, List.of());

        assertThat(state.positions().getFirst().weightPct()).isNull();
        assertThat(state.risk().largestPositionPct()).isNull();
        assertThat(state.risk().positionCount()).isEqualTo(1);
        assertThat(state.partial()).isFalse();
        assertThat(state.unknownFields()).contains("positions[005930].weightPct");
    }

    @Test
    void reportsZeroLargestPositionOnlyWhenThereAreNoPositions() {
        var portfolio = new ConnectorResponse.Portfolio(
                Instant.parse("2026-09-15T00:00:30Z"), false, null, false, List.of(), List.of(),
                new ConnectorResponse.Account(
                        null, null, Map.of(), Map.of("USD", BigDecimal.ZERO), Map.of(),
                        Map.of(), Map.of(), Map.of(), null, null, null, Instant.now()),
                List.of(),
                Map.of("USD", new ConnectorResponse.BuyingPower(new BigDecimal("100"), Instant.now())));

        var state = PortfolioStateBuilder.build(portfolio, List.of());

        assertThat(state.risk().largestPositionPct()).isZero();
        assertThat(state.risk().positionCount()).isZero();
    }

    @Test
    void keepsLegacyStateConstructorUsable() {
        var completedAt = Instant.parse("2026-09-15T00:00:30Z");

        var state = new ConnectorResponse.PortfolioState(
                completedAt, "USD", new ConnectorResponse.StateAccount(null, null, null),
                List.of(), List.of(), new ConnectorResponse.Risk(null, null, null),
                false, null, false, List.of(), List.of());

        assertThat(state.asOf()).isEqualTo(completedAt);
        assertThat(state.sourceAsOf()).isNull();
        assertThat(state.syncedAt()).isEqualTo(completedAt);
        assertThat(state.risk().positionCount()).isNull();
    }

    private static ConnectorResponse.Position position(String symbol, String marketValue, String pnlRate) {
        return position(symbol, marketValue, pnlRate, null);
    }

    private static ConnectorResponse.Position position(
            String symbol, String marketValue, String pnlRate, Instant observedAt) {
        return position(symbol, marketValue, pnlRate, observedAt, "USD");
    }

    private static ConnectorResponse.Position position(
            String symbol, String marketValue, String pnlRate, Instant observedAt, String currency) {
        return new ConnectorResponse.Position(
                symbol, symbol, "US", BigDecimal.ONE, currency, new BigDecimal("10"),
                new BigDecimal("10"), new BigDecimal(marketValue), new BigDecimal(marketValue),
                new BigDecimal(marketValue), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(pnlRate), new BigDecimal(pnlRate), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, observedAt);
    }
}
