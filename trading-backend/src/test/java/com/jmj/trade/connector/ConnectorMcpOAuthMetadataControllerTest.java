package com.jmj.trade.connector;

import com.jmj.trade.broker.connection.BrokerConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConnectorMcpOAuthMetadataControllerTest {

    private final ConnectorMcpOAuthService service = new ConnectorMcpOAuthService(
            mock(BrokerConnectionService.class),
            mock(ConnectorApiKeyService.class),
            new java.security.SecureRandom(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            "https://dashboard.example",
            "oidc", new InMemoryConnectorOAuthClientStore());

    private final MockMvc mvc = standaloneSetup(new ConnectorMcpOAuthMetadataController(service)).build();

    @Test
    void advertisesProtectedResourceAndAuthorizationServerMetadataAtWellKnownUris() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource/api/v1/connector/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource")
                        .value("https://dashboard.example/api/v1/connector/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]")
                        .value("https://dashboard.example/.well-known/oauth-authorization-server"));

        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization_endpoint")
                        .value("https://dashboard.example/api/v1/connector/oauth/authorize"))
                .andExpect(jsonPath("$.token_endpoint")
                        .value("https://dashboard.example/api/v1/connector/oauth/token"))
                .andExpect(jsonPath("$.registration_endpoint")
                        .value("https://dashboard.example/api/v1/connector/oauth/register"))
                .andExpect(jsonPath("$.grant_types_supported").isArray())
                .andExpect(jsonPath("$.grant_types_supported").value(org.hamcrest.Matchers.hasItems(
                        "authorization_code", "refresh_token")))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
    }
}
