package com.jmj.trade.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
final class AnalysisServiceHealthIndicator implements HealthIndicator {

    private final HttpClient client;
    private final HttpRequest request;

    AnalysisServiceHealthIndicator(
            @Value("${analysis.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${analysis.service.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${analysis.service.read-timeout:PT5S}") Duration readTimeout
    ) {
        client = HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout, "connectTimeout"))
                .build();
        request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/internal/v1/ready"))
                .timeout(positive(readTimeout, "readTimeout"))
                .GET()
                .build();
    }

    @Override
    public Health health() {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Health.up().build();
            }
            return Health.down().withDetail("statusCode", response.statusCode()).build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return down(exception);
        } catch (IOException | RuntimeException exception) {
            return down(exception);
        }
    }

    private static Health down(Exception exception) {
        return Health.down()
                .withDetail("errorType", exception.getClass().getSimpleName())
                .build();
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
