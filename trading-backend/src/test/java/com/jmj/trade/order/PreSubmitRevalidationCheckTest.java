package com.jmj.trade.order;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PreSubmitRevalidationCheckTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-07-31T00:00:00Z");

    private final AccountOwnershipRevalidationCheck ownership = new AccountOwnershipRevalidationCheck();
    private final SellableQuantityRevalidationCheck sellable = new SellableQuantityRevalidationCheck();
    private final SameSymbolOpenOrderRevalidationCheck openOrder = new SameSymbolOpenOrderRevalidationCheck();

    @Test
    void ownershipMismatchBlocksAndMatchPasses() {
        assertThat(ownership.evaluate(context(OrderSide.BUY, "AAPL", "1", UUID.randomUUID(), CONNECTION, false)))
                .contains(PreTradeRiskEngine.Reason.ACCOUNT_OWNERSHIP_MISMATCH);
        assertThat(ownership.evaluate(context(OrderSide.BUY, "AAPL", "1", USER, UUID.randomUUID(), false)))
                .contains(PreTradeRiskEngine.Reason.ACCOUNT_OWNERSHIP_MISMATCH);
        assertThat(ownership.evaluate(context(OrderSide.BUY, "AAPL", "1", USER, CONNECTION, false)))
                .isEmpty();
    }

    @Test
    void sellableUnknownWhenNoMatchingPositionOrNullQuantity() {
        assertThat(sellable.evaluate(sellContext("AAPL", "2", List.of())))
                .contains(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_UNKNOWN);
        assertThat(sellable.evaluate(sellContext("AAPL", "2", List.of(position("AAPL", Currency.USD, null)))))
                .contains(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_UNKNOWN);
    }

    @Test
    void sellableInsufficientAndSufficient() {
        assertThat(sellable.evaluate(sellContext("AAPL", "2", List.of(position("AAPL", Currency.USD, "1")))))
                .contains(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_INSUFFICIENT);
        assertThat(sellable.evaluate(sellContext("AAPL", "2", List.of(position("AAPL", Currency.USD, "2")))))
                .isEmpty();
    }

    @Test
    void sellableCheckSkipsBuyOrders() {
        assertThat(sellable.evaluate(context(OrderSide.BUY, "AAPL", "2", USER, CONNECTION, false)))
                .isEmpty();
    }

    @Test
    void sameSymbolOpenOrderBlocksAndAbsencePasses() {
        assertThat(openOrder.evaluate(context(OrderSide.BUY, "AAPL", "1", USER, CONNECTION, true)))
                .contains(PreTradeRiskEngine.Reason.OPEN_ORDER_EXISTS);
        assertThat(openOrder.evaluate(context(OrderSide.BUY, "AAPL", "1", USER, CONNECTION, false)))
                .isEmpty();
    }

    private PreSubmitContext sellContext(String symbol, String quantity, List<PortfolioReadService.PositionView> positions) {
        return new PreSubmitContext(
                USER, CONNECTION, USER, CONNECTION,
                OrderSide.SELL, symbol, new BigDecimal(quantity), Currency.USD,
                new BigDecimal("100"), portfolio(positions), false);
    }

    private PreSubmitContext context(
            OrderSide side,
            String symbol,
            String quantity,
            UUID intentUserId,
            UUID intentConnectionId,
            boolean sameSymbolOpenOrderExists
    ) {
        return new PreSubmitContext(
                USER, CONNECTION, intentUserId, intentConnectionId,
                side, symbol, new BigDecimal(quantity), Currency.USD,
                new BigDecimal("100"), portfolio(List.of()), sameSymbolOpenOrderExists);
    }

    private PortfolioReadService.PortfolioView portfolio(List<PortfolioReadService.PositionView> positions) {
        return new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), T0, false, null, false, List.of(), List.of(),
                null, positions, Map.of());
    }

    private PortfolioReadService.PositionView position(String symbol, Currency currency, String sellable) {
        return new PortfolioReadService.PositionView(
                symbol, symbol, "US", BigDecimal.ONE, currency.name(),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                sellable == null ? null : new BigDecimal(sellable), T0);
    }
}
