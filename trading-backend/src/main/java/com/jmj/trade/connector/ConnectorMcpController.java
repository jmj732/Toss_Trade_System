package com.jmj.trade.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
@RequestMapping("/api/v1/connector/mcp")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class ConnectorMcpController {

    private static final String MESSAGE_PATH = "/api/v1/connector/mcp/messages";

    private final ConnectorMcpProtocol protocol;
    private final ObjectReader requestReader;
    private final ObjectMapper objectMapper;
    private final String publicDashboardUrl;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    public ConnectorMcpController(
            ConnectorMcpProtocol protocol,
            @org.springframework.beans.factory.annotation.Value("${public.dashboard-url:http://localhost:3000}")
            String publicDashboardUrl
    ) {
        this(protocol, publicDashboardUrl, new ObjectMapper());
    }

    @Autowired
    public ConnectorMcpController(
            ConnectorMcpProtocol protocol,
            @org.springframework.beans.factory.annotation.Value("${public.dashboard-url:http://localhost:3000}")
            String publicDashboardUrl,
            ObjectMapper objectMapper
    ) {
        this.protocol = protocol;
        this.objectMapper = objectMapper;
        this.requestReader = objectMapper.reader(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.publicDashboardUrl = trimTrailingSlash(publicDashboardUrl);
    }

    /**
     * Streamable HTTP transport used by current ChatGPT MCP connections.
     * The connector key binds every request to the user's active broker connection,
     * so the endpoint can remain stateless and does not need an MCP session id.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ObjectNode> mcp(
            @RequestBody byte[] requestBody,
            Authentication authentication
    ) {
        var request = parseRequest(requestBody);
        var key = key(authentication);
        var response = handle(request, key);
        if (response == null) return ResponseEntity.accepted().build();
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter sse(Authentication authentication) {
        var key = key(authentication);
        var sessionId = UUID.randomUUID().toString();
        var emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        var session = new Session(key.userId(), key.connectionId(), emitter);
        sessions.put(sessionId, session);
        var cleanup = (Runnable) () -> sessions.remove(sessionId, session);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        try {
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data(publicDashboardUrl + MESSAGE_PATH + "?sessionId=" + sessionId));
        } catch (IOException exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @PostMapping(value = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> message(
            @RequestParam String sessionId,
            @RequestBody byte[] requestBody,
            Authentication authentication
    ) {
        var request = parseRequest(requestBody);
        var session = sessions.get(sessionId);
        if (session == null) return ResponseEntity.status(410).build();

        var key = key(authentication);
        if (!session.belongsTo(key)) return ResponseEntity.status(403).build();

        var response = handle(request, key);
        if (response == null) return ResponseEntity.accepted().build();
        try {
            session.emitter().send(SseEmitter.event().name("message").data(response));
            return ResponseEntity.accepted().build();
        } catch (IOException exception) {
            sessions.remove(sessionId, session);
            return ResponseEntity.status(410).build();
        }
    }

    private static ConnectorApiKeyService.AuthenticatedKey key(Authentication authentication) {
        if (authentication == null
                || !(authentication.getDetails() instanceof ConnectorApiKeyService.AuthenticatedKey key)) {
            throw new org.springframework.security.authentication.BadCredentialsException("connector key required");
        }
        return key;
    }

    @ExceptionHandler(MalformedMcpRequest.class)
    ResponseEntity<ObjectNode> malformedRequest() {
        return ResponseEntity.badRequest()
                .body(objectMapper.createObjectNode().put("errorCode", "MALFORMED_MCP_REQUEST"));
    }

    private ObjectNode parseRequest(byte[] requestBody) {
        try {
            var request = requestReader.readTree(requestBody);
            if (request instanceof ObjectNode object) return object;
        } catch (JacksonException exception) {
            throw new MalformedMcpRequest();
        }
        throw new MalformedMcpRequest();
    }

    private ObjectNode handle(ObjectNode request, ConnectorApiKeyService.AuthenticatedKey key) {
        return key.canTrade()
                ? protocol.handle(request, key.userId(), key.connectionId(), key.id(), true)
                : protocol.handle(request, key.userId(), key.connectionId());
    }

    private record Session(UUID userId, UUID connectionId, SseEmitter emitter) {
        boolean belongsTo(ConnectorApiKeyService.AuthenticatedKey key) {
            return userId.equals(key.userId()) && connectionId.equals(key.connectionId());
        }
    }

    private static final class MalformedMcpRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static String trimTrailingSlash(String value) {
        var normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
