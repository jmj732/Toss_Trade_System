package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

final class BrokerPreconditions {

    private BrokerPreconditions() {
    }

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static Instant nonNegative(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException(name + " must not be before epoch");
        }
        return value;
    }

    static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static <T> Optional<T> optional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static String nullableNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present");
        }
        return value;
    }
}
