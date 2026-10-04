package com.jmj.trade.marketdata;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

final class ProviderHttpTransport {

    private static final Duration MAX_429_BACKOFF = Duration.ofSeconds(30);

    private final StockDataProviderId provider;
    private final StockAnalysisProviderProperties.ProviderConfiguration configuration;
    private final ProviderTransportPolicy policy;
    private final ProviderRateLimiter limiter;
    private final RestClient restClient;
    private final ProviderTransportProfile profile;

    ProviderHttpTransport(
            StockDataProviderId provider,
            StockAnalysisProviderProperties.ProviderConfiguration configuration
    ) {
        this(provider, configuration, new ProviderRateLimiter(provider, configuration.transportPolicy()));
    }

    ProviderHttpTransport(
            StockDataProviderId provider,
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ProviderRateLimiter limiter
    ) {
        this.provider = provider;
        this.configuration = configuration;
        this.profile = ProviderCatalog.transportOf(provider);
        this.policy = configuration.transportPolicy();
        this.limiter = limiter;
        if (profile.userAgentRequired() && configuration.userAgent().isBlank()) {
            throw new IllegalArgumentException(provider + " requires userAgent");
        }
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(policy.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(policy.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(configuration.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    String get(ProviderRequest request) {
        return get(request, configuration.apiKey());
    }

    String get(ProviderRequest request, String apiKey) {
        return get(uri(request, configuration.path(), configuration.queryParameters(), apiKey), apiKey);
    }

    String get(ProviderRequest request, StockAnalysisProviderProperties.EndpointConfiguration endpoint) {
        return get(request, endpoint, configuration.apiKey());
    }

    String get(ProviderRequest request, StockAnalysisProviderProperties.EndpointConfiguration endpoint,
               String apiKey) {
        var queryParameters = new java.util.LinkedHashMap<>(configuration.queryParameters());
        queryParameters.putAll(endpoint.queryParameters());
        return get(uri(request, endpoint.path(), queryParameters, apiKey), apiKey);
    }

    String get(URI uri) {
        return get(uri, configuration.apiKey());
    }

    String get(URI uri, String apiKey) {
        for (var attempt = 0; ; attempt++) {
            limiter.acquire();
            try {
                var body = restClient.get()
                        .uri(uri)
                        .headers(headers -> {
                            if (!configuration.userAgent().isBlank()) {
                                headers.set("User-Agent", configuration.userAgent());
                            }
                            if (apiKeyQueryParameter().isBlank()
                                    && apiKey != null
                                    && !apiKey.isBlank()) {
                                headers.set(apiKeyHeader(), apiKey);
                            }
                        })
                        .retrieve()
                        .body(String.class);
                if (body == null || body.isBlank()) {
                    throw unavailable("EMPTY_RESPONSE");
                }
                return body;
            } catch (ProviderUnavailableException exception) {
                throw exception;
            } catch (RestClientResponseException exception) {
                var status = exception.getStatusCode().value();
                if ((status == 429 || status == 401 || status == 403)
                        && provider == StockDataProviderId.ALPHA_VANTAGE) {
                    var alphaFailure = AlphaVantageEarningsEstimatesProvider.classifyProviderMessage(
                            exception.getResponseBodyAsString());
                    if ("DAILY_QUOTA_EXHAUSTED".equals(alphaFailure)
                            && status == 429 || "INVALID_API_KEY".equals(alphaFailure)) {
                        throw unavailable(alphaFailure);
                    }
                }
                var retryAfter = status == 429 ? retryAfter(exception) : Duration.ZERO;
                var exponentialDelay = status == 429 ? retryDelay(policy.retryBackoff(), attempt) : Duration.ZERO;
                if (status == 429 && retryAfter.compareTo(MAX_429_BACKOFF) > 0) {
                    limiter.coolDownFor(retryAfter);
                    throw unavailable("HTTP_429");
                }
                if (!retryable(status) || attempt >= policy.maxRetries()) {
                    if (status == 429) {
                        limiter.coolDownFor(retryAfter.compareTo(exponentialDelay) > 0
                                ? retryAfter : exponentialDelay);
                    }
                    throw unavailable("HTTP_" + status);
                }
                pause(status == 429 && retryAfter.compareTo(exponentialDelay) > 0
                        ? retryAfter : status == 429 ? exponentialDelay
                        : policy.retryBackoff().multipliedBy(attempt + 1L));
            } catch (ResourceAccessException exception) {
                if (attempt >= policy.maxRetries()) {
                    throw unavailable("NETWORK");
                }
                pause(policy.retryBackoff().multipliedBy(attempt + 1L));
            } catch (RestClientException exception) {
                throw unavailable("CLIENT");
            }
        }
    }

    private URI uri(ProviderRequest request, String endpointPath, Map<String, String> queryParameters,
                    String apiKey) {
        var path = endpointPath.replace("{symbol}", request.symbol());
        for (var entry : request.identifiers().entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        var builder = UriComponentsBuilder.fromUri(configuration.baseUrl()).path(path);
        if (configuration.includeSymbolQuery()) {
            builder.queryParam("symbol", request.symbol());
        }
        queryParameters.forEach(builder::queryParam);
        if (!apiKeyQueryParameter().isBlank()
                && apiKey != null
                && !apiKey.isBlank()) {
            builder.queryParam(apiKeyQueryParameter(), apiKey);
        }
        request.identifiers().forEach((key, value) -> {
            if (configuration.queryIdentifiers().contains(key)) {
                builder.queryParam(key, value);
            }
        });
        return builder.build().encode().toUri();
    }

    private String apiKeyHeader() {
        return configuration.apiKeyHeader().isBlank()
                ? profile.defaultApiKeyHeader()
                : configuration.apiKeyHeader();
    }

    private String apiKeyQueryParameter() {
        if (!configuration.apiKeyHeader().isBlank()) {
            return "";
        }
        return configuration.apiKeyQueryParameter().isBlank()
                ? profile.defaultApiKeyQueryParameter()
                : configuration.apiKeyQueryParameter();
    }

    private boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    static Duration retryDelay(Duration backoff, int attempt) {
        if (backoff == null || backoff.isNegative() || attempt < 0) {
            throw new IllegalArgumentException("retry delay requires a non-negative backoff and attempt");
        }
        long delayMillis = Math.min(backoff.toMillis(), MAX_429_BACKOFF.toMillis());
        for (int index = 0; index < attempt && delayMillis < MAX_429_BACKOFF.toMillis(); index++) {
            delayMillis = Math.min(MAX_429_BACKOFF.toMillis(), delayMillis * 2);
        }
        return Duration.ofMillis(delayMillis);
    }

    private static Duration retryAfter(RestClientResponseException exception) {
        var headers = exception.getResponseHeaders();
        if (headers == null) return Duration.ZERO;
        var value = headers.getFirst("Retry-After");
        if (value == null || value.isBlank()) return Duration.ZERO;
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            try {
                var retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                var delay = Duration.between(Instant.now(), retryAt);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (RuntimeException invalidDate) {
                return Duration.ZERO;
            }
        }
    }

    private void pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("INTERRUPTED");
        }
    }

    private ProviderUnavailableException unavailable(String reason) {
        return new ProviderUnavailableException(provider, reason);
    }
}
