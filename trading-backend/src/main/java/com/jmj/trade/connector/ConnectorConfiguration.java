package com.jmj.trade.connector;

import com.jmj.trade.prediction.PredictionIngestionApiKeyRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class ConnectorConfiguration {
    @Bean
    ConnectorApiKeyService connectorApiKeyService(JdbcTemplate jdbc, PlatformTransactionManager tx,
                                                   SecureRandom credentialSecureRandom) {
        return new ConnectorApiKeyService(jdbc, new TransactionTemplate(tx), credentialSecureRandom, Clock.systemUTC());
    }

    @Bean
    ConnectorApiKeyAuthenticationFilter connectorApiKeyAuthenticationFilter(
            ConnectorApiKeyService keys, PredictionIngestionApiKeyRateLimiter rateLimiter) {
        return new ConnectorApiKeyAuthenticationFilter(keys, rateLimiter);
    }
}
