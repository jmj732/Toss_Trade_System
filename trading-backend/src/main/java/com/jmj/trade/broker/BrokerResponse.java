package com.jmj.trade.broker;

import java.util.Objects;

public record BrokerResponse<T>(T value, BrokerCallMetadata metadata) {

    public BrokerResponse {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(metadata, "metadata");
    }
}
