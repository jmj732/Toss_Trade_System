package com.jmj.trade.connector;

import com.jmj.trade.broker.connection.BrokerConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConnectorMcpOAuthControllerTest {

    private final ConnectorMcpOAuthService service = new ConnectorMcpOAuthService(
            mock(BrokerConnectionService.class),
            mock(ConnectorApiKeyService.class),
            new java.security.SecureRandom(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            "https://dashboard.example",
            "oidc", new InMemoryConnectorOAuthClientStore());

    private final MockMvc mvc = standaloneSetup(new ConnectorMcpOAuthController(service)).build();

    @Test
    void registersChatGptRedirectUriAndStartsPkceAuthorization() throws Exception {
        var registration = mvc.perform(post("/api/v1/connector/oauth/register")
                        .contentType("application/json")
                        .content("{\"redirect_uris\":[\"https://chatgpt.com/oauth/callback\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client_id").isNotEmpty())
                .andReturn();
        var clientId = new tools.jackson.databind.ObjectMapper()
                .readTree(registration.getResponse().getContentAsString())
                .path("client_id").asText();

        mvc.perform(get("/api/v1/connector/oauth/authorize")
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", "https://chatgpt.com/oauth/callback")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString(
                        "/oauth2/authorization/oidc")));
    }

    @Test
    void returnsJsonErrorForStaleClientInsteadOfWhitelabel500() throws Exception {
        mvc.perform(get("/api/v1/connector/oauth/authorize")
                        .param("response_type", "code")
                        .param("client_id", "mcp_client_stale")
                        .param("redirect_uri", "https://chatgpt.com/connector/oauth/callback")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256")
                        .param("scope", "connector:read"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }
}
