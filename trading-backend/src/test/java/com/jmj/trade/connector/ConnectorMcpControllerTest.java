package com.jmj.trade.connector;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConnectorMcpControllerTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    @Test
    void sseRouteStartsForAnAuthenticatedConnectorKey() throws Exception {
        MockMvc mvc = standaloneSetup(new ConnectorMcpController(mock(ConnectorMcpProtocol.class))).build();

        mvc.perform(get("/api/v1/connector/mcp/sse").principal(authentication()))
                .andExpect(request().asyncStarted());
    }

    @Test
    void messageRouteRejectsUnknownSessions() throws Exception {
        MockMvc mvc = standaloneSetup(new ConnectorMcpController(mock(ConnectorMcpProtocol.class))).build();

        mvc.perform(post("/api/v1/connector/mcp/messages")
                        .principal(authentication())
                        .param("sessionId", "missing")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isGone());
    }

    private static TestingAuthenticationToken authentication() {
        var token = new TestingAuthenticationToken(USER, null, "SCOPE_CONNECTOR_READ");
        token.setDetails(new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), USER, CONNECTION, null));
        return token;
    }
}
