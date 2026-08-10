package com.jmj.trade.broker.connection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Stable response envelope for provider-backed and provider-unsupported UI data. */
public record BrokerSurfaceResponse<T>(
        Status status,
        boolean stale,
        boolean unknown,
        List<String> unknownFields,
        boolean unavailable,
        String unavailableReason,
        T data
) {

    public BrokerSurfaceResponse {
        status = Objects.requireNonNull(status, "status");
        unknownFields = List.copyOf(unknownFields == null ? List.of() : unknownFields);
        if (unavailable != (status == Status.UNAVAILABLE)) {
            throw new IllegalArgumentException("unavailable must match status");
        }
        if (unavailable && (unavailableReason == null || unavailableReason.isBlank())) {
            throw new IllegalArgumentException("unavailableReason is required");
        }
        if (status == Status.AVAILABLE && unavailableReason != null) {
            throw new IllegalArgumentException("available response must not carry unavailableReason");
        }
        if (unavailable && data != null) {
            throw new IllegalArgumentException("unavailable response must not carry data");
        }
    }

    public static <T> BrokerSurfaceResponse<T> available(T data) {
        return new BrokerSurfaceResponse<>(Status.AVAILABLE, false, false, List.of(), false, null, data);
    }

    public static <T> BrokerSurfaceResponse<T> degraded(
            T data,
            boolean stale,
            boolean unknown,
            List<String> unknownFields,
            String reason
    ) {
        return new BrokerSurfaceResponse<>(Status.DEGRADED, stale, unknown, unknownFields, false, reason, data);
    }

    public static <T> BrokerSurfaceResponse<T> unavailable(String reason) {
        return new BrokerSurfaceResponse<>(Status.UNAVAILABLE, false, false, List.of(), true, reason, null);
    }

    public enum Status {
        AVAILABLE,
        DEGRADED,
        UNAVAILABLE
    }

    public record PriceView(
            String symbol,
            BigDecimal lastPrice,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            String currency,
            Instant observedAt,
            Instant brokerTimestamp
    ) {
    }

    public record SellableQuantityView(
            String symbol,
            String availability,
            BigDecimal quantity,
            Instant observedAt
    ) {
    }
}
