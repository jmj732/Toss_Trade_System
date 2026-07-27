package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record Position(
        BrokerAccountRef account,
        String symbol,
        String name,
        BigDecimal quantity,
        Currency currency,
        BigDecimal averagePrice,
        BigDecimal currentPrice,
        BigDecimal marketValue,
        BigDecimal unrealizedProfitLoss,
        BigDecimal portfolioWeight,
        BigDecimal commission,
        BigDecimal tax,
        Instant observedAt) {

    public Position {
        Objects.requireNonNull(account, "account");
        symbol = BrokerPreconditions.nonBlank(symbol, "symbol");
        name = BrokerPreconditions.nonBlank(name, "name");
        quantity = BrokerPreconditions.nonNegative(quantity, "quantity");
        Objects.requireNonNull(currency, "currency");
        averagePrice = BrokerPreconditions.nonNegative(averagePrice, "averagePrice");
        currentPrice = BrokerPreconditions.nonNegative(currentPrice, "currentPrice");
        marketValue = BrokerPreconditions.nonNegative(marketValue, "marketValue");
        Objects.requireNonNull(unrealizedProfitLoss, "unrealizedProfitLoss");
        portfolioWeight = BrokerPreconditions.nonNegative(portfolioWeight, "portfolioWeight");
        commission = BrokerPreconditions.nonNegative(commission, "commission");
        if (tax != null) {
            tax = BrokerPreconditions.nonNegative(tax, "tax");
        }
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
