package com.jmj.trade.connector;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.jmj.trade.order.McpOrderExecutionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class ConnectorMcpProtocol {

    static final String PROTOCOL_VERSION = "2024-11-05";

    private final ConnectorService service;
    private final McpOrderExecutionService tradeService;
    private final ObjectMapper objectMapper;
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorMcpProtocol.class);

    public ConnectorMcpProtocol(ConnectorService service, ObjectMapper objectMapper) {
        this(service, (McpOrderExecutionService) null, objectMapper);
    }

    @Autowired
    ConnectorMcpProtocol(ConnectorService service, ObjectProvider<McpOrderExecutionService> tradeService,
                         ObjectMapper objectMapper) {
        this(service, tradeService.getIfAvailable(), objectMapper);
    }

    public ConnectorMcpProtocol(ConnectorService service, McpOrderExecutionService tradeService,
                                ObjectMapper objectMapper) {
        this.service = service;
        this.tradeService = tradeService;
        this.objectMapper = objectMapper;
    }

    ObjectNode handle(ObjectNode request, UUID userId, UUID connectionId) {
        return handle(request, userId, connectionId, false);
    }

    ObjectNode handle(ObjectNode request, UUID userId, UUID connectionId, boolean canTrade) {
        canTrade = canTrade && tradeService != null;
        if (request == null || !"2.0".equals(request.path("jsonrpc").asText(null))) {
            return error(request, -32600, "Invalid Request");
        }

        var method = request.path("method").asText(null);
        if (method == null) return error(request, -32600, "Invalid Request");

        var response = switch (method) {
            case "initialize" -> initialize(request, canTrade);
            case "notifications/initialized" -> null;
            case "ping" -> result(request, objectMapper.createObjectNode());
            case "tools/list" -> toolsList(request, canTrade);
            case "tools/call" -> toolsCall(request, userId, connectionId, canTrade);
            default -> error(request, -32601, "Method not found: " + method);
        };
        return request.has("id") ? response : null;
    }

    private ObjectNode initialize(ObjectNode request, boolean canTrade) {
        var result = objectMapper.createObjectNode();
        result.put("protocolVersion", negotiatedProtocolVersion(request));
        var capabilities = objectMapper.createObjectNode();
        capabilities.set("tools", objectMapper.createObjectNode().put("listChanged", false));
        result.set("capabilities", capabilities);
        result.put("instructions", canTrade
                ? "Use read tools to inspect the authenticated Toss Invest account. Trade tools require an explicit prepare_order then submit_order approval flow; stale or partial data blocks submission."
                : "Use these read-only tools to inspect the authenticated Toss Invest account. Treat stale or partial portfolio data as uncertain and never infer a trade from it.");
        result.set("serverInfo", objectMapper.createObjectNode()
                .put("name", "investment-os-toss")
                .put("version", "1.0.0"));
        return result(request, result);
    }

    private String negotiatedProtocolVersion(ObjectNode request) {
        var requested = request.path("params").path("protocolVersion").asText(null);
        if (requested == null) return PROTOCOL_VERSION;
        return switch (requested) {
            case "2025-06-18", "2025-03-26", "2024-11-05" -> requested;
            default -> PROTOCOL_VERSION;
        };
    }

    private ObjectNode toolsList(ObjectNode request, boolean canTrade) {
        var result = objectMapper.createObjectNode();
        var tools = result.putArray("tools");
        tools.add(tool(
                "get_portfolio",
                "Get portfolio",
                "Read the latest Toss Invest portfolio snapshot. If stale or partial is true, treat the data as uncertain and do not size or submit trades.",
                emptySchema(), objectSchema()));
        tools.add(tool(
                "get_orders",
                "Get orders",
                "Read Toss Invest broker orders. Use OPEN for working orders or CLOSED for completed and canceled orders.",
                ordersSchema(), listEnvelopeSchema("orders")));
        tools.add(tool(
                "get_recent_fills",
                "Get recent fills",
                "Read filled quantities derived from Toss Invest open and closed orders. Optionally filter by an ISO-8601 instant.",
                fillsSchema(), listEnvelopeSchema("fills")));
        if (canTrade) {
            tools.add(tool(
                    "prepare_order",
                    "Prepare order",
                    "Validate and prepare a Toss Invest order. This never sends an order to the broker; wait for explicit user approval before submit_order.",
                    prepareOrderSchema(), objectSchema(), false, false));
            tools.add(tool(
                    "submit_order",
                    "Submit approved order",
                    "Submit an explicitly approved prepared order by proposalId only. Never infer or reconstruct order fields.",
                    proposalIdSchema(), objectSchema(), false, true));
            tools.add(tool(
                    "cancel_order",
                    "Cancel order",
                    "Cancel a currently open Toss Invest order by brokerOrderId.",
                    brokerOrderIdSchema(), objectSchema(), false, true));
            tools.add(tool(
                    "get_order",
                    "Get order",
                    "Read the latest status of a Toss Invest order by brokerOrderId or clientOrderId.",
                    orderLookupSchema(), objectSchema(), true, false));
        }
        return result(request, result);
    }

    private ObjectNode toolsCall(ObjectNode request, UUID userId, UUID connectionId, boolean canTrade) {
        var params = request.path("params");
        var name = params.path("name").asText(null);
        if (name == null) return error(request, -32602, "Tool name is required");
        var started = System.nanoTime();

        try {
            var arguments = params.path("arguments");
            var response = switch (name) {
                case "get_portfolio" -> toolResult(request, service.portfolio(userId, connectionId));
                case "get_orders" -> toolResult(request, envelope("orders", service.orders(userId, connectionId,
                        optionalText(arguments, "group", "OPEN"))));
                case "get_recent_fills" -> toolResult(request, envelope("fills", service.fills(userId, connectionId,
                        optionalInstant(arguments, "since"))));
                case "prepare_order" -> canTrade
                        ? toolResult(request, tradeService.prepare(userId, connectionId, prepare(arguments)))
                        : forbiddenTrade(request);
                case "submit_order" -> canTrade
                        ? toolResult(request, tradeService.submit(userId, connectionId,
                                requiredText(arguments, "proposalId")))
                        : forbiddenTrade(request);
                case "cancel_order" -> canTrade
                        ? toolResult(request, tradeService.cancel(userId, connectionId,
                                requiredText(arguments, "brokerOrderId")))
                        : forbiddenTrade(request);
                case "get_order" -> canTrade
                        ? toolResult(request, getOrderLookup(arguments, userId, connectionId))
                        : forbiddenTrade(request);
                default -> error(request, -32601, "Tool not found: " + name);
            };
            LOG.atInfo().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", name)
                    .addKeyValue("outcome", "success")
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("MCP tool completed");
            return response;
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", name)
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("MCP tool failed");
            return toolError(request, "Connector tool failed");
        }
    }

    private ObjectNode toolResult(ObjectNode request, Object value) {
        var payload = objectMapper.createObjectNode();
        payload.put("isError", false);
        payload.set("structuredContent", objectMapper.valueToTree(value));
        var content = payload.putArray("content");
        var text = objectMapper.writeValueAsString(value);
        content.addObject().put("type", "text").put("text", text);
        return result(request, payload);
    }

    private ObjectNode toolError(ObjectNode request, String message) {
        var payload = objectMapper.createObjectNode();
        payload.put("isError", true);
        payload.putArray("content").addObject().put("type", "text").put("text", message);
        return result(request, payload);
    }

    private ObjectNode tool(String name, String title, String description,
                            ObjectNode schema, ObjectNode outputSchema) {
        return tool(name, title, description, schema, outputSchema, true, false);
    }

    private ObjectNode tool(String name, String title, String description,
                            ObjectNode schema, ObjectNode outputSchema,
                            boolean readOnly, boolean destructive) {
        var tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        tool.set("outputSchema", outputSchema);
        var annotations = tool.objectNode();
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", destructive);
        annotations.put("openWorldHint", false);
        tool.set("annotations", annotations);
        return tool;
    }

    private ObjectNode emptySchema() {
        return objectMapper.createObjectNode().put("type", "object")
                .set("properties", objectMapper.createObjectNode());
    }

    private ObjectNode ordersSchema() {
        var properties = objectMapper.createObjectNode();
        var group = objectMapper.createObjectNode().put("type", "string");
        group.putArray("enum").add("OPEN").add("CLOSED");
        group.put("default", "OPEN");
        properties.set("group", group);
        return objectMapper.createObjectNode().put("type", "object")
                .set("properties", properties);
    }

    private ObjectNode fillsSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("since", objectMapper.createObjectNode()
                .put("type", "string")
                .put("format", "date-time"));
        return objectMapper.createObjectNode().put("type", "object")
                .set("properties", properties);
    }

    private ObjectNode prepareOrderSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("symbol", objectMapper.createObjectNode().put("type", "string"));
        properties.set("side", enumSchema("BUY", "SELL"));
        properties.set("orderType", enumSchema("LIMIT", "MARKET"));
        properties.set("quantity", objectMapper.createObjectNode().put("type", "number").put("exclusiveMinimum", 0));
        properties.set("price", objectMapper.createObjectNode().put("type", "number").put("exclusiveMinimum", 0));
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("symbol").add("side").add("orderType").add("quantity");
        var variants = schema.putArray("oneOf");
        variants.add(requiredOrderVariant("LIMIT", true));
        variants.add(requiredOrderVariant("MARKET", false));
        return schema;
    }

    private ObjectNode requiredOrderVariant(String orderType, boolean requiresPrice) {
        var variant = objectMapper.createObjectNode();
        variant.set("properties", objectMapper.createObjectNode()
                .set("orderType", objectMapper.createObjectNode().put("const", orderType)));
        var required = variant.putArray("required").add("orderType");
        if (requiresPrice) required.add("price");
        else variant.set("not", objectMapper.createObjectNode().set("required", objectMapper.createArrayNode().add("price")));
        return variant;
    }

    private ObjectNode proposalIdSchema() {
        return requiredStringSchema("proposalId");
    }

    private ObjectNode brokerOrderIdSchema() {
        return requiredStringSchema("brokerOrderId");
    }

    private ObjectNode orderLookupSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("brokerOrderId", objectMapper.createObjectNode().put("type", "string"));
        properties.set("clientOrderId", objectMapper.createObjectNode().put("type", "string"));
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        var anyOf = schema.putArray("anyOf");
        anyOf.add(requiredStringSchema("brokerOrderId"));
        anyOf.add(requiredStringSchema("clientOrderId"));
        return schema;
    }

    private Object getOrderLookup(JsonNode arguments, UUID userId, UUID connectionId) {
        var brokerId = arguments.path("brokerOrderId");
        if (brokerId.isTextual() && !brokerId.asText().isBlank()) {
            return tradeService.getOrder(userId, connectionId, brokerId.asText());
        }
        return tradeService.getOrderByClientId(userId, connectionId, requiredText(arguments, "clientOrderId"));
    }

    private ObjectNode requiredStringSchema(String name) {
        var properties = objectMapper.createObjectNode();
        properties.set(name, objectMapper.createObjectNode().put("type", "string"));
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add(name);
        return schema;
    }

    private ObjectNode enumSchema(String... values) {
        var schema = objectMapper.createObjectNode().put("type", "string");
        var array = schema.putArray("enum");
        for (var value : values) array.add(value);
        return schema;
    }

    private ObjectNode objectSchema() {
        return objectMapper.createObjectNode().put("type", "object");
    }

    private ObjectNode listEnvelopeSchema(String field) {
        var properties = objectMapper.createObjectNode();
        properties.set(field, arraySchema());
        var schema = objectMapper.createObjectNode()
                .put("type", "object")
                .set("properties", properties);
        schema.putArray("required").add(field);
        return schema;
    }

    private ObjectNode arraySchema() {
        return objectMapper.createObjectNode()
                .put("type", "array")
                .set("items", objectMapper.createObjectNode().put("type", "object"));
    }

    private ObjectNode envelope(String field, Object value) {
        return objectMapper.createObjectNode().set(field, objectMapper.valueToTree(value));
    }

    private static String optionalText(JsonNode arguments, String field, String fallback) {
        var value = arguments.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static Instant optionalInstant(JsonNode arguments, String field) {
        var value = arguments.path(field);
        return value.isTextual() && !value.asText().isBlank() ? Instant.parse(value.asText()) : null;
    }

    private static McpOrderExecutionService.PrepareCommand prepare(JsonNode arguments) {
        return new McpOrderExecutionService.PrepareCommand(
                requiredText(arguments, "symbol"),
                requiredText(arguments, "side"),
                requiredText(arguments, "orderType"),
                decimal(arguments, "quantity"),
                optionalDecimal(arguments, "price"));
    }

    private static String requiredText(JsonNode arguments, String field) {
        var value = arguments.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private static java.math.BigDecimal decimal(JsonNode arguments, String field) {
        var value = arguments.path(field);
        if (!value.isNumber()) throw new IllegalArgumentException(field + " is required");
        return value.decimalValue();
    }

    private static java.math.BigDecimal optionalDecimal(JsonNode arguments, String field) {
        var value = arguments.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private ObjectNode forbiddenTrade(ObjectNode request) {
        return toolError(request, "Connector trade scope required");
    }

    private ObjectNode result(ObjectNode request, JsonNode result) {
        var response = baseResponse(request);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(ObjectNode request, int code, String message) {
        var response = baseResponse(request);
        response.set("error", objectMapper.createObjectNode().put("code", code).put("message", message));
        return response;
    }

    private ObjectNode baseResponse(ObjectNode request) {
        var response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (request != null && request.has("id")) response.set("id", request.get("id"));
        return response;
    }
}
