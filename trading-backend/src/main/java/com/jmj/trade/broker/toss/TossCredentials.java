package com.jmj.trade.broker.toss;

import java.util.Objects;

public record TossCredentials(String clientId, String clientSecret) {

    public TossCredentials {
        requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
    }

    @Override
    public String toString() {
        return "TossCredentials[clientId=****, clientSecret=****]";
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
