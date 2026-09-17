package com.jmj.trade.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
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
    private final ObjectMapper objectMapper;

    public ConnectorMcpProtocol(ConnectorService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    ObjectNode handle(ObjectNode request, UUID userId, UUID connectionId) {
        if (request == null || !"2.0".equals(request.path("jsonrpc").asText(null))) {
            return error(request, -32600, "Invalid Request");
        }

        var method = request.path("method").asText(null);
        if (method == null) return error(request, -32600, "Invalid Request");

        var response = switch (method) {
            case "initialize" -> initialize(request);
            case "notifications/initialized" -> null;
            case "ping" -> result(request, objectMapper.createObjectNode());
            case "tools/list" -> toolsList(request);
            case "tools/call" -> toolsCall(request, userId, connectionId);
            default -> error(request, -32601, "Method not found: " + method);
        };
        return request.has("id") ? response : null;
    }

    private ObjectNode initialize(ObjectNode request) {
        var result = objectMapper.createObjectNode();
        result.put("protocolVersion", negotiatedProtocolVersion(request));
        var capabilities = objectMapper.createObjectNode();
        capabilities.set("tools", objectMapper.createObjectNode().put("listChanged", false));
        result.set("capabilities", capabilities);
        result.put("instructions",
                "Use these read-only tools to inspect the authenticated Toss Invest account. "
                        + "Treat stale or partial portfolio data as uncertain and never infer a trade from it.");
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

    private ObjectNode toolsList(ObjectNode request) {
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
                ordersSchema(), arraySchema()));
        tools.add(tool(
                "get_recent_fills",
                "Get recent fills",
                "Read filled quantities derived from Toss Invest open and closed orders. Optionally filter by an ISO-8601 instant.",
                fillsSchema(), arraySchema()));
        return result(request, result);
    }

    private ObjectNode toolsCall(ObjectNode request, UUID userId, UUID connectionId) {
        var params = request.path("params");
        var name = params.path("name").asText(null);
        if (name == null) return error(request, -32602, "Tool name is required");

        try {
            var arguments = params.path("arguments");
            return switch (name) {
                case "get_portfolio" -> toolResult(request, service.portfolio(userId, connectionId));
                case "get_orders" -> toolResult(request, service.orders(userId, connectionId,
                        optionalText(arguments, "group", "OPEN")));
                case "get_recent_fills" -> toolResult(request, service.fills(userId, connectionId,
                        optionalInstant(arguments, "since")));
                default -> error(request, -32601, "Tool not found: " + name);
            };
        } catch (RuntimeException exception) {
            return toolError(request, "Connector read failed");
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
        var tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        tool.set("outputSchema", outputSchema);
        var annotations = tool.objectNode();
        annotations.put("readOnlyHint", true);
        annotations.put("destructiveHint", false);
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

    private ObjectNode objectSchema() {
        return objectMapper.createObjectNode().put("type", "object");
    }

    private ObjectNode arraySchema() {
        return objectMapper.createObjectNode()
                .put("type", "array")
                .set("items", objectMapper.createObjectNode().put("type", "object"));
    }

    private static String optionalText(JsonNode arguments, String field, String fallback) {
        var value = arguments.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static Instant optionalInstant(JsonNode arguments, String field) {
        var value = arguments.path(field);
        return value.isTextual() && !value.asText().isBlank() ? Instant.parse(value.asText()) : null;
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
