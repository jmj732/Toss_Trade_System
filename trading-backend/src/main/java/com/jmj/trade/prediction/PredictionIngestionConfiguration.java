package com.jmj.trade.prediction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

/**
 * Owns the prediction ingestion API-key bean wiring — key service, rate limiter, authentication
 * filter, and the cleanup sub-configuration. It shares the credential vault's activation property
 * so the bean set stays byte-identical to when this wiring lived in
 * {@code CredentialVaultConfiguration}; a follow-up delta can drop the class-level condition once
 * ingestion no longer keys off credential availability.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class PredictionIngestionConfiguration {

    @Bean
    PredictionIngestionApiKeyService predictionIngestionApiKeyService(
            JdbcTemplate jdbcTemplate,
            PredictionModelRegistryService registry,
            PlatformTransactionManager transactionManager,
            SecureRandom credentialSecureRandom
    ) {
        return new PredictionIngestionApiKeyService(
                jdbcTemplate, registry, new TransactionTemplate(transactionManager),
                credentialSecureRandom, Clock.systemUTC());
    }

    @Bean
    PredictionIngestionApiKeyRateLimiter predictionIngestionApiKeyRateLimiter(
            StringRedisTemplate redis,
            @Value("${prediction.ingestion-api-key.rate-limit.limit:60}") int limit,
            @Value("${prediction.ingestion-api-key.rate-limit.window:PT1M}") Duration window
    ) {
        return new PredictionIngestionApiKeyRateLimiter(redis, limit, window);
    }

    @Bean
    PredictionIngestionApiKeyAuthenticationFilter predictionIngestionApiKeyAuthenticationFilter(
            PredictionIngestionApiKeyService apiKeys,
            PredictionIngestionApiKeyRateLimiter rateLimiter,
            PredictionIngestionApiKeyMetrics metrics,
            ObjectMapper objectMapper
    ) {
        return new PredictionIngestionApiKeyAuthenticationFilter(
                apiKeys, rateLimiter, metrics, objectMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "prediction.ingestion-api-key.cleanup",
            name = "enabled",
            havingValue = "true")
    @EnableScheduling
    static class PredictionIngestionApiKeyCleanupConfiguration {

        @Bean
        PredictionIngestionApiKeyCleanup predictionIngestionApiKeyCleanup(
                JdbcTemplate jdbcTemplate
        ) {
            return new PredictionIngestionApiKeyCleanup(jdbcTemplate);
        }
    }
}
