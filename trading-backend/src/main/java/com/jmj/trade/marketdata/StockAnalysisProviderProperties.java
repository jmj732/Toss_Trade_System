package com.jmj.trade.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
            Map<String, String> fields,
            Map<String, EndpointConfiguration> endpoints
    ) {

        public ProviderConfiguration(
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
            this(enabled, includeSymbolQuery, baseUrl, path, apiKey, apiKeyHeader, apiKeyQueryParameter,
                    queryParameters, queryIdentifiers, userAgent, units, periods, identifiers, asOfPaths,
                    asOfFormat, connectTimeout, readTimeout, maxRetries, retryBackoff, requestsPerWindow,
                    rateLimitWindow, asOfPath, fields, Map.of());
        }

        @ConstructorBinding
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
            endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
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
                if (fields.isEmpty() && endpoints.isEmpty()) {
                    throw new IllegalArgumentException("enabled provider requires fields or endpoints");
                }
                if (!fields.isEmpty()) {
                    validateEndpoint(new EndpointConfiguration(path, Map.of(), units, periods, identifiers,
                            asOfPaths, asOfFormat, asOfPath, fields));
                }
                var seenFields = new java.util.HashSet<String>(fields.keySet());
                for (var entry : endpoints.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                        throw new IllegalArgumentException("endpoint name and configuration are required");
                    }
                    validateEndpoint(entry.getValue());
                    if (entry.getValue().declaredFields().stream().anyMatch(field -> !seenFields.add(field))) {
                        throw new IllegalArgumentException("provider endpoints cannot declare duplicate fields");
                    }
                }
            }
        }

        public java.util.List<EndpointConfiguration> configuredEndpoints() {
            var result = new java.util.ArrayList<EndpointConfiguration>();
            if (!fields.isEmpty()) {
                result.add(new EndpointConfiguration(path, Map.of(), units, periods, identifiers,
                        asOfPaths, asOfFormat, asOfPath, fields));
            }
            endpoints.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue).forEach(result::add);
            return java.util.List.copyOf(result);
        }

        public Set<String> configuredFields() {
            var result = new java.util.HashSet<String>(fields.keySet());
            endpoints.values().forEach(endpoint -> result.addAll(endpoint.declaredFields()));
            return Set.copyOf(result);
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
                    + (baseUrl != null && (!fields.isEmpty() || !endpoints.isEmpty())) + "]";
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
            if (value == null) throw new IllegalArgumentException(name + " must be a JSON pointer");
            if (value.isEmpty()) return;
            if (!value.startsWith("/")) throw new IllegalArgumentException(name + " must be a valid JSON pointer");
            try {
                JsonPointer.compile(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(name + " must be a valid JSON pointer", exception);
            }
        }

        private static void validateEndpoint(EndpointConfiguration endpoint) {
            endpoint.fields().forEach((field, pointer) -> requirePointer(pointer, "field " + field));
            if (!endpoint.asOfPath().isBlank()) requirePointer(endpoint.asOfPath(), "asOfPath");
            endpoint.asOfPaths().forEach((field, pointer) -> requirePointer(pointer, "asOfPath " + field));
            if (!Set.of("INSTANT", "EPOCH_SECONDS", "EPOCH_MILLIS", "DATE").contains(endpoint.asOfFormat())) {
                throw new IllegalArgumentException("unsupported asOfFormat: " + endpoint.asOfFormat());
            }
            if (endpoint.fields().isEmpty()) {
                throw new IllegalArgumentException("endpoint requires fields");
            }
        }

        private static boolean isLocal(String host) {
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host);
        }
    }

    public record EndpointConfiguration(
            String path,
            Map<String, String> queryParameters,
            Map<String, String> units,
            Map<String, String> periods,
            Map<String, String> identifiers,
            Map<String, String> asOfPaths,
            String asOfFormat,
            String asOfPath,
            Map<String, String> fields,
            AsOfMode asOfMode,
            PriceSession priceSession
    ) {

        public EndpointConfiguration(
                String path,
                Map<String, String> queryParameters,
                Map<String, String> units,
                Map<String, String> periods,
                Map<String, String> identifiers,
                Map<String, String> asOfPaths,
                String asOfFormat,
                String asOfPath,
                Map<String, String> fields
        ) {
            this(path, queryParameters, units, periods, identifiers, asOfPaths,
                    asOfFormat, asOfPath, fields, AsOfMode.SOURCE_AS_OF, null);
        }

        @ConstructorBinding
        public EndpointConfiguration {
            path = path == null || path.isBlank() ? "/" : path.trim();
            queryParameters = queryParameters == null ? Map.of() : Map.copyOf(queryParameters);
            units = units == null ? Map.of() : Map.copyOf(units);
            periods = periods == null ? Map.of() : Map.copyOf(periods);
            identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
            asOfPaths = asOfPaths == null ? Map.of() : Map.copyOf(asOfPaths);
            asOfFormat = asOfFormat == null || asOfFormat.isBlank() ? "INSTANT" : asOfFormat.trim().toUpperCase();
            asOfPath = asOfPath == null ? "" : asOfPath.trim();
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            asOfMode = asOfMode == null ? AsOfMode.SOURCE_AS_OF : asOfMode;
            if (priceSession != null && fields.containsKey("price.session")) {
                throw new IllegalArgumentException("price.session is supplied by priceSession metadata");
            }
        }

        public Set<String> declaredFields() {
            var result = new java.util.HashSet<>(fields.keySet());
            if (priceSession != null) result.add("price.session");
            return Set.copyOf(result);
        }

        @Override
        public String toString() {
            return "EndpointConfiguration[configuredFields=" + fields.size() + "]";
        }
    }

    public enum AsOfMode {
        SOURCE_AS_OF,
        OBSERVED_AT
    }

    public enum PriceSession {
        REGULAR_CLOSE,
        LIVE_REGULAR,
        AFTER_HOURS,
        PREMARKET
    }
}
