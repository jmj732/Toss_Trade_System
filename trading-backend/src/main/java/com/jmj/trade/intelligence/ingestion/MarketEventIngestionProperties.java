package com.jmj.trade.intelligence.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ConfigurationProperties("market-events")
public record MarketEventIngestionProperties(
        Duration interval,
        Duration initialDelay,
        Duration leaseTtl,
        Duration staleAfter,
        Duration lookback,
        Duration retryBackoff,
        Duration maxRetryBackoff,
        int maxAttempts,
        int batchSize,
        int maxEventsPerProvider,
        Map<String, ProviderConfiguration> providers
) {

    public MarketEventIngestionProperties {
        interval = defaultValue(interval, Duration.ofMinutes(15));
        initialDelay = defaultValue(initialDelay, Duration.ofMinutes(1));
        leaseTtl = defaultValue(leaseTtl, Duration.ofMinutes(10));
        staleAfter = defaultValue(staleAfter, Duration.ofMinutes(30));
        lookback = defaultValue(lookback, Duration.ofDays(2));
        retryBackoff = defaultValue(retryBackoff, Duration.ofSeconds(30));
        maxRetryBackoff = defaultValue(maxRetryBackoff, Duration.ofMinutes(30));
        maxAttempts = maxAttempts < 1 ? 3 : maxAttempts;
        batchSize = batchSize < 1 ? 25 : batchSize;
        maxEventsPerProvider = maxEventsPerProvider < 1 ? 200 : maxEventsPerProvider;
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        positive(interval, "interval");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        positive(leaseTtl, "leaseTtl");
        positive(staleAfter, "staleAfter");
        positive(lookback, "lookback");
        positive(retryBackoff, "retryBackoff");
        positive(maxRetryBackoff, "maxRetryBackoff");
    }

    private static Duration defaultValue(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public record ProviderConfiguration(
            boolean enabled,
            URI baseUrl,
            String path,
            String apiKey,
            String userAgent,
            Map<String, String> identifiers,
            Map<String, String> feedUrls,
            List<String> scopes,
            Duration connectTimeout,
            Duration readTimeout,
            int maxRetries,
            Duration retryBackoff
    ) {

        public ProviderConfiguration {
            path = path == null || path.isBlank() ? "/" : path.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            userAgent = userAgent == null ? "" : userAgent.trim();
            identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
            feedUrls = feedUrls == null ? Map.of() : Map.copyOf(feedUrls);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
            retryBackoff = retryBackoff == null ? Duration.ofMillis(100) : retryBackoff;
            if (maxRetries < 0 || !connectTimeout.isPositive() || !readTimeout.isPositive()
                    || retryBackoff.isNegative()) {
                throw new IllegalArgumentException("provider transport configuration is invalid");
            }
        }
    }
}
