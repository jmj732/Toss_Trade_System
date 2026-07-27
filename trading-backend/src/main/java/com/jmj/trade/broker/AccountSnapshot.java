package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AccountSnapshot(
        BrokerAccountRef account,
        MoneyByCurrency totalPurchaseAmount,
        MoneyByCurrency marketValueAmount,
        MoneyByCurrency marketValueAmountAfterCost,
        MoneyByCurrency profitLossAmount,
        MoneyByCurrency profitLossAmountAfterCost,
        BigDecimal profitLossRate,
        BigDecimal profitLossRateAfterCost,
        MoneyByCurrency dailyProfitLossAmount,
        BigDecimal dailyProfitLossRate,
        CashBalanceStatus cashBalanceStatus,
        Instant observedAt) {

    public AccountSnapshot {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(totalPurchaseAmount, "totalPurchaseAmount");
        Objects.requireNonNull(marketValueAmount, "marketValueAmount");
        Objects.requireNonNull(marketValueAmountAfterCost, "marketValueAmountAfterCost");
        requireNonNegative(totalPurchaseAmount, "totalPurchaseAmount");
        requireNonNegative(marketValueAmount, "marketValueAmount");
        requireNonNegative(marketValueAmountAfterCost, "marketValueAmountAfterCost");
        Objects.requireNonNull(profitLossAmount, "profitLossAmount");
        Objects.requireNonNull(profitLossAmountAfterCost, "profitLossAmountAfterCost");
        Objects.requireNonNull(profitLossRate, "profitLossRate");
        Objects.requireNonNull(profitLossRateAfterCost, "profitLossRateAfterCost");
        Objects.requireNonNull(dailyProfitLossAmount, "dailyProfitLossAmount");
        Objects.requireNonNull(dailyProfitLossRate, "dailyProfitLossRate");
        Objects.requireNonNull(cashBalanceStatus, "cashBalanceStatus");
        Objects.requireNonNull(observedAt, "observedAt");
        if (cashBalanceStatus != CashBalanceStatus.UNKNOWN) {
            throw new IllegalArgumentException("cashBalanceStatus must be UNKNOWN");
        }
    }

    private static void requireNonNegative(MoneyByCurrency money, String name) {
        money.amounts().forEach((currency, amount) -> BrokerPreconditions.nonNegative(amount, name + "." + currency));
    }
}
