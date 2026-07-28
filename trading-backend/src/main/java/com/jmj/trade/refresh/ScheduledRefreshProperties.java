package com.jmj.trade.refresh;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("portfolio.refresh")
record ScheduledRefreshProperties(
        boolean enabled,
        Duration interval,
        Duration initialDelay,
        Duration lockTtl,
        Duration connectionStaleAfter,
        Integer maxConsecutiveFailures,
        Integer batchSize
) {

    ScheduledRefreshProperties {
        interval = interval == null ? Duration.ofMinutes(15) : interval;
        initialDelay = initialDelay == null ? Duration.ofMinutes(1) : initialDelay;
        lockTtl = lockTtl == null ? Duration.ofMinutes(10) : lockTtl;
        connectionStaleAfter = connectionStaleAfter == null
                ? Duration.ofHours(24)
                : connectionStaleAfter;
        maxConsecutiveFailures = maxConsecutiveFailures == null ? 3 : maxConsecutiveFailures;
        batchSize = batchSize == null ? 25 : batchSize;

        requirePositive(interval, "interval");
        requirePositive(lockTtl, "lockTtl");
        requirePositive(connectionStaleAfter, "connectionStaleAfter");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (maxConsecutiveFailures < 1) {
            throw new IllegalArgumentException("maxConsecutiveFailures must be at least 1");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
