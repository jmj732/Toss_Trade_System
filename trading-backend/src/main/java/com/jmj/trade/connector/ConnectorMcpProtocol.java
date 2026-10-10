package com.jmj.trade.connector;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.jmj.trade.order.McpOrderExecutionService;
import com.jmj.trade.order.LiveOrderActivationException;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.investment.InvestmentException;
import com.jmj.trade.investment.InvestmentReviewService;
import com.jmj.trade.investment.InvestmentThesisVerificationService;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class ConnectorMcpProtocol {

    static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Pattern CONTEXT_TICKER = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private final ConnectorService service;
    private final McpOrderExecutionService tradeService;
    private final InvestmentContextService investmentContextService;
    private final InvestmentReviewService investmentReviewService;
    private final InvestmentThesisVerificationService thesisVerificationService;
    private final ObjectMapper objectMapper;
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorMcpProtocol.class);

    public ConnectorMcpProtocol(ConnectorService service, ObjectMapper objectMapper) {
        this(service, (McpOrderExecutionService) null, (InvestmentContextService) null,
                (InvestmentReviewService) null, (InvestmentThesisVerificationService) null, objectMapper);
    }

    @Autowired
    ConnectorMcpProtocol(ConnectorService service, ObjectProvider<McpOrderExecutionService> tradeService,
                         ObjectProvider<InvestmentContextService> investmentContextService,
                         ObjectProvider<InvestmentReviewService> investmentReviewService,
                         ObjectProvider<InvestmentThesisVerificationService> thesisVerificationService,
                         ObjectMapper objectMapper) {
        this(service, tradeService.getIfAvailable(), investmentContextService.getIfAvailable(),
                investmentReviewService.getIfAvailable(), thesisVerificationService.getIfAvailable(), objectMapper);
    }

    public ConnectorMcpProtocol(ConnectorService service, McpOrderExecutionService tradeService,
                                ObjectMapper objectMapper) {
        this(service, tradeService, null, null, null, objectMapper);
    }

    public ConnectorMcpProtocol(ConnectorService service, McpOrderExecutionService tradeService,
                                InvestmentContextService investmentContextService, ObjectMapper objectMapper) {
        this(service, tradeService, investmentContextService, null, null, objectMapper);
    }

    public ConnectorMcpProtocol(ConnectorService service, McpOrderExecutionService tradeService,
                                InvestmentContextService investmentContextService,
                                InvestmentReviewService investmentReviewService, ObjectMapper objectMapper) {
        this(service, tradeService, investmentContextService, investmentReviewService, null, objectMapper);
    }

    public ConnectorMcpProtocol(ConnectorService service, McpOrderExecutionService tradeService,
                                InvestmentContextService investmentContextService,
                                InvestmentReviewService investmentReviewService,
                                InvestmentThesisVerificationService thesisVerificationService,
                                ObjectMapper objectMapper) {
        this.service = service;
        this.tradeService = tradeService;
        this.investmentContextService = investmentContextService;
        this.investmentReviewService = investmentReviewService;
        this.thesisVerificationService = thesisVerificationService;
        this.objectMapper = objectMapper;
    }

    ObjectNode handle(ObjectNode request, UUID userId, UUID connectionId) {
        return handle(request, userId, connectionId, null, false);
    }

    ObjectNode handle(ObjectNode request, UUID userId, UUID connectionId, boolean canTrade) {
        return handle(request, userId, connectionId, null, canTrade);
    }

    ObjectNode handle(ObjectNode request, UUID userId, UUID connectionId,
                      UUID authenticatedApiKeyId, boolean canTrade) {
        var tradeScopeGranted = canTrade;
        var liveExecutionAvailable = tradeScopeGranted && tradeService != null;
        if (request == null || !"2.0".equals(request.path("jsonrpc").asText(null))) {
            return error(request, -32600, "Invalid Request");
        }

        var method = request.path("method").asText(null);
        if (method == null) return error(request, -32600, "Invalid Request");

        var response = switch (method) {
            case "initialize" -> initialize(request, tradeScopeGranted, liveExecutionAvailable);
            case "notifications/initialized" -> null;
            case "ping" -> result(request, objectMapper.createObjectNode());
            case "tools/list" -> toolsList(request, tradeScopeGranted);
            case "tools/call" -> toolsCall(request, userId, connectionId, authenticatedApiKeyId,
                    tradeScopeGranted, liveExecutionAvailable);
            default -> error(request, -32601, "Method not found: " + method);
        };
        return request.has("id") ? response : null;
    }

    private ObjectNode initialize(ObjectNode request, boolean tradeScopeGranted, boolean liveExecutionAvailable) {
        var result = objectMapper.createObjectNode();
        result.put("protocolVersion", negotiatedProtocolVersion(request));
        var capabilities = objectMapper.createObjectNode();
        capabilities.set("tools", objectMapper.createObjectNode().put("listChanged", false));
        result.set("capabilities", capabilities);
        result.put("instructions", instructions(tradeScopeGranted, liveExecutionAvailable));
        result.set("serverInfo", objectMapper.createObjectNode()
                .put("name", "investment-os-toss")
                .put("version", "1.0.0"));
        return result(request, result);
    }

    private static String instructions(boolean tradeScopeGranted, boolean liveExecutionAvailable) {
        if (!tradeScopeGranted) {
            return "Use these read-only tools to inspect the authenticated Toss Invest account. "
                    + "Treat stale or partial portfolio data as uncertain and never infer a trade from it.";
        }
        if (!liveExecutionAvailable) {
            return "Trade tools are visible because connector:trade is granted, but live order execution "
                    + "is disabled by server configuration. Calls will fail until REAL_ORDER_ENABLED=true.";
        }
        return "Use read tools to inspect the authenticated Toss Invest account. Trade tools require an explicit "
                + "prepare_order then submit_order approval flow; stale or partial data blocks submission.";
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
        tools.add(tool(
                "get_order",
                "Get order",
                "Read the latest status of a Toss Invest order by brokerOrderId or clientOrderId.",
                orderLookupSchema(), objectSchema()));
        tools.add(tool(
                "get_investment_context",
                "Get investment context",
                "Read the authenticated user's persisted investment context, including portfolio, security data, risk, thesis, decisions, review notes (reviewLog), and tactical overlays. This tool does not call providers or write data. Optionally filter only the securities array by ticker.",
                investmentContextSchema(), investmentContextOutputSchema()));
        if (thesisVerificationService != null) {
            tools.add(tool("get_investment_thesis_verification_context", "Get thesis verification context",
                    "Read the authenticated user's current thesis revision, verification policy, trusted price view, and latest verification event for one ticker. This tool does not call providers or write data.",
                    verificationContextSchema(), verificationContextOutputSchema()));
        }
        if (canTrade) {
            if (investmentContextService != null) {
                tools.add(tool("put_investment_thesis", "Save investment thesis",
                        "Store an externally authored proposal in PostgreSQL for Context and Sheet mirroring. Cannot set CONFIRMED or overwrite a confirmed thesis. Supply expectedUpdatedAt when updating an existing proposal; omit it only for creation. Never invent evidence or risk prices. Does not submit orders. Requires existing connector write scope.",
                        investmentThesisSchema(), objectSchema(), false, false, false,
                        ConnectorApiKeyService.TRADE_SCOPE));
                tools.add(tool("append_investment_decision", "Append investment decision record",
                        "Append a caller-authored decision record to PostgreSQL for Context and Sheet mirroring. Uses the supplied decisionId for idempotent retries; a different record with that ID conflicts. Stores supplied fields without generating investment judgments and never prepares or submits orders. Requires existing connector write scope; available even when order execution is disabled.",
                        investmentDecisionSchema(), objectSchema(), false, false, true,
                        ConnectorApiKeyService.TRADE_SCOPE));
            }
            if (thesisVerificationService != null) {
                tools.add(tool("verify_investment_thesis", "Verify investment thesis",
                        "Store an externally authored verification against the current thesis revision and the configured policy. The server applies policy; it does not call a model, infer evidence, or submit orders. Requires the existing connector trade scope.",
                        thesisVerificationSchema(), verificationResultOutputSchema(), false, false, true,
                        ConnectorApiKeyService.TRADE_SCOPE));
            }
            if (investmentReviewService != null) {
                tools.add(tool("append_investment_review", "Append investment review note",
                        "Append a caller-authored review/audit note to PostgreSQL for Context and Sheet mirroring. A review note records only that something was reviewed and what was observed; it does not create an investment decision, change a thesis, or prepare or submit an order. Raw values such as rawAction (e.g. REVIEW) and rawPriceSession are stored verbatim; priceSession is derived only from an exact supported value and is otherwise null. Uses recordKey for idempotent retries; a different record with the same recordKey conflicts. Requires existing connector write scope; available even when order execution is disabled.",
                        investmentReviewSchema(), objectSchema(), false, false, true,
                        ConnectorApiKeyService.TRADE_SCOPE));
            }
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
        }
        return result(request, result);
    }

    private ObjectNode toolsCall(ObjectNode request, UUID userId, UUID connectionId, UUID authenticatedApiKeyId,
                                 boolean tradeScopeGranted, boolean liveExecutionAvailable) {
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
                case "get_order" -> toolResult(request, service.order(userId, connectionId,
                        optionalText(arguments, "brokerOrderId", null),
                        optionalText(arguments, "clientOrderId", null)));
                case "get_investment_context" -> investmentContext(request, userId, arguments);
                case "get_investment_thesis_verification_context" ->
                        investmentThesisVerificationContext(request, userId, arguments);
                case "verify_investment_thesis" -> !tradeScopeGranted
                        ? forbiddenTrade(request)
                        : verifyInvestmentThesis(request, userId, authenticatedApiKeyId, arguments);
                case "put_investment_thesis" -> !tradeScopeGranted
                        ? forbiddenTrade(request) : putInvestmentThesis(request, userId, arguments);
                case "append_investment_decision" -> !tradeScopeGranted
                        ? forbiddenTrade(request) : appendInvestmentDecision(request, userId, arguments);
                case "append_investment_review" -> !tradeScopeGranted
                        ? forbiddenTrade(request) : appendInvestmentReview(request, userId, arguments);
                case "prepare_order" -> !tradeScopeGranted
                        ? forbiddenTrade(request)
                        : !liveExecutionAvailable
                        ? liveOrderExecutionDisabled(request)
                        : toolResult(request, tradeService.prepare(userId, connectionId, prepare(arguments)));
                case "submit_order" -> !tradeScopeGranted
                        ? forbiddenTrade(request)
                        : !liveExecutionAvailable
                        ? liveOrderExecutionDisabled(request)
                        : toolResult(request, tradeService.submit(userId, connectionId,
                                requiredText(arguments, "proposalId")));
                case "cancel_order" -> !tradeScopeGranted
                        ? forbiddenTrade(request)
                        : !liveExecutionAvailable
                        ? liveOrderExecutionDisabled(request)
                        : toolResult(request, tradeService.cancel(userId, connectionId,
                                requiredText(arguments, "brokerOrderId")));
                default -> error(request, -32601, "Tool not found: " + name);
            };
            var outcome = response != null && response.path("result").path("isError").asBoolean()
                    ? "failure" : "success";
            LOG.atInfo().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", name)
                    .addKeyValue("outcome", outcome)
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("MCP tool completed");
            return response;
        } catch (RuntimeException exception) {
            var builder = LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", name)
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000);
            if (exception instanceof LiveOrderActivationException activationException) {
                builder.addKeyValue("error_code", activationException.code().name())
                        .addKeyValue("reason", activationException.getMessage());
            }
            builder.log("MCP tool failed");
            if (exception instanceof LiveOrderActivationException activationException) {
                return toolError(request, "ORDER_BLOCKED", activationException.getMessage(), false, false);
            }
            if (exception instanceof BrokerException brokerException) {
                return toolError(request, "TOSS_API_ERROR", "Toss API request failed",
                        brokerException.isRetriable(), false);
            }
            if (exception instanceof IllegalArgumentException) {
                return toolError(request, "INVALID_ARGUMENT", exception.getMessage(), false, false);
            }
            return toolError(request, "CONNECTOR_ERROR", "Connector tool failed", false, false);
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

    private ObjectNode toolError(ObjectNode request, String code, String message, boolean retryable,
                                 boolean reauthorizationRequired) {
        var payload = objectMapper.createObjectNode();
        payload.put("isError", true);
        var structured = payload.objectNode();
        structured.put("ok", false);
        structured.put("errorCode", code);
        structured.put("retryable", retryable);
        structured.put("reauthorizationRequired", reauthorizationRequired);
        payload.set("structuredContent", structured);
        payload.putArray("content").addObject().put("type", "text").put("text", message);
        return result(request, payload);
    }

    private ObjectNode investmentContext(ObjectNode request, UUID userId, JsonNode arguments) {
        final String ticker;
        try {
            ticker = investmentContextTicker(arguments);
        } catch (IllegalArgumentException exception) {
            return toolError(request, "INVALID_ARGUMENT", exception.getMessage(), false, false);
        }

        if (investmentContextService == null) {
            return investmentContextUnavailable(request);
        }

        try {
            var context = investmentContextService.context(userId);
            if (ticker == null) return toolResult(request, context);

            var matchingSecurity = context.securities() == null ? null : context.securities().stream()
                    .filter(security -> security.ticker() != null
                            && ticker.equals(security.ticker().trim().toUpperCase(Locale.ROOT)))
                    .findFirst().orElse(null);
            if (matchingSecurity == null) {
                return toolError(request, "INVALID_ARGUMENT", "ticker is unknown in investment context",
                        false, false);
            }

            var filtered = (ObjectNode) objectMapper.valueToTree(context);
            var securities = objectMapper.createArrayNode().add(objectMapper.valueToTree(matchingSecurity));
            filtered.set("securities", securities);
            return toolResult(request, filtered);
        } catch (DataAccessException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_context")
                    .addKeyValue("outcome", "unavailable")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Investment context read unavailable");
            return investmentContextUnavailable(request);
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_context")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Investment context read failed");
            return toolError(request, "INTERNAL_ERROR", "Investment context could not be read", false, false);
        }
    }

    private ObjectNode putInvestmentThesis(ObjectNode request, UUID userId, JsonNode arguments) {
        if (investmentContextService == null) return investmentContextUnavailable(request);
        if (!arguments.isObject() || arguments.size() < 2 || arguments.size() > 4
                || !arguments.path("ticker").isTextual() || !arguments.path("thesis").isObject())
            return toolError(request, "INVALID_ARGUMENT", "ticker and thesis are required", false, false);
        try {
            for (var name : arguments.propertyNames()) {
                if (!java.util.Set.of("ticker", "thesis", "expectedUpdatedAt", "proposalRunId").contains(name))
                    return toolError(request, "INVALID_ARGUMENT", "Unsupported argument", false, false);
            }
            var ticker = investmentContextTicker(objectMapper.createObjectNode()
                    .put("ticker", arguments.path("ticker").asText()));
            var fields = investmentThesisSchema().path("properties").path("thesis").path("properties");
            var thesis = arguments.path("thesis");
            for (var name : thesis.propertyNames()) {
                var value = thesis.path(name);
                if (!fields.has(name) || !value.isNull()
                        && ("priceRiskTriggerPrice".equals(name) ? !value.isNumber() : !value.isTextual()))
                    return toolError(request, "INVALID_ARGUMENT", "Invalid thesis field or type", false, false);
            }
            var input = objectMapper.treeToValue(arguments.path("thesis"), InvestmentContextService.ThesisInput.class);
            if ("CONFIRMED".equalsIgnoreCase(input.invalidationStatus()))
                return toolError(request, "CONFIRMATION_REQUIRED", "Use the authenticated user thesis API to confirm a specific thesis", false, false);
            String proposalRunId = null;
            if (arguments.has("proposalRunId")) {
                proposalRunId = boundedText(arguments, "proposalRunId", 160);
            }
            var expectedUpdatedAt = optionalInstant(arguments, "expectedUpdatedAt");
            var saved = proposalRunId == null
                    ? investmentContextService.putThesisProposal(userId, ticker, input, expectedUpdatedAt)
                    : investmentContextService.putThesisProposal(userId, ticker, input, expectedUpdatedAt,
                            proposalRunId);
            return toolResult(request, saved);
        } catch (com.jmj.trade.investment.InvestmentException exception) {
            if (exception.code() == com.jmj.trade.investment.InvestmentException.Code.CONFLICT)
                return toolError(request, "THESIS_STATE_CONFLICT", "Thesis version changed or the current thesis is confirmed", false, false);
            return toolError(request, "INVALID_ARGUMENT", "Invalid investment thesis input", false, false);
        } catch (tools.jackson.core.JacksonException exception) {
            return toolError(request, "INVALID_ARGUMENT", "Invalid investment thesis payload", false, false);
        } catch (IllegalArgumentException exception) {
            return toolError(request, "INVALID_ARGUMENT", "Invalid investment thesis arguments", false, false);
        }
    }

    private ObjectNode appendInvestmentDecision(ObjectNode request, UUID userId, JsonNode arguments) {
        if (investmentContextService == null) return investmentContextUnavailable(request);
        var fields = java.util.Set.of("decisionId", "asOf", "asset", "action", "referencePrice",
                "priceSession", "horizon", "alphaThesis", "invalidation", "nextReviewTrigger", "confidence");
        if (arguments == null || !arguments.isObject() || arguments.size() != fields.size())
            return toolError(request, "INVALID_ARGUMENT", "All supported decision fields are required", false, false);
        for (var field : arguments.propertyNames()) {
            if (!fields.contains(field))
                return toolError(request, "INVALID_ARGUMENT", "Unsupported decision field", false, false);
        }
        for (var field : new String[]{"decisionId", "asOf", "asset", "action", "priceSession", "horizon",
                "alphaThesis", "invalidation", "nextReviewTrigger"}) {
            if (!arguments.path(field).isTextual())
                return toolError(request, "INVALID_ARGUMENT", "Decision fields have invalid types", false, false);
        }
        if (!arguments.path("referencePrice").isNumber() || !arguments.path("confidence").isNumber())
            return toolError(request, "INVALID_ARGUMENT", "Decision fields have invalid types", false, false);
        if (!java.util.Set.of("ADD", "HOLD", "REDUCE", "EXIT", "REPLACE")
                    .contains(arguments.path("action").asText())
                || !java.util.Set.of("REGULAR_CLOSE", "LIVE_REGULAR", "AFTER_HOURS", "PREMARKET")
                    .contains(arguments.path("priceSession").asText()))
            return toolError(request, "INVALID_ARGUMENT", "Decision action or priceSession is unsupported", false, false);
        try {
            var input = objectMapper.treeToValue(arguments, InvestmentContextService.DecisionInput.class);
            return toolResult(request, investmentContextService.recordDecision(userId, input));
        } catch (com.jmj.trade.investment.InvestmentException exception) {
            return switch (exception.code()) {
                case INVALID_INPUT -> toolError(request, "INVALID_ARGUMENT", "Invalid investment decision fields", false, false);
                case CONFLICT -> toolError(request, "DECISION_CONFLICT", "decisionId conflicts with an existing decision", false, false);
                case INVALID_USER -> toolError(request, "AUTHENTICATED_USER_INVALID", "Authenticated user is unavailable", false, true);
                case NOT_FOUND -> toolError(request, "INVESTMENT_STATE_NOT_FOUND", "Required investment state was not found", false, false);
            };
        } catch (DataAccessException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "append_investment_decision")
                    .addKeyValue("outcome", "unavailable")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Investment decision storage unavailable");
            return investmentContextUnavailable(request);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            return toolError(request, "INVALID_ARGUMENT", "Invalid investment decision fields", false, false);
        }
    }

    private ObjectNode appendInvestmentReview(ObjectNode request, UUID userId, JsonNode arguments) {
        if (investmentReviewService == null) return investmentContextUnavailable(request);
        var textFields = java.util.Set.of("recordKey", "asOf", "scope", "asset", "rawAction", "outcome",
                "rationale", "nextReviewTrigger", "rawPriceSession", "sourceAsOf");
        if (arguments == null || !arguments.isObject())
            return toolError(request, "INVALID_ARGUMENT", "Review arguments must be an object", false, false);
        for (var field : arguments.propertyNames()) {
            var value = arguments.path(field);
            if (textFields.contains(field) ? !value.isNull() && !value.isTextual()
                    : !"referencePrice".equals(field) || !value.isNull() && !value.isNumber())
                return toolError(request, "INVALID_ARGUMENT", "Unsupported review field or type", false, false);
        }
        for (var field : new String[]{"recordKey", "asOf", "scope"}) {
            if (!arguments.path(field).isTextual())
                return toolError(request, "INVALID_ARGUMENT", "recordKey, asOf and scope are required", false, false);
        }
        try {
            var input = objectMapper.treeToValue(arguments, InvestmentReviewService.ReviewInput.class)
                    .withSource("CONNECTOR_MCP")
                    .withRawPayload(arguments.deepCopy());
            return toolResult(request, investmentReviewService.recordReview(userId, input,
                    InvestmentReviewService.Actor.connector(userId)));
        } catch (com.jmj.trade.investment.InvestmentException exception) {
            return switch (exception.code()) {
                case INVALID_INPUT -> toolError(request, "INVALID_ARGUMENT", "Invalid investment review fields", false, false);
                case CONFLICT -> toolError(request, "REVIEW_CONFLICT", "recordKey conflicts with an existing review record", false, false);
                case INVALID_USER -> toolError(request, "AUTHENTICATED_USER_INVALID", "Authenticated user is unavailable", false, true);
                case NOT_FOUND -> toolError(request, "INVESTMENT_STATE_NOT_FOUND", "Required investment state was not found", false, false);
            };
        } catch (DataAccessException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "append_investment_review")
                    .addKeyValue("outcome", "unavailable")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Investment review storage unavailable");
            return investmentContextUnavailable(request);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            return toolError(request, "INVALID_ARGUMENT", "Invalid investment review fields", false, false);
        }
    }

    private ObjectNode investmentContextUnavailable(ObjectNode request) {
        return toolError(request, "INVESTMENT_CONTEXT_UNAVAILABLE",
                "Investment context is temporarily unavailable", true, false);
    }

    private ObjectNode investmentThesisVerificationContext(ObjectNode request, UUID userId, JsonNode arguments) {
        if (thesisVerificationService == null) return verificationContextUnavailable(request);
        final String ticker;
        try {
            ticker = investmentContextTicker(arguments);
            if (ticker == null) throw new IllegalArgumentException("ticker is required");
        } catch (IllegalArgumentException exception) {
            return toolError(request, "INVALID_ARGUMENT", exception.getMessage(), false, false);
        }
        try {
            return toolResult(request, thesisVerificationService.verificationContext(userId, ticker));
        } catch (DataAccessException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_thesis_verification_context")
                    .addKeyValue("outcome", "unavailable")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Thesis verification context unavailable");
            return verificationContextUnavailable(request);
        } catch (InvestmentException exception) {
            if (exception.code() == InvestmentException.Code.NOT_FOUND) {
                return toolError(request, "THESIS_CONTEXT_NOT_FOUND", "Thesis verification context was not found",
                        false, false);
            }
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_thesis_verification_context")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_code", exception.code().name())
                    .log("Thesis verification context read failed");
            return toolError(request, "INTERNAL_ERROR", "Thesis verification context could not be read",
                    false, false);
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_thesis_verification_context")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Thesis verification context read failed");
            return toolError(request, "INTERNAL_ERROR", "Thesis verification context could not be read",
                    false, false);
        }
    }

    private ObjectNode verificationContextUnavailable(ObjectNode request) {
        return toolError(request, "VERIFICATION_CONTEXT_UNAVAILABLE",
                "Thesis verification context is temporarily unavailable", true, false);
    }

    private ObjectNode verifyInvestmentThesis(ObjectNode request, UUID userId, UUID authenticatedApiKeyId,
                                              JsonNode arguments) {
        if (thesisVerificationService == null) return verificationUnavailable(request);
        if (authenticatedApiKeyId == null) {
            return toolError(request, "AUTHENTICATED_ACTOR_UNAVAILABLE",
                    "An authenticated connector trade key is required", false, false);
        }
        final InvestmentThesisVerificationService.VerificationInput input;
        try {
            input = verificationInput(arguments);
        } catch (IllegalArgumentException exception) {
            return toolError(request, "INVALID_ARGUMENT", exception.getMessage(), false, false);
        }
        try {
            return toolResult(request, thesisVerificationService.verify(userId, authenticatedApiKeyId, input));
        } catch (DataAccessException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "verify_investment_thesis")
                    .addKeyValue("outcome", "unavailable")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Thesis verification unavailable");
            return verificationUnavailable(request);
        } catch (InvestmentException exception) {
            return verificationException(request, exception);
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "verify_investment_thesis")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Thesis verification failed");
            return toolError(request, "INTERNAL_ERROR", "Thesis verification could not be saved", false, false);
        }
    }

    private ObjectNode verificationException(ObjectNode request, InvestmentException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT -> toolError(request, "INVALID_ARGUMENT", "Invalid thesis verification input",
                    false, false);
            case NOT_FOUND -> toolError(request, "THESIS_REVISION_NOT_FOUND",
                    "The referenced thesis revision was not found", false, false);
            case CONFLICT -> toolError(request, "VERIFICATION_EVENT_CONFLICT",
                    "Verification event ID conflicts with a previously stored event", false, false);
            case INVALID_USER -> toolError(request, "AUTHENTICATED_ACTOR_UNAVAILABLE",
                    "The authenticated user is unavailable", false, false);
        };
    }

    private ObjectNode verificationUnavailable(ObjectNode request) {
        return toolError(request, "VERIFICATION_UNAVAILABLE",
                "Thesis verification is temporarily unavailable", true, false);
    }

    private InvestmentThesisVerificationService.VerificationInput verificationInput(JsonNode arguments) {
        var fields = Set.of("verificationEventId", "ticker", "thesisRevisionId", "verificationRunId",
                "provider", "model", "verdict", "sourceAsOf", "sourceUrls", "rationale",
                "counterevidence", "selectedTriggerPrice");
        if (arguments == null || !arguments.isObject() || arguments.size() != fields.size()) {
            throw new IllegalArgumentException("All supported verification fields are required");
        }
        for (var field : arguments.properties()) {
            if (!fields.contains(field.getKey())) throw new IllegalArgumentException("Unsupported verification field");
        }

        var eventId = requiredUuid(arguments, "verificationEventId");
        var ticker = investmentContextTicker(objectMapper.createObjectNode()
                .put("ticker", requiredText(arguments, "ticker")));
        var revisionId = requiredUuid(arguments, "thesisRevisionId");
        var runId = boundedText(arguments, "verificationRunId", 160);
        var provider = boundedText(arguments, "provider", 120);
        var model = boundedText(arguments, "model", 160);
        var verdict = requiredText(arguments, "verdict");
        if (!Set.of("PASS", "REJECT").contains(verdict)) {
            throw new IllegalArgumentException("verdict must be PASS or REJECT");
        }
        var sourceAsOf = requiredInstant(arguments, "sourceAsOf");
        var sourceUrls = requiredHttpsUrls(arguments, "sourceUrls");
        var rationale = boundedText(arguments, "rationale", 4000);
        var counterevidence = boundedText(arguments, "counterevidence", 4000);
        var selectedTriggerPrice = requiredNullablePositiveFiniteDecimal(arguments, "selectedTriggerPrice");
        return new InvestmentThesisVerificationService.VerificationInput(eventId, ticker, revisionId, runId,
                provider, model, verdict, sourceAsOf, sourceUrls, rationale, counterevidence, selectedTriggerPrice);
    }

    private static UUID requiredUuid(JsonNode arguments, String field) {
        var value = arguments.path(field);
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a UUID");
        try {
            var uuid = UUID.fromString(value.asText());
            if (!uuid.toString().equalsIgnoreCase(value.asText())) {
                throw new IllegalArgumentException(field + " must be a UUID");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }

    private static String boundedText(JsonNode arguments, String field, int maxLength) {
        var value = arguments.path(field);
        if (!value.isTextual() || value.asText().isBlank()
                || value.asText().codePointCount(0, value.asText().length()) > maxLength) {
            throw new IllegalArgumentException(field + " must be a non-empty string within its length limit");
        }
        return value.asText();
    }

    private static Instant requiredInstant(JsonNode arguments, String field) {
        var value = arguments.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant");
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant");
        }
    }

    private static java.math.BigDecimal requiredNullablePositiveFiniteDecimal(JsonNode arguments, String field) {
        var value = arguments.path(field);
        if (value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException(field + " must be a positive finite number");
        try {
            var decimal = value.decimalValue();
            if (decimal.signum() <= 0 || value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) {
                throw new IllegalArgumentException(field + " must be a positive finite number");
            }
            return decimal;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a positive finite number");
        }
    }

    private static java.util.List<String> requiredHttpsUrls(JsonNode arguments, String field) {
        var values = arguments.path(field);
        if (!values.isArray() || values.size() < 1 || values.size() > 10) {
            throw new IllegalArgumentException(field + " must contain between 1 and 10 HTTPS URLs");
        }
        var result = new ArrayList<String>(values.size());
        var unique = new HashSet<String>();
        for (var value : values) {
            if (!value.isTextual() || value.asText().isBlank()
                    || value.asText().codePointCount(0, value.asText().length()) > 2000) {
                throw new IllegalArgumentException(field + " must contain only HTTPS URLs");
            }
            try {
                var uri = URI.create(value.asText());
                var host = uri.getHost();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()
                        || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                        || uri.getPort() > 65535) {
                    throw new IllegalArgumentException(field + " must contain only credential-free HTTPS URLs");
                }
                var port = uri.getPort() == 443 ? -1 : uri.getPort();
                var path = uri.normalize().getRawPath();
                var normalized = host.toLowerCase(Locale.ROOT) + ":" + port
                        + (path == null || path.isEmpty() ? "/" : path)
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                if (!unique.add(normalized)) {
                    throw new IllegalArgumentException(field + " must contain unique HTTPS URLs");
                }
                result.add(value.asText());
            } catch (IllegalArgumentException exception) {
                if (exception.getMessage() != null && exception.getMessage().startsWith(field)) throw exception;
                throw new IllegalArgumentException(field + " must contain valid HTTPS URLs");
            }
        }
        return java.util.List.copyOf(result);
    }

    private static String investmentContextTicker(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode()) return null;
        if (!arguments.isObject()) throw new IllegalArgumentException("arguments must be an object");
        for (var field : arguments.properties()) {
            if (!"ticker".equals(field.getKey())) {
                throw new IllegalArgumentException("unsupported investment context argument");
            }
        }
        var tickerNode = arguments.get("ticker");
        if (tickerNode == null) return null;
        if (!tickerNode.isTextual()) throw new IllegalArgumentException("ticker must be a non-empty string");
        var ticker = tickerNode.asText();
        if (!CONTEXT_TICKER.matcher(ticker).matches()) {
            throw new IllegalArgumentException("ticker must contain only letters, digits, '.', '_' or '-'");
        }
        return ticker.toUpperCase(Locale.ROOT);
    }

    private ObjectNode tool(String name, String title, String description,
                            ObjectNode schema, ObjectNode outputSchema) {
        return tool(name, title, description, schema, outputSchema, true, false, true,
                ConnectorApiKeyService.READ_SCOPE);
    }

    private ObjectNode tool(String name, String title, String description,
                            ObjectNode schema, ObjectNode outputSchema,
                            boolean readOnly, boolean destructive) {
        return tool(name, title, description, schema, outputSchema, readOnly, destructive, false,
                ConnectorApiKeyService.TRADE_SCOPE);
    }

    private ObjectNode tool(String name, String title, String description,
                            ObjectNode schema, ObjectNode outputSchema,
                            boolean readOnly, boolean destructive, boolean idempotent, String scope) {
        var tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        tool.set("outputSchema", outputSchema);
        var annotations = tool.objectNode();
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", destructive);
        annotations.put("idempotentHint", idempotent);
        annotations.put("openWorldHint", false);
        tool.set("annotations", annotations);
        var schemes = tool.putArray("securitySchemes");
        schemes.addObject().put("type", "oauth2").putArray("scopes").add(scope);
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

    private ObjectNode investmentContextSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 32)
                .put("pattern", "^[A-Za-z0-9._-]{1,32}$"));
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode thesisVerificationSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("verificationEventId", uuidSchema());
        properties.set("ticker", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 32)
                .put("pattern", "^[A-Za-z0-9._-]{1,32}$"));
        properties.set("thesisRevisionId", uuidSchema());
        properties.set("verificationRunId", boundedStringSchema(160));
        properties.set("provider", boundedStringSchema(120));
        properties.set("model", boundedStringSchema(160));
        properties.set("verdict", enumSchema("PASS", "REJECT"));
        properties.set("sourceAsOf", objectMapper.createObjectNode().put("type", "string")
                .put("format", "date-time"));
        properties.set("sourceUrls", objectMapper.createObjectNode().put("type", "array")
                .put("minItems", 1).put("maxItems", 10).put("uniqueItems", true)
                .set("items", objectMapper.createObjectNode().put("type", "string")
                        .put("format", "uri").put("pattern", "^https://.+").put("maxLength", 2000)));
        properties.set("rationale", boundedStringSchema(4000));
        properties.set("counterevidence", boundedStringSchema(4000));
        var triggerPrice = objectMapper.createObjectNode();
        triggerPrice.putArray("anyOf")
                .add(objectMapper.createObjectNode().put("type", "number").put("exclusiveMinimum", 0))
                .add(objectMapper.createObjectNode().put("type", "null"));
        properties.set("selectedTriggerPrice", triggerPrice);

        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        schema.putArray("required").add("verificationEventId").add("ticker")
                .add("thesisRevisionId").add("verificationRunId").add("provider")
                .add("model").add("verdict").add("sourceAsOf").add("sourceUrls")
                .add("rationale").add("counterevidence").add("selectedTriggerPrice");
        return schema;
    }

    private ObjectNode verificationContextSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 32)
                .put("pattern", "^[A-Za-z0-9._-]{1,32}$"));
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        schema.putArray("required").add("ticker");
        return schema;
    }

    private ObjectNode verificationContextOutputSchema() {
        var successProperties = objectMapper.createObjectNode();
        successProperties.set("policy", nullableObjectSchema());
        successProperties.set("thesis", nullableObjectSchema());
        successProperties.set("price", nullableObjectSchema());
        successProperties.set("latestVerification", nullableObjectSchema());
        var triggerCandidate = objectMapper.createObjectNode().put("type", "object");
        var candidateProperties = triggerCandidate.putObject("properties");
        candidateProperties.set("price", objectMapper.createObjectNode().put("type", "number")
                .put("exclusiveMinimum", 0));
        candidateProperties.set("source", objectMapper.createObjectNode().put("type", "string"));
        candidateProperties.set("asOf", nullableString(objectMapper.createObjectNode()
                .put("type", "string").put("format", "date-time"), null));
        triggerCandidate.putArray("required").add("price").add("source").add("asOf");
        triggerCandidate.put("additionalProperties", false);
        successProperties.set("candidateTriggers", objectMapper.createObjectNode().put("type", "array")
                .set("items", triggerCandidate));
        var success = objectMapper.createObjectNode().put("type", "object");
        success.set("properties", successProperties);
        success.putArray("required").add("policy").add("thesis").add("price")
                .add("latestVerification").add("candidateTriggers");
        success.put("additionalProperties", false);

        var errors = verificationErrorSchema("THESIS_CONTEXT_NOT_FOUND", "VERIFICATION_CONTEXT_UNAVAILABLE",
                "INTERNAL_ERROR", "INVALID_ARGUMENT");
        var output = objectMapper.createObjectNode().put("type", "object");
        output.putArray("anyOf").add(success).add(errors);
        return output;
    }

    private ObjectNode verificationResultOutputSchema() {
        var resultProperties = objectMapper.createObjectNode();
        resultProperties.set("verificationEventId", nullableString(objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid"), null));
        resultProperties.set("ticker", nullableString(objectMapper.createObjectNode().put("type", "string"), null));
        resultProperties.set("thesisRevisionId", nullableString(objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid"), null));
        resultProperties.set("outcome", enumSchema("AUTO_APPROVED", "BLOCKED", "REJECTED"));
        resultProperties.set("reasonCode", nullableString(objectMapper.createObjectNode().put("type", "string"), null));
        resultProperties.set("verdict", nullableString(enumSchema("PASS", "REJECT"), null));
        resultProperties.set("policyVersion", nullableString(objectMapper.createObjectNode().put("type", "string"), null));
        resultProperties.set("authenticatedActorUserId", nullableString(objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid"), null));
        resultProperties.set("authenticatedActorKeyId", nullableString(objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid"), null));
        resultProperties.set("createdAt", nullableString(objectMapper.createObjectNode()
                .put("type", "string").put("format", "date-time"), null));
        resultProperties.set("approvedThesis", nullableObjectSchema());
        var success = objectMapper.createObjectNode().put("type", "object");
        success.set("properties", resultProperties);
        success.putArray("required").add("verificationEventId").add("ticker").add("thesisRevisionId")
                .add("outcome").add("reasonCode").add("verdict").add("policyVersion")
                .add("authenticatedActorUserId").add("authenticatedActorKeyId").add("createdAt")
                .add("approvedThesis");
        success.put("additionalProperties", false);

        var errors = verificationErrorSchema("INVALID_ARGUMENT", "AUTHENTICATED_ACTOR_UNAVAILABLE",
                "THESIS_REVISION_NOT_FOUND", "VERIFICATION_EVENT_CONFLICT", "VERIFICATION_UNAVAILABLE",
                "INTERNAL_ERROR");
        var output = objectMapper.createObjectNode().put("type", "object");
        output.putArray("anyOf").add(success).add(errors);
        return output;
    }

    private ObjectNode verificationErrorSchema(String... codes) {
        var errorProperties = objectMapper.createObjectNode();
        errorProperties.set("ok", objectMapper.createObjectNode().put("const", false));
        errorProperties.set("errorCode", enumSchema(codes));
        errorProperties.set("retryable", objectMapper.createObjectNode().put("type", "boolean"));
        errorProperties.set("reauthorizationRequired", objectMapper.createObjectNode().put("type", "boolean"));
        var errors = objectMapper.createObjectNode().put("type", "object");
        errors.set("properties", errorProperties);
        errors.putArray("required").add("ok").add("errorCode").add("retryable").add("reauthorizationRequired");
        errors.put("additionalProperties", false);
        return errors;
    }

    private ObjectNode uuidSchema() {
        return objectMapper.createObjectNode().put("type", "string").put("format", "uuid");
    }

    private ObjectNode boundedStringSchema(int maxLength) {
        return objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", maxLength);
    }

    private ObjectNode investmentThesisSchema() {
        var schema = investmentContextSchema();
        var properties = (ObjectNode) schema.path("properties");
        var thesis = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var fields = thesis.putObject("properties");
        for (var name : new String[]{"coreThesis", "upsideDriver", "expectationsGap", "fundamentalInvalidation",
                "revisionInvalidation", "priceRiskTrigger", "expandTrigger", "exitOrDiscardTrigger", "classification"}) {
            var field = fields.putObject(name);
            field.putArray("type").add("string").add("null");
            field.put("maxLength", "classification".equals(name) ? 80 : 5000);
        }
        fields.set("coreThesis", objectMapper.createObjectNode().put("type", "string").put("minLength", 1).put("maxLength", 5000));
        var price = fields.putObject("priceRiskTriggerPrice").put("minimum", 0);
        price.putArray("type").add("number").add("null");
        var status = fields.putObject("invalidationStatus").put("type", "string");
        for (var value : new String[]{"AI_PROPOSED", "UNVERIFIED", "INVALIDATION_UNDEFINED"}) status.withArray("enum").add(value);
        thesis.putArray("required").add("coreThesis").add("invalidationStatus");
        properties.set("thesis", thesis);
        properties.set("expectedUpdatedAt", objectMapper.createObjectNode().put("type", "string").put("format", "date-time"));
        properties.set("proposalRunId", boundedStringSchema(160));
        schema.putArray("required").add("ticker").add("thesis");
        return schema;
    }

    private ObjectNode investmentDecisionSchema() {
        var schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var fields = schema.putObject("properties");
        fields.set("decisionId", objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
        fields.set("asOf", objectMapper.createObjectNode().put("type", "string").put("format", "date-time"));
        fields.set("asset", objectMapper.createObjectNode().put("type", "string")
                .put("pattern", "^[A-Za-z0-9._-]{1,32}$"));
        var action = fields.putObject("action").put("type", "string");
        for (var value : new String[]{"ADD", "HOLD", "REDUCE", "EXIT", "REPLACE"}) action.withArray("enum").add(value);
        fields.set("referencePrice", objectMapper.createObjectNode().put("type", "number").put("exclusiveMinimum", 0));
        var priceSession = fields.putObject("priceSession").put("type", "string");
        for (var value : new String[]{"REGULAR_CLOSE", "LIVE_REGULAR", "AFTER_HOURS", "PREMARKET"})
            priceSession.withArray("enum").add(value);
        fields.set("horizon", objectMapper.createObjectNode().put("type", "string").put("minLength", 1).put("maxLength", 80));
        fields.set("alphaThesis", objectMapper.createObjectNode().put("type", "string").put("minLength", 1));
        fields.set("invalidation", objectMapper.createObjectNode().put("type", "string").put("minLength", 1));
        fields.set("nextReviewTrigger", objectMapper.createObjectNode().put("type", "string").put("minLength", 1));
        fields.set("confidence", objectMapper.createObjectNode().put("type", "number")
                .put("minimum", 0).put("maximum", 1));
        schema.putArray("required").add("decisionId").add("asOf").add("asset").add("action")
                .add("referencePrice").add("priceSession").add("horizon").add("alphaThesis")
                .add("invalidation").add("nextReviewTrigger").add("confidence");
        return schema;
    }

    private ObjectNode investmentReviewSchema() {
        var schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var fields = schema.putObject("properties");
        fields.set("recordKey", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 128)
                .put("description", "Caller idempotency key; any string, need not be a UUID."));
        fields.set("asOf", objectMapper.createObjectNode().put("type", "string").put("format", "date-time")
                .put("description", "When the review was made; must not be in the future."));
        var scope = fields.putObject("scope").put("type", "string");
        scope.withArray("enum").add("SECURITY").add("PORTFOLIO");
        fields.set("asset", nullableString(objectMapper.createObjectNode().put("type", "string")
                .put("pattern", "^[A-Za-z0-9._-]{1,32}$"),
                "Required for SECURITY scope; omit or null for PORTFOLIO scope."));
        fields.set("rawAction", nullableString(objectMapper.createObjectNode().put("type", "string")
                .put("maxLength", 64), "Verbatim review label such as REVIEW; not an investment action."));
        fields.set("outcome", nullableString(objectMapper.createObjectNode().put("type", "string")
                .put("maxLength", 2000), null));
        fields.set("rationale", nullableString(objectMapper.createObjectNode().put("type", "string")
                .put("maxLength", 5000), null));
        fields.set("nextReviewTrigger", nullableString(objectMapper.createObjectNode().put("type", "string")
                .put("maxLength", 2000), null));
        var referencePrice = fields.putObject("referencePrice");
        referencePrice.putArray("anyOf")
                .add(objectMapper.createObjectNode().put("type", "number").put("exclusiveMinimum", 0))
                .add(objectMapper.createObjectNode().put("type", "null"));
        referencePrice.put("description", "Optional; never required for a review.");
        fields.set("rawPriceSession", nullableString(objectMapper.createObjectNode().put("type", "string")
                .put("maxLength", 64), "Verbatim session label; unknown values are kept and priceSession stays null."));
        fields.set("sourceAsOf", nullableString(objectMapper.createObjectNode().put("type", "string")
                .put("format", "date-time"), "As-of time of the evidence the review relied on."));
        schema.putArray("required").add("recordKey").add("asOf").add("scope");
        return schema;
    }

    private ObjectNode nullableString(ObjectNode stringSchema, String description) {
        var schema = objectMapper.createObjectNode();
        schema.putArray("anyOf").add(stringSchema).add(objectMapper.createObjectNode().put("type", "null"));
        if (description != null) schema.put("description", description);
        return schema;
    }

    private ObjectNode investmentContextOutputSchema() {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var anyOf = schema.putArray("anyOf");

        var contextProperties = objectMapper.createObjectNode();
        contextProperties.set("portfolio", nullableObjectSchema());
        contextProperties.set("securities", nullableArraySchema());
        contextProperties.set("watchlist", nullableArraySchema());
        contextProperties.set("riskPolicy", nullableObjectSchema());
        contextProperties.set("decisionLedger", nullableArraySchema());
        contextProperties.set("pipeline", nullableObjectSchema());
        contextProperties.set("tacticalOverlay", nullableObjectSchema());
        contextProperties.set("decisionOverlays", objectMapper.createObjectNode()
                .put("type", "object").set("additionalProperties", objectMapper.createObjectNode()
                        .put("type", "object").put("additionalProperties", true)));
        contextProperties.set("reviewLog", nullableArraySchema());
        var context = objectMapper.createObjectNode().put("type", "object");
        context.set("properties", contextProperties);
        context.putArray("required").add("portfolio").add("securities").add("watchlist")
                .add("riskPolicy").add("decisionLedger").add("pipeline")
                .add("tacticalOverlay").add("decisionOverlays");
        context.put("additionalProperties", true);
        anyOf.add(context);

        var errorProperties = objectMapper.createObjectNode();
        errorProperties.set("ok", objectMapper.createObjectNode().put("const", false));
        var errorCode = objectMapper.createObjectNode().put("type", "string");
        errorCode.putArray("enum").add("INVALID_ARGUMENT")
                .add("INVESTMENT_CONTEXT_UNAVAILABLE").add("INTERNAL_ERROR");
        errorProperties.set("errorCode", errorCode);
        errorProperties.set("retryable", objectMapper.createObjectNode().put("type", "boolean"));
        errorProperties.set("reauthorizationRequired", objectMapper.createObjectNode().put("type", "boolean"));
        var error = objectMapper.createObjectNode().put("type", "object");
        error.set("properties", errorProperties);
        error.putArray("required").add("ok").add("errorCode").add("retryable").add("reauthorizationRequired");
        error.put("additionalProperties", false);
        anyOf.add(error);
        return schema;
    }

    private ObjectNode nullableObjectSchema() {
        var schema = objectMapper.createObjectNode();
        var anyOf = schema.putArray("anyOf");
        anyOf.add(objectMapper.createObjectNode().put("type", "object").put("additionalProperties", true));
        anyOf.add(objectMapper.createObjectNode().put("type", "null"));
        return schema;
    }

    private ObjectNode nullableArraySchema() {
        var schema = objectMapper.createObjectNode();
        var anyOf = schema.putArray("anyOf");
        anyOf.add(objectMapper.createObjectNode().put("type", "array")
                .set("items", objectMapper.createObjectNode().put("type", "object")
                        .put("additionalProperties", true)));
        anyOf.add(objectMapper.createObjectNode().put("type", "null"));
        return schema;
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
        return toolError(request, "TRADE_SCOPE_REQUIRED", "Connector trade scope required", false, false);
    }

    private ObjectNode liveOrderExecutionDisabled(ObjectNode request) {
        return toolError(request, "LIVE_ORDER_DISABLED", "Live order execution is disabled (REAL_ORDER_ENABLED=false)",
                false, false);
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
