package com.jmj.trade.connector;

import com.jmj.trade.broker.connection.BrokerConnectionService;
import com.jmj.trade.prediction.PredictionIngestionApiKeyRateLimiter;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean
    ConnectorMcpOAuthService connectorMcpOAuthService(
            BrokerConnectionService connections,
            ConnectorApiKeyService keys,
            SecureRandom secureRandom,
            JdbcTemplate jdbc,
            tools.jackson.databind.ObjectMapper objectMapper,
            @Value("${public.dashboard-url:http://localhost:3000}") String publicDashboardUrl,
            @Value("${security.oidc.registration-id:oidc}") String oidcRegistrationId,
            @Value("${connector.mcp.default-scope:connector:read}") String defaultScope
    ) {
        return new ConnectorMcpOAuthService(
                connections,
                keys,
                secureRandom,
                Clock.systemUTC(),
                publicDashboardUrl,
                oidcRegistrationId,
                new JdbcConnectorOAuthClientStore(jdbc, objectMapper),
                new JdbcConnectorOAuthRefreshTokenStore(jdbc),
                defaultScope);
    }
}
