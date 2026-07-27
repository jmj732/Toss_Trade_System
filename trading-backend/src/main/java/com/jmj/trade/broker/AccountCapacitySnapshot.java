package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AccountCapacitySnapshot(
        BrokerAccountRef account,
        Currency currency,
        BigDecimal cashBuyingPower,
        Instant observedAt) {

    public AccountCapacitySnapshot {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
        cashBuyingPower = BrokerPreconditions.nonNegative(cashBuyingPower, "cashBuyingPower");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
