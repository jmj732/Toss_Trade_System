package com.jmj.trade.security;

import com.jmj.trade.connector.ConnectorApiKeyAuthenticationFilter;
import com.jmj.trade.connector.ConnectorApiKeyService;
import com.jmj.trade.connector.ConnectorMcpController;
import com.jmj.trade.connector.ConnectorMcpProtocol;
import com.jmj.trade.connector.ConnectorService;
import com.jmj.trade.investment.InvestmentContextService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvestmentContextMcpSecurityIntegrationTest {

    private static final UUID USER = UUID.fromString("01990000-0000-7000-8000-000000000011");
    private static final UUID OTHER_USER = UUID.fromString("01990000-0000-7000-8000-000000000012");
    private static final UUID CONNECTION = UUID.fromString("01990000-0000-7000-8000-000000000013");

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    UserDetailsServiceAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class,
                    SecurityConfiguration.class));

    @Test
    void missingAndInvalidConnectorKeysAreRejectedBeforeContextRead() throws Exception {
        var keys = mock(ConnectorApiKeyService.class);
        var contextService = mock(InvestmentContextService.class);
        var connectorService = mock(ConnectorService.class);
        var protocol = new ConnectorMcpProtocol(connectorService, null, contextService, new ObjectMapper());
        when(keys.findActive("ckey_invalid")).thenReturn(Optional.empty());
        TestBeans.configure(keys, protocol);

        contextRunner.withUserConfiguration(TestBeans.class).run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply(SecurityMockMvcConfigurers.springSecurity())
                    .build();
            try {
                mvc.perform(post("/api/v1/connector/mcp")
                                .contentType("application/json")
                                .content(contextRequest()))
                        .andExpect(status().isUnauthorized());
                mvc.perform(post("/api/v1/connector/mcp")
                                .header("Authorization", "Bearer ckey_invalid")
                                .contentType("application/json")
                                .content(contextRequest()))
                        .andExpect(status().isUnauthorized());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            verifyNoInteractions(contextService, connectorService);
        });
    }

    @Test
    void readKeyScopesContextToItsUserAndDoesNotExposeTradeTools() throws Exception {
        var keys = mock(ConnectorApiKeyService.class);
        var contextService = mock(InvestmentContextService.class);
        var connectorService = mock(ConnectorService.class);
        var mapper = new ObjectMapper();
        var protocol = new ConnectorMcpProtocol(connectorService, null, contextService, mapper);
        var key = new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), USER, CONNECTION, "ckey_read", null, false, ConnectorApiKeyService.READ_SCOPE);
        when(keys.findActive("ckey_read")).thenReturn(Optional.of(key));
        when(keys.markUsed(key.id())).thenReturn(true);
        when(contextService.context(USER)).thenReturn(new InvestmentContextService.ContextView(
                null, List.of(), List.of(), null, List.of(), null));
        TestBeans.configure(keys, protocol);

        contextRunner.withUserConfiguration(TestBeans.class).run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply(SecurityMockMvcConfigurers.springSecurity())
                    .build();
            try {
                var toolsResponse = mvc.perform(post("/api/v1/connector/mcp")
                                .header("Authorization", "Bearer ckey_read")
                                .contentType("application/json")
                                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                var tools = mapper.readTree(toolsResponse).path("result").path("tools");
                assertThat(tools.toString()).contains("get_investment_context")
                        .doesNotContain("prepare_order", "submit_order", "cancel_order");
                verify(contextService, never()).context(USER);

                var result = mvc.perform(post("/api/v1/connector/mcp")
                                .header("Authorization", "Bearer ckey_read")
                                .contentType("application/json")
                                .content(contextRequest()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                assertThat(mapper.readTree(result).path("result").path("isError").asBoolean()).isFalse();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            verify(contextService).context(USER);
            verify(contextService, never()).context(OTHER_USER);
            verifyNoInteractions(connectorService);
        });
    }

    private static String contextRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"context\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_investment_context\",\"arguments\":{}}}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        private static ConnectorApiKeyService keys;
        private static ConnectorMcpProtocol protocol;

        static void configure(ConnectorApiKeyService apiKeys, ConnectorMcpProtocol mcpProtocol) {
            keys = apiKeys;
            protocol = mcpProtocol;
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
        ConnectorMcpController connectorMcpController() {
            return new ConnectorMcpController(protocol, "https://dashboard.example");
        }
    }
}
