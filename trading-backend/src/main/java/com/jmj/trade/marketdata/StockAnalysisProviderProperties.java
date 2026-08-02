package com.jmj.trade.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import tools.jackson.core.JsonPointer;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.Map;

@ConfigurationProperties("stock-analysis")
public record StockAnalysisProviderProperties(Map<String, ProviderConfiguration> providers) {

    public StockAnalysisProviderProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    public record ProviderConfiguration(
            boolean enabled,
            boolean includeSymbolQuery,
            URI baseUrl,
            String path,
            String apiKey,
            String apiKeyHeader,
            String apiKeyQueryParameter,
            Map<String, String> queryParameters,
            Set<String> queryIdentifiers,
            String userAgent,
            Map<String, String> units,
            Map<String, String> periods,
            Map<String, String> identifiers,
            Map<String, String> asOfPaths,
            String asOfFormat,
            Duration connectTimeout,
            Duration readTimeout,
            int maxRetries,
            Duration retryBackoff,
            int requestsPerWindow,
            Duration rateLimitWindow,
            String asOfPath,
            Map<String, String> fields
    ) {

        public ProviderConfiguration {
            path = path == null || path.isBlank() ? "/" : path;
            apiKeyHeader = apiKeyHeader == null ? "" : apiKeyHeader.trim();
            apiKeyQueryParameter = apiKeyQueryParameter == null ? "" : apiKeyQueryParameter.trim();
            queryParameters = queryParameters == null ? Map.of() : Map.copyOf(queryParameters);
            queryIdentifiers = queryIdentifiers == null ? Set.of() : Set.copyOf(queryIdentifiers);
            userAgent = userAgent == null ? "" : userAgent.trim();
            units = units == null ? Map.of() : Map.copyOf(units);
            periods = periods == null ? Map.of() : Map.copyOf(periods);
            identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
            asOfPaths = asOfPaths == null ? Map.of() : Map.copyOf(asOfPaths);
            asOfFormat = asOfFormat == null || asOfFormat.isBlank() ? "INSTANT" : asOfFormat.trim().toUpperCase();
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
            retryBackoff = retryBackoff == null ? Duration.ofMillis(50) : retryBackoff;
            rateLimitWindow = rateLimitWindow == null ? Duration.ofSeconds(1) : rateLimitWindow;
            requestsPerWindow = requestsPerWindow < 1 ? 60 : requestsPerWindow;
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            asOfPath = asOfPath == null ? "" : asOfPath.trim();
            new ProviderTransportPolicy(
                    connectTimeout,
                    readTimeout,
                    maxRetries,
                    retryBackoff,
                    requestsPerWindow,
                    rateLimitWindow);
            if (enabled) {
                requireHttpUrl(baseUrl);
                if (fields.isEmpty()) {
                    throw new IllegalArgumentException("enabled provider requires fields");
                }
                fields.forEach((field, pointer) -> requirePointer(pointer, "field " + field));
                if (!asOfPath.isBlank()) {
                    requirePointer(asOfPath, "asOfPath");
                }
                asOfPaths.forEach((field, pointer) -> requirePointer(pointer, "asOfPath " + field));
                if (!Set.of("INSTANT", "EPOCH_SECONDS", "EPOCH_MILLIS", "DATE").contains(asOfFormat)) {
                    throw new IllegalArgumentException("unsupported asOfFormat: " + asOfFormat);
                }
            }
        }

        public ProviderTransportPolicy transportPolicy() {
            return new ProviderTransportPolicy(
                    connectTimeout,
                    readTimeout,
                    maxRetries,
                    retryBackoff,
                    requestsPerWindow,
                    rateLimitWindow);
        }

        @Override
        public String toString() {
            return "ProviderConfiguration[enabled=" + enabled + ", configured="
                    + (baseUrl != null && !fields.isEmpty()) + "]";
        }

        private static void requireHttpUrl(URI value) {
            if (value == null || value.getHost() == null
                    || value.getUserInfo() != null
                    || value.getRawQuery() != null
                    || !("https".equalsIgnoreCase(value.getScheme())
                    || ("http".equalsIgnoreCase(value.getScheme()) && isLocal(value.getHost())))) {
                throw new IllegalArgumentException("enabled provider baseUrl must be http(s) with host");
            }
        }

        private static void requirePointer(String value, String name) {
            if (value == null || value.isBlank() || !value.startsWith("/")) {
                throw new IllegalArgumentException(name + " must be a non-blank JSON pointer");
            }
            try {
                JsonPointer.compile(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(name + " must be a valid JSON pointer", exception);
            }
        }

        private static boolean isLocal(String host) {
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host);
        }
    }
}
