package com.jmj.trade.broker;

import java.util.Objects;
import java.util.UUID;

public record BrokerConnectionRef(UUID brokerConnectionId) {

    public BrokerConnectionRef {
        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId");
    }
}
