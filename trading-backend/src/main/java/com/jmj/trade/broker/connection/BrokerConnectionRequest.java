package com.jmj.trade.broker.connection;

import java.nio.charset.StandardCharsets;

public record BrokerConnectionRequest(String clientId, String clientSecret) {

    private static final int MAX_FIELD_BYTES = 4096;

    BrokerConnectionRequest validated() {
        validate(clientId);
        validate(clientSecret);
        return this;
    }

    @Override
    public String toString() {
        return "BrokerConnectionRequest[clientId=****, clientSecret=****]";
    }

    private static void validate(String value) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_FIELD_BYTES) {
            throw BrokerConnectionException.validationFailed();
        }
    }
}
