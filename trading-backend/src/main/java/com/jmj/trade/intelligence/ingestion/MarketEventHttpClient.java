package com.jmj.trade.intelligence.ingestion;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;

final class MarketEventHttpClient {

    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    String get(URI uri, MarketEventIngestionProperties.ProviderConfiguration configuration) {
        return get(uri, configuration, () -> true);
    }

    String get(
            URI uri,
            MarketEventIngestionProperties.ProviderConfiguration configuration,
            BooleanSupplier heartbeat
    ) {
        requireUrl(uri);
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(configuration.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(configuration.readTimeout())
                .GET();
        if (!configuration.userAgent().isBlank()) {
            requestBuilder.header("User-Agent", configuration.userAgent());
        }
        var request = requestBuilder.build();
        for (var attempt = 0; ; attempt++) {
            if (!heartbeat.getAsBoolean()) {
                throw new ProviderFailure("LEASE_EXPIRED");
            }
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    var bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new ProviderFailure("RESPONSE_TOO_LARGE");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        if (!retryable(response.statusCode())
                                || attempt >= configuration.maxRetries()) {
                            throw new ProviderFailure("HTTP_" + response.statusCode());
                        }
                        if (!heartbeat.getAsBoolean()) {
                            throw new ProviderFailure("LEASE_EXPIRED");
                        }
                        pause(configuration.retryBackoff(), attempt);
                        continue;
                    }
                    var text = new String(bytes, StandardCharsets.UTF_8);
                    if (text.isBlank()) {
                        throw new ProviderFailure("EMPTY_RESPONSE");
                    }
                    return text;
                }
            } catch (ProviderFailure exception) {
                throw exception;
            } catch (IOException exception) {
                if (attempt >= configuration.maxRetries()) {
                    throw new ProviderFailure("NETWORK", exception);
                }
                if (!heartbeat.getAsBoolean()) {
                    throw new ProviderFailure("LEASE_EXPIRED");
                }
                pause(configuration.retryBackoff(), attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ProviderFailure("INTERRUPTED", exception);
            }
        }
    }

    static boolean retryable(int status) {
        return status == HttpURLConnection.HTTP_CLIENT_TIMEOUT
                || status == 429
                || status >= 500;
    }

    static void requireUrl(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && local(uri.getHost())))) {
            throw new IllegalArgumentException("market event URL must be HTTPS with a host");
        }
    }

    static void requireConfiguredUrl(URI uri) {
        requireUrl(uri);
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("configured market event URL must not contain a query");
        }
    }

    private static boolean local(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    private static void pause(Duration backoff, int attempt) {
        try {
            Thread.sleep(backoff.multipliedBy(attempt + 1L).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderFailure("INTERRUPTED", exception);
        }
    }

    static final class ProviderFailure extends RuntimeException {

        ProviderFailure(String reason) {
            super(reason);
        }

        ProviderFailure(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
