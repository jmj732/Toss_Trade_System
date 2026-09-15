package com.jmj.trade.security;

import com.jmj.trade.connector.ConnectorApiKeyAuthenticationFilter;
import com.jmj.trade.connector.ConnectorApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConnectorSecurityChainTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    UserDetailsServiceAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class,
                    SecurityConfiguration.class));

    @Test
    void connectorKeyPassesConnectorGetButCannotAuthenticateMutation() throws Exception {
        var keys = mock(ConnectorApiKeyService.class);
        var accessTokens = mock(AccessTokenService.class);
        var key = new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), USER, CONNECTION, "ckey_test", null, false);
        when(keys.findActive("ckey_secret")).thenReturn(Optional.of(key));
        when(keys.markUsed(key.id())).thenReturn(true);
        when(accessTokens.parse("ckey_secret")).thenThrow(new AccessTokenService.InvalidAccessTokenException());

        TestBeans.configure(keys, accessTokens);
        contextRunner
                .withUserConfiguration(TestBeans.class)
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .apply(SecurityMockMvcConfigurers.springSecurity())
                            .build();

                    mvc.perform(get("/api/v1/connector/portfolio")
                                    .header("Authorization", "Bearer ckey_secret"))
                            .andExpect(status().isOk());
                    verify(accessTokens, never()).parse("ckey_secret");

                    mvc.perform(post("/api/v1/paper-orders")
                                    .header("Authorization", "Bearer ckey_secret"))
                            .andExpect(status().isUnauthorized());
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        private static ConnectorApiKeyService keys;
        private static AccessTokenService accessTokens;

        static void configure(ConnectorApiKeyService keys, AccessTokenService accessTokens) {
            TestBeans.keys = keys;
            TestBeans.accessTokens = accessTokens;
        }

        @Bean
        ConnectorApiKeyService connectorApiKeyService() {
            return keys;
        }

        @Bean
        ConnectorApiKeyAuthenticationFilter connectorApiKeyAuthenticationFilter() {
            return new ConnectorApiKeyAuthenticationFilter(keys);
        }

        @Bean
        AccessTokenService accessTokenService() {
            return accessTokens;
        }

        @Bean
        TestEndpoint testEndpoint() {
            return new TestEndpoint();
        }
    }

    @RestController
    static class TestEndpoint {
        @GetMapping({"/api/v1/connector/portfolio", "/api/v1/connector/portfolio/state"})
        void portfolio() {
        }
    }
}
