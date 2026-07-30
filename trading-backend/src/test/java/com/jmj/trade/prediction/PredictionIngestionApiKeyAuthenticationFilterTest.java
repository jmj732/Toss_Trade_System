package com.jmj.trade.prediction;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PredictionIngestionApiKeyAuthenticationFilterTest {

    @Test
    void redisFailureReturnsTemporaryFailureWithoutLastUsedUpdate() throws Exception {
        var apiKeys = mock(PredictionIngestionApiKeyService.class);
        var limiter = mock(PredictionIngestionApiKeyRateLimiter.class);
        var registry = new SimpleMeterRegistry();
        var metrics = new PredictionIngestionApiKeyMetrics(registry);
        var filter = new PredictionIngestionApiKeyAuthenticationFilter(
                apiKeys, limiter, metrics);
        var keyId = UUID.randomUUID();
        var authenticated = new PredictionIngestionApiKeyService.AuthenticatedKey(
                keyId,
                UUID.randomUUID(),
                "tpik_12345678",
                new AnalysisPredictionService.ModelContractScope("model-v1", "contract-v1"),
                null);
        when(apiKeys.findActive("tpik_1234567890")).thenReturn(Optional.of(authenticated));
        when(limiter.acquire(keyId)).thenThrow(
                new PredictionIngestionApiKeyRateLimiter.RateLimitUnavailableException());
        var request = new MockHttpServletRequest(
                "POST", "/api/v1/broker-connections/1/analysis-predictions/batch");
        request.addHeader("Authorization", "Bearer tpik_1234567890");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("PREDICTION_INGESTION_RATE_LIMIT_UNAVAILABLE");
        verify(apiKeys, never()).markUsed(keyId);
        verify(chain, never()).doFilter(request, response);
        assertThat(registry.get("trade.prediction.ingestion.api.key.rejected")
                .tag("reason", "redis_unavailable").counter().count()).isOne();
    }
}
