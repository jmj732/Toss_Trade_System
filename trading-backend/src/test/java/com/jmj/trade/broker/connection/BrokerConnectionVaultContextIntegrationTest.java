package com.jmj.trade.broker.connection;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@Import(BrokerConnectionVaultContextIntegrationTest.RedisInfrastructure.class)
class BrokerConnectionVaultContextIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void vaultEnabledWithRedisCreatesProviderAdapterValidationServiceAndController() {
        assertThat(context.getBeansOfType(TossCredentialProvider.class)).hasSize(1);
        assertThat(context.getBeansOfType(BrokerAdapter.class)).hasSize(1);
        assertThat(context.getBeansOfType(BrokerConnectionValidationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(BrokerConnectionController.class)).hasSize(1);
    }

    @TestConfiguration
    static class RedisInfrastructure {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
