package com.jmj.trade.connector;

import com.jmj.trade.investment.InvestmentRiskPreflightService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void streamableHttpPreservesExactDecimalScaleAndMaximumQuantity() throws Exception {
        var mapper = new ObjectMapper();
        var preflight = mock(InvestmentRiskPreflightService.class);
        when(preflight.preflight(eq(USER), any(InvestmentRiskPreflightService.PreflightInput.class)))
                .thenAnswer(invocation -> {
                    var input = (InvestmentRiskPreflightService.PreflightInput) invocation.getArgument(1);
                    return new InvestmentRiskPreflightService.PreflightView(input.ticker(), input.quantity(),
                            false, List.of("TEST_BLOCKER"), null, null, null, null, Map.of(), Map.of());
                });
        var protocol = new ConnectorMcpProtocol(null, null, null, null, null,
                null, preflight, mapper);
        var mvc = standaloneSetup(new ConnectorMcpController(
                protocol, "https://dashboard.example", mapper)).build();

        assertPreflightQuantity(mvc, mapper, "1.2500", new BigDecimal("1.2500"));
        assertPreflightQuantity(mvc, mapper, "999999999999999999.9999999999",
                new BigDecimal("999999999999999999.9999999999"));

        verify(preflight).preflight(USER,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", new BigDecimal("1.2500")));
        verify(preflight).preflight(USER, new InvestmentRiskPreflightService.PreflightInput("AAPL",
                new BigDecimal("999999999999999999.9999999999")));
    }

    @Test
    void malformedAndNonObjectBodiesReturnSanitizedBadRequestForHttpAndSseMessages() throws Exception {
        var mvc = standaloneSetup(new ConnectorMcpController(
                mock(ConnectorMcpProtocol.class), "https://dashboard.example")).build();

        mvc.perform(post("/api/v1/connector/mcp")
                        .principal(authentication())
                        .contentType("application/json")
                        .content("{\"secret\":\"not-to-echo\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_MCP_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));

        mvc.perform(post("/api/v1/connector/mcp/messages")
                        .principal(authentication())
                        .param("sessionId", "missing")
                        .contentType("application/json")
                        .content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_MCP_REQUEST"));

        mvc.perform(post("/api/v1/connector/mcp")
                        .principal(authentication())
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\"} {}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_MCP_REQUEST"));
    }

    private static void assertPreflightQuantity(MockMvc mvc, ObjectMapper mapper, String quantity,
                                               BigDecimal expected) throws Exception {
        var request = "{\"jsonrpc\":\"2.0\",\"id\":\"quantity\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"preflight_investment_risk\",\"arguments\":{"
                + "\"ticker\":\"AAPL\",\"quantity\":" + quantity + "}}}";
        var response = mapper.reader(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(mvc.perform(post("/api/v1/connector/mcp")
                        .principal(authentication())
                        .contentType("application/json")
                        .accept("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var actual = response.path("result").path("structuredContent").path("quantity").decimalValue();
        assertThat(actual).isEqualTo(expected);
    }

    private static TestingAuthenticationToken authentication() {
        var token = new TestingAuthenticationToken(USER, null, "SCOPE_CONNECTOR_READ");
        token.setDetails(new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), USER, CONNECTION, null));
        return token;
    }
}
