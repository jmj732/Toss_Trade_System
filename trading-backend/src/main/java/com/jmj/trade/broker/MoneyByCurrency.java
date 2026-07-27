package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record MoneyByCurrency(Map<Currency, BigDecimal> amounts) {

    public MoneyByCurrency {
        Objects.requireNonNull(amounts, "amounts");
        var copy = new EnumMap<Currency, BigDecimal>(Currency.class);
        amounts.forEach((currency, amount) -> copy.put(
                Objects.requireNonNull(currency, "currency"),
                BrokerPreconditions.nonNegative(amount, "amount")));
        amounts = Map.copyOf(copy);
    }
}
