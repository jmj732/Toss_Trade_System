package com.jmj.trade.broker.connection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Stable response envelope for provider-backed and provider-unsupported UI data. */
public record BrokerSurfaceResponse<T>(
        Status status,
        boolean stale,
        boolean unknown,
        List<String> unknownFields,
        boolean unavailable,
        String unavailableReason,
        List<ProviderProvenance> provenance,
        T data
) {

    public BrokerSurfaceResponse {
        status = Objects.requireNonNull(status, "status");
        unknownFields = List.copyOf(unknownFields == null ? List.of() : unknownFields);
        provenance = List.copyOf(provenance == null ? List.of() : provenance);
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

    public BrokerSurfaceResponse(
            Status status,
            boolean stale,
            boolean unknown,
            List<String> unknownFields,
            boolean unavailable,
            String unavailableReason,
            T data) {
        this(status, stale, unknown, unknownFields, unavailable, unavailableReason, List.of(), data);
    }

    public static <T> BrokerSurfaceResponse<T> available(T data) {
        return available(data, List.of());
    }

    public static <T> BrokerSurfaceResponse<T> available(T data, List<ProviderProvenance> provenance) {
        return new BrokerSurfaceResponse<>(Status.AVAILABLE, false, false, List.of(), false, null, provenance, data);
    }

    public static <T> BrokerSurfaceResponse<T> degraded(
            T data,
            boolean stale,
            boolean unknown,
            List<String> unknownFields,
            String reason
    ) {
        return degraded(data, stale, unknown, unknownFields, reason, List.of());
    }

    public static <T> BrokerSurfaceResponse<T> degraded(
            T data,
            boolean stale,
            boolean unknown,
            List<String> unknownFields,
            String reason,
            List<ProviderProvenance> provenance
    ) {
        return new BrokerSurfaceResponse<>(Status.DEGRADED, stale, unknown, unknownFields, false, reason, provenance, data);
    }

    public static <T> BrokerSurfaceResponse<T> unavailable(String reason) {
        return unavailable(reason, List.of());
    }

    public static <T> BrokerSurfaceResponse<T> unavailable(String reason, List<ProviderProvenance> provenance) {
        return new BrokerSurfaceResponse<>(Status.UNAVAILABLE, false, false, List.of(), true, reason, provenance, null);
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

    public record ProviderProvenance(
            String provider,
            String endpoint,
            String currency,
            Instant asOf,
            Instant observedAt
    ) {
        public ProviderProvenance {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("provider is required");
            }
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("endpoint is required");
            }
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record OrderBookView(
            String symbol,
            Instant timestamp,
            String currency,
            List<LevelView> asks,
            List<LevelView> bids) {
        public OrderBookView {
            asks = List.copyOf(asks == null ? List.of() : asks);
            bids = List.copyOf(bids == null ? List.of() : bids);
        }
    }

    public record LevelView(BigDecimal price, BigDecimal volume) {
    }

    public record CandleSeriesView(
            String symbol,
            String interval,
            boolean adjusted,
            List<CandleView> candles,
            Instant nextBefore) {
        public CandleSeriesView {
            candles = List.copyOf(candles == null ? List.of() : candles);
        }
    }

    public record CandleView(
            Instant timestamp,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal volume,
            String currency) {
    }

    public record ExchangeRateView(
            String baseCurrency,
            String quoteCurrency,
            BigDecimal rate,
            BigDecimal midRate,
            BigDecimal basisPoint,
            String rateChangeType,
            Instant validFrom,
            Instant validUntil) {
    }

    public record MarketCalendarView(String market, JsonNode payload) {
    }

    public record RankingView(
            String type,
            String marketCountry,
            String duration,
            Instant rankedAt,
            List<RankingItemView> items) {
        public RankingView {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    public record RankingItemView(
            int rank,
            String symbol,
            String currency,
            BigDecimal lastPrice,
            BigDecimal basePrice,
            BigDecimal changeRate,
            BigDecimal tradingVolume,
            BigDecimal tradingAmount,
            BigDecimal marketCap) {
    }
}
