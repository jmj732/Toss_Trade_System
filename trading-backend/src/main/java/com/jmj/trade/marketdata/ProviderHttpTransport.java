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
import java.util.Map;

final class ProviderHttpTransport {

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
        this.provider = provider;
        this.configuration = configuration;
        this.profile = ProviderCatalog.transportOf(provider);
        this.policy = configuration.transportPolicy();
        if (profile.userAgentRequired() && configuration.userAgent().isBlank()) {
            throw new IllegalArgumentException(provider + " requires userAgent");
        }
        this.limiter = new ProviderRateLimiter(provider, policy);
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
        for (var attempt = 0; ; attempt++) {
            limiter.acquire();
            try {
                var body = restClient.get()
                        .uri(uri(request))
                        .headers(headers -> {
                            if (!configuration.userAgent().isBlank()) {
                                headers.set("User-Agent", configuration.userAgent());
                            }
                            if (apiKeyQueryParameter().isBlank()
                                    && configuration.apiKey() != null
                                    && !configuration.apiKey().isBlank()) {
                                headers.set(apiKeyHeader(), configuration.apiKey());
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
                if (!retryable(exception.getStatusCode().value()) || attempt >= policy.maxRetries()) {
                    throw unavailable("HTTP_" + exception.getStatusCode().value());
                }
                pause(attempt);
            } catch (ResourceAccessException exception) {
                if (attempt >= policy.maxRetries()) {
                    throw unavailable("NETWORK");
                }
                pause(attempt);
            } catch (RestClientException exception) {
                throw unavailable("CLIENT");
            }
        }
    }

    private URI uri(ProviderRequest request) {
        var path = configuration.path().replace("{symbol}", request.symbol());
        for (var entry : request.identifiers().entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        var builder = UriComponentsBuilder.fromUri(configuration.baseUrl()).path(path);
        if (configuration.includeSymbolQuery()) {
            builder.queryParam("symbol", request.symbol());
        }
        configuration.queryParameters().forEach(builder::queryParam);
        if (!apiKeyQueryParameter().isBlank()
                && configuration.apiKey() != null
                && !configuration.apiKey().isBlank()) {
            builder.queryParam(apiKeyQueryParameter(), configuration.apiKey());
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

    private void pause(int attempt) {
        var delay = policy.retryBackoff().multipliedBy(attempt + 1L);
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
