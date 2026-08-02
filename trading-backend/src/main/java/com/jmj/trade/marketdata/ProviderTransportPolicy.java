package com.jmj.trade.marketdata;

import java.time.Duration;
import java.util.Objects;

public record ProviderTransportPolicy(
        Duration connectTimeout,
        Duration readTimeout,
        int maxRetries,
        Duration retryBackoff,
        int requestsPerWindow,
        Duration rateLimitWindow
) {

    public ProviderTransportPolicy {
        positive(connectTimeout, "connectTimeout");
        positive(readTimeout, "readTimeout");
        nonNegative(retryBackoff, "retryBackoff");
        positive(rateLimitWindow, "rateLimitWindow");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (requestsPerWindow < 1) {
            throw new IllegalArgumentException("requestsPerWindow must be positive");
        }
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
