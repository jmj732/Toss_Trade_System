package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record Position(
        BrokerAccountRef account,
        String symbol,
        String name,
        String marketCountry,
        BigDecimal quantity,
        Currency currency,
        BigDecimal averagePrice,
        BigDecimal lastPrice,
        BigDecimal purchaseAmount,
        BigDecimal marketValueAmount,
        BigDecimal marketValueAmountAfterCost,
        BigDecimal profitLossAmount,
        BigDecimal profitLossAmountAfterCost,
        BigDecimal profitLossRate,
        BigDecimal profitLossRateAfterCost,
        BigDecimal dailyProfitLossAmount,
        BigDecimal dailyProfitLossRate,
        BigDecimal commission,
        BigDecimal tax,
        Instant observedAt) {

    public Position {
        Objects.requireNonNull(account, "account");
        symbol = BrokerPreconditions.nonBlank(symbol, "symbol");
        name = BrokerPreconditions.nonBlank(name, "name");
        marketCountry = BrokerPreconditions.nonBlank(marketCountry, "marketCountry");
        quantity = BrokerPreconditions.nonNegative(quantity, "quantity");
        Objects.requireNonNull(currency, "currency");
        averagePrice = BrokerPreconditions.nonNegative(averagePrice, "averagePrice");
        lastPrice = BrokerPreconditions.nonNegative(lastPrice, "lastPrice");
        purchaseAmount = BrokerPreconditions.nonNegative(purchaseAmount, "purchaseAmount");
        marketValueAmount = BrokerPreconditions.nonNegative(marketValueAmount, "marketValueAmount");
        marketValueAmountAfterCost = BrokerPreconditions.nonNegative(marketValueAmountAfterCost, "marketValueAmountAfterCost");
        Objects.requireNonNull(profitLossAmount, "profitLossAmount");
        Objects.requireNonNull(profitLossAmountAfterCost, "profitLossAmountAfterCost");
        Objects.requireNonNull(profitLossRate, "profitLossRate");
        Objects.requireNonNull(profitLossRateAfterCost, "profitLossRateAfterCost");
        Objects.requireNonNull(dailyProfitLossAmount, "dailyProfitLossAmount");
        Objects.requireNonNull(dailyProfitLossRate, "dailyProfitLossRate");
        commission = BrokerPreconditions.nonNegative(commission, "commission");
        if (tax != null) {
            tax = BrokerPreconditions.nonNegative(tax, "tax");
        }
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
