package com.jmj.trade.connector;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConnectorMcpControllerTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    @Test
    void sseRouteStartsForAnAuthenticatedConnectorKey() throws Exception {
        MockMvc mvc = standaloneSetup(new ConnectorMcpController(
                mock(ConnectorMcpProtocol.class), "https://dashboard.example")).build();

        mvc.perform(get("/api/v1/connector/mcp/sse").principal(authentication()))
                .andExpect(request().asyncStarted());
    }

    @Test
    void messageRouteRejectsUnknownSessions() throws Exception {
        MockMvc mvc = standaloneSetup(new ConnectorMcpController(
                mock(ConnectorMcpProtocol.class), "https://dashboard.example")).build();

        mvc.perform(post("/api/v1/connector/mcp/messages")
                        .principal(authentication())
                        .param("sessionId", "missing")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isGone());
    }

    @Test
    void streamableHttpRouteReturnsJsonRpcResponse() throws Exception {
        var protocol = mock(ConnectorMcpProtocol.class);
        var response = new ObjectMapper().createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .set("result", new ObjectMapper().createObjectNode());
        when(protocol.handle(org.mockito.ArgumentMatchers.any(ObjectNode.class),
                org.mockito.ArgumentMatchers.eq(USER),
                org.mockito.ArgumentMatchers.eq(CONNECTION))).thenReturn(response);
        MockMvc mvc = standaloneSetup(new ConnectorMcpController(
                protocol, "https://dashboard.example")).build();

        mvc.perform(post("/api/v1/connector/mcp")
                        .principal(authentication())
                        .contentType("application/json")
                        .accept("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.result").exists());
    }

    private static TestingAuthenticationToken authentication() {
        var token = new TestingAuthenticationToken(USER, null, "SCOPE_CONNECTOR_READ");
        token.setDetails(new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), USER, CONNECTION, null));
        return token;
    }
}
