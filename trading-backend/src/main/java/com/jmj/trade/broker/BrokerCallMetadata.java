package com.jmj.trade.broker;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record BrokerCallMetadata(
        String requestId,
        Instant observedAt,
        Optional<RateLimitSnapshot> rateLimit) {

    public BrokerCallMetadata {
        requestId = BrokerPreconditions.nullableNonBlank(requestId, "requestId");
        Objects.requireNonNull(observedAt, "observedAt");
        rateLimit = BrokerPreconditions.optional(rateLimit, "rateLimit");
    }
}
