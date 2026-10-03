package com.jmj.trade.marketdata;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class ProviderRateLimiter {

    private static final long SEC_INTERVAL_NANOS = Duration.ofMillis(500).toNanos();
    private static final State SEC_STATE = new State();

    private final long intervalNanos;
    private final StockDataProviderId provider;
    private final State state;

    ProviderRateLimiter(StockDataProviderId provider, ProviderTransportPolicy policy) {
        this(provider, policy, true);
    }

    ProviderRateLimiter(StockDataProviderId provider, ProviderTransportPolicy policy, boolean sharedSecState) {
        this.provider = provider;
        var configuredInterval = policy.rateLimitWindow().dividedBy(policy.requestsPerWindow());
        intervalNanos = provider == StockDataProviderId.SEC
                ? Math.max(SEC_INTERVAL_NANOS, configuredInterval.toNanos())
                : Math.max(1, configuredInterval.toNanos());
        state = provider == StockDataProviderId.SEC && sharedSecState ? SEC_STATE : new State();
    }

    void acquire() {
        synchronized (state) {
            var now = System.nanoTime();
            if (state.cooldownUntilNanos > now) {
                throw new ProviderUnavailableException(provider, "HTTP_429");
            }
            if (state.hasNextAllowed && state.nextAllowedNanos > now) {
                var waitNanos = state.nextAllowedNanos - now;
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ProviderUnavailableException(provider, "rate limiter interrupted");
                }
                now = System.nanoTime();
            }
            state.nextAllowedNanos = now + intervalNanos;
            state.hasNextAllowed = true;
        }
    }

    void coolDownFor(Duration delay) {
        if (delay == null || delay.isNegative() || delay.isZero()) return;
        synchronized (state) {
            var now = System.nanoTime();
            long deadline;
            try {
                deadline = Math.addExact(now, delay.toNanos());
            } catch (ArithmeticException exception) {
                deadline = Long.MAX_VALUE;
            }
            if (deadline > state.cooldownUntilNanos) state.cooldownUntilNanos = deadline;
        }
    }

    private static final class State {
        private long nextAllowedNanos = Long.MIN_VALUE;
        private long cooldownUntilNanos = Long.MIN_VALUE;
        private boolean hasNextAllowed;
    }
}
