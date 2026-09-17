package com.jmj.trade.connector;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorMcpProtocolTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    private final ConnectorService service = mock(ConnectorService.class);
    private final ConnectorMcpProtocol protocol = new ConnectorMcpProtocol(service, new ObjectMapper());

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
        assertThat(tools.get(1).path("outputSchema").path("type").asText()).isEqualTo("array");
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

        protocol.handle(toolCall("4", "get_orders", "{\"group\":\"CLOSED\"}"), USER, CONNECTION);
        protocol.handle(toolCall("5", "get_recent_fills", "{\"since\":\"2026-09-01T00:00:00Z\"}"), USER, CONNECTION);

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
