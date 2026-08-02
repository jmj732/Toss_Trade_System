package com.jmj.trade.marketdata;

import java.time.Duration;
import java.time.Instant;

final class ProviderRateLimiter {

    private final Duration interval;
    private final StockDataProviderId provider;
    private Instant nextAllowed = Instant.MIN;

    ProviderRateLimiter(StockDataProviderId provider, ProviderTransportPolicy policy) {
        this.provider = provider;
        interval = policy.rateLimitWindow().dividedBy(policy.requestsPerWindow());
    }

    // ponytail: one lock per provider; use a distributed limiter if multi-instance throughput requires it.
    synchronized void acquire() {
        var now = Instant.now();
        if (nextAllowed.isAfter(now)) {
            var waitMillis = Duration.between(now, nextAllowed).toMillis();
            try {
                Thread.sleep(Math.max(1, waitMillis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ProviderUnavailableException(provider, "rate limiter interrupted");
            }
            now = Instant.now();
        }
        nextAllowed = now.plus(interval);
    }
}
