package com.jmj.trade.broker;

import java.time.Instant;
import java.util.Objects;

public record AccountSnapshot(
        BrokerAccountRef account,
        MoneyByCurrency holdingsMarketValue,
        MoneyByCurrency holdingsCost,
        CashBalanceStatus cashBalanceStatus,
        Instant observedAt) {

    public AccountSnapshot {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(holdingsMarketValue, "holdingsMarketValue");
        Objects.requireNonNull(holdingsCost, "holdingsCost");
        Objects.requireNonNull(cashBalanceStatus, "cashBalanceStatus");
        Objects.requireNonNull(observedAt, "observedAt");
        if (cashBalanceStatus != CashBalanceStatus.UNKNOWN) {
            throw new IllegalArgumentException("cashBalanceStatus must be UNKNOWN");
        }
    }
}
