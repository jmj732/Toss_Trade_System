package com.jmj.trade.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

@Component
final class MonitoringEvaluationClient implements MonitoringEvaluator {

    private final RestClient restClient;

    MonitoringEvaluationClient(
            @Value("${analysis.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${analysis.service.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${analysis.service.read-timeout:PT10S}") Duration readTimeout
    ) {
        var client = HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(positive(readTimeout));
        this.restClient = RestClient.builder()
                .baseUrl(Objects.requireNonNull(baseUrl, "baseUrl"))
                .requestFactory(factory)
                .build();
    }

    @Override
    public JsonNode evaluate(MonitoringEvaluationContract.Request request) {
        var response = restClient.post()
                .uri("/internal/v1/monitoring/evaluations")
                .body(Objects.requireNonNull(request, "request"))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.isNull()) {
            throw new IllegalStateException("analysis service returned an empty monitoring result");
        }
        return response;
    }

    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("monitoring timeout must be positive");
        }
        return value;
    }
}
