package com.jmj.trade.connector;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.jmj.trade.order.McpOrderExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorMcpProtocolTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    private final ConnectorService service = mock(ConnectorService.class);
    private final McpOrderExecutionService tradeService = mock(McpOrderExecutionService.class);
    private final ConnectorMcpProtocol protocol = new ConnectorMcpProtocol(service, tradeService, new ObjectMapper());

    @Test
    void initializesWithToolsCapability() throws Exception {
        var response = protocol.handle(request("1", "initialize", "{}"), USER, CONNECTION);

        assertThat(response.path("result").path("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(response.path("result").path("capabilities").path("tools").isObject()).isTrue();
        assertThat(response.path("result").path("instructions").asText()).contains("read-only");
    }

    @Test
    void listsReadOnlyInvestmentToolsWithInputSchemas() throws Exception {
        var response = protocol.handle(request("2", "tools/list", "{}"), USER, CONNECTION);

        var tools = response.path("result").path("tools");
        assertThat(tools.size()).isEqualTo(3);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("get_portfolio");
        assertThat(tools.get(1).path("name").asText()).isEqualTo("get_orders");
        assertThat(tools.get(2).path("name").asText()).isEqualTo("get_recent_fills");
        assertThat(tools.get(1).path("inputSchema").path("type").asText()).isEqualTo("object");
        assertThat(tools.get(0).path("title").asText()).isEqualTo("Get portfolio");
        assertThat(tools.get(0).path("outputSchema").path("type").asText()).isEqualTo("object");
        assertThat(tools.get(1).path("outputSchema").path("type").asText()).isEqualTo("object");
        assertThat(tools.get(1).path("outputSchema").path("properties").path("orders")
                .path("type").asText()).isEqualTo("array");
        assertThat(tools.get(1).path("outputSchema").path("required").toString()).contains("orders");
        assertThat(tools.get(2).path("outputSchema").path("type").asText()).isEqualTo("object");
        assertThat(tools.get(2).path("outputSchema").path("properties").path("fills")
                .path("type").asText()).isEqualTo("array");
        assertThat(tools.get(2).path("outputSchema").path("required").toString()).contains("fills");
    }

    @Test
    void listsTradeToolsOnlyWhenTradeScopeIsGranted() throws Exception {
        var response = protocol.handle(request("trade", "tools/list", "{}"), USER, CONNECTION, true);

        var tools = response.path("result").path("tools");
        assertThat(tools.size()).isEqualTo(7);
        assertThat(tools.get(3).path("name").asText()).isEqualTo("prepare_order");
        assertThat(tools.get(4).path("name").asText()).isEqualTo("submit_order");
        assertThat(tools.get(5).path("name").asText()).isEqualTo("cancel_order");
        assertThat(tools.get(6).path("name").asText()).isEqualTo("get_order");
        assertThat(tools.get(3).path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(tools.get(4).path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(tools.get(4).path("inputSchema").path("required").toString()).contains("proposalId");
    }

    @Test
    void listsTradeToolsForTradeScopeWhenLiveExecutionIsDisabled() throws Exception {
        var disabledProtocol = new ConnectorMcpProtocol(service, (McpOrderExecutionService) null,
                new ObjectMapper());

        var response = disabledProtocol.handle(request("trade-disabled", "tools/list", "{}"),
                USER, CONNECTION, true);

        var tools = response.path("result").path("tools");
        assertThat(tools.size()).isEqualTo(7);
        assertThat(tools.get(3).path("name").asText()).isEqualTo("prepare_order");
        assertThat(tools.get(4).path("name").asText()).isEqualTo("submit_order");
        assertThat(tools.get(5).path("name").asText()).isEqualTo("cancel_order");
        assertThat(tools.get(6).path("name").asText()).isEqualTo("get_order");
    }

    @ParameterizedTest
    @MethodSource("tradeToolCalls")
    void reportsLiveExecutionDisabledWhenTradeToolIsVisibleButUnavailable(String toolName, String arguments)
            throws Exception {
        var disabledProtocol = new ConnectorMcpProtocol(service, (McpOrderExecutionService) null,
                new ObjectMapper());

        var response = disabledProtocol.handle(toolCall("trade-disabled-call", toolName, arguments),
                USER, CONNECTION, true);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .contains("Live order execution is disabled");
    }

    private static Stream<Arguments> tradeToolCalls() {
        return Stream.of(
                Arguments.of("prepare_order",
                        "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"quantity\":1,\"price\":180}"),
                Arguments.of("submit_order", "{\"proposalId\":\"ordp_018f0000-0000-7000-8000-000000000001\"}"),
                Arguments.of("cancel_order", "{\"brokerOrderId\":\"broker-1\"}"),
                Arguments.of("get_order", "{\"brokerOrderId\":\"broker-1\"}"));
    }

    @Test
    void readScopeCannotCallTradeTool() throws Exception {
        var response = protocol.handle(toolCall("trade-read", "submit_order",
                "{\"proposalId\":\"ordp_018f0000-0000-7000-8000-000000000001\"}"), USER, CONNECTION);
        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        verify(tradeService, org.mockito.Mockito.never()).submit(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void callsPortfolioToolUsingAuthenticatedConnection() throws Exception {
        when(service.portfolio(USER, CONNECTION)).thenReturn(new ConnectorResponse.Portfolio(
                Instant.parse("2026-09-17T00:00:00Z"), false, false, List.of(), List.of(),
                Map.of("USD", new BigDecimal("1000")), List.of()));

        var response = protocol.handle(toolCall("3", "get_portfolio", "{}"), USER, CONNECTION);
        var text = response.path("result").path("content").get(0).path("text").asText();

        assertThat(text).contains("\"stale\":false", "\"buyingPower\"");
        assertThat(response.path("result").path("structuredContent").path("stale").asBoolean())
                .isFalse();
        verify(service).portfolio(USER, CONNECTION);
    }

    @Test
    void callsOrdersAndFillsToolsWithValidatedArguments() throws Exception {
        when(service.orders(USER, CONNECTION, "CLOSED")).thenReturn(List.of());
        when(service.fills(USER, CONNECTION, Instant.parse("2026-09-01T00:00:00Z"))).thenReturn(List.of());

        var ordersResponse = protocol.handle(
                toolCall("4", "get_orders", "{\"group\":\"CLOSED\"}"), USER, CONNECTION);
        var fillsResponse = protocol.handle(
                toolCall("5", "get_recent_fills", "{\"since\":\"2026-09-01T00:00:00Z\"}"), USER, CONNECTION);

        assertThat(ordersResponse.path("result").path("structuredContent").isObject()).isTrue();
        assertThat(ordersResponse.path("result").path("structuredContent").path("orders").isArray())
                .isTrue();
        assertThat(ordersResponse.path("result").path("content").get(0).path("text").asText())
                .isEqualTo("{\"orders\":[]}");
        assertThat(fillsResponse.path("result").path("structuredContent").isObject()).isTrue();
        assertThat(fillsResponse.path("result").path("structuredContent").path("fills").isArray())
                .isTrue();
        assertThat(fillsResponse.path("result").path("content").get(0).path("text").asText())
                .isEqualTo("{\"fills\":[]}");

        verify(service).orders(USER, CONNECTION, "CLOSED");
        verify(service).fills(USER, CONNECTION, Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void returnsJsonRpcErrorForUnknownMethodAndIgnoresNotifications() throws Exception {
        var unknown = protocol.handle(request("6", "unknown/method", "{}"), USER, CONNECTION);
        var notification = protocol.handle(request(null, "notifications/initialized", "{}"), USER, CONNECTION);

        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(notification).isNull();
    }

    private static ObjectNode request(String id, String method, String params)
            throws Exception {
        var objectMapper = new ObjectMapper();
        var request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        if (id != null) request.put("id", id);
        request.put("method", method);
        request.set("params", objectMapper.readTree(params));
        return request;
    }

    private static ObjectNode toolCall(String id, String name, String arguments)
            throws Exception {
        var request = request(id, "tools/call", "{}");
        var params = new ObjectMapper().createObjectNode();
        params.put("name", name);
        params.set("arguments", new ObjectMapper().readTree(arguments));
        request.set("params", params);
        return request;
    }
}
