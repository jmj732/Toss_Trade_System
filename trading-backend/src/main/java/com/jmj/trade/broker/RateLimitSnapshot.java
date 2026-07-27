package com.jmj.trade.broker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public record RateLimitSnapshot(
        Optional<Integer> limit,
        Optional<Integer> remaining,
        Optional<Instant> resetAt,
        Optional<Duration> retryAfter) {

    public RateLimitSnapshot {
        limit = BrokerPreconditions.optional(limit, "limit");
        remaining = BrokerPreconditions.optional(remaining, "remaining");
        resetAt = BrokerPreconditions.optional(resetAt, "resetAt");
        retryAfter = BrokerPreconditions.optional(retryAfter, "retryAfter");
        limit.ifPresent(value -> requireNonNegative(value, "limit"));
        remaining.ifPresent(value -> requireNonNegative(value, "remaining"));
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
