package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record Quote(
        BrokerConnectionRef connection,
        String symbol,
        Currency currency,
        BigDecimal lastPrice,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        Instant brokerTimestamp,
        Instant observedAt) {

    public Quote {
        Objects.requireNonNull(connection, "connection");
        symbol = BrokerPreconditions.nonBlank(symbol, "symbol");
        Objects.requireNonNull(currency, "currency");
        lastPrice = BrokerPreconditions.nonNegative(lastPrice, "lastPrice");
        if (bidPrice != null) {
            bidPrice = BrokerPreconditions.nonNegative(bidPrice, "bidPrice");
        }
        if (askPrice != null) {
            askPrice = BrokerPreconditions.nonNegative(askPrice, "askPrice");
        }
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
