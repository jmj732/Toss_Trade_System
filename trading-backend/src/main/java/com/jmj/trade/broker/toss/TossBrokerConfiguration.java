package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerOrderPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@AutoConfigureAfter(DataRedisAutoConfiguration.class)
@ConditionalOnBean(TossCredentialProvider.class)
@EnableConfigurationProperties(TossApiProperties.class)
public class TossBrokerConfiguration {

    @Bean
    TossOAuthClient tossOAuthClient(TossApiProperties properties) {
        return new TossOAuthClient(properties);
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    TossTokenManager tossTokenManager(
            StringRedisTemplate redis,
            TossCredentialProvider credentialProvider,
            TossOAuthClient oauthClient,
            TossApiProperties properties) {
        return new TossTokenManager(redis, credentialProvider, oauthClient, properties);
    }

    @Bean
    @ConditionalOnBean(TossTokenManager.class)
    TossApiClient tossApiClient(TossApiProperties properties, TossTokenManager tokenManager) {
        return new TossApiClient(properties, tokenManager);
    }

    @Bean
    TossResponseMapper tossResponseMapper() {
        return new TossResponseMapper();
    }

    @Bean
    @ConditionalOnBean(TossApiClient.class)
    TossInvestBrokerAdapter tossBrokerAdapter(TossApiClient apiClient, TossResponseMapper mapper) {
        return new TossInvestBrokerAdapter(apiClient, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "real-order", name = "enabled", havingValue = "true")
    BrokerOrderPort tossOrderPort(TossInvestBrokerAdapter adapter) {
        return adapter;
    }
}
