package com.jmj.trade.connector;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.investment.InvestmentOnboardingService;
import com.jmj.trade.investment.InvestmentRiskPreflightService;
import com.jmj.trade.order.LiveOrderActivationException;
import com.jmj.trade.order.McpOrderExecutionService;
import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.investment.InvestmentException;
import com.jmj.trade.investment.InvestmentReviewService;
import com.jmj.trade.investment.InvestmentThesisVerificationService;
import com.jmj.trade.monitoring.InvestmentRiskEventService;
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
import java.time.LocalDate;
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
    private static final Pattern THEME_ID = Pattern.compile("[A-Za-z0-9._:-]{1,116}");

    private final ConnectorService service;
    private final McpOrderExecutionService tradeService;
    private final InvestmentContextService investmentContextService;
    private final InvestmentReviewService investmentReviewService;
    private final InvestmentThesisVerificationService thesisVerificationService;
    private final InvestmentOnboardingService investmentOnboardingService;
    private final InvestmentRiskPreflightService investmentRiskPreflightService;
    private final InvestmentRiskEventService investmentRiskEventService;
    private final com.jmj.trade.investment.InvestmentSellReadinessService sellReadinessService;
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
                         ObjectProvider<InvestmentOnboardingService> investmentOnboardingService,
                         ObjectProvider<InvestmentRiskPreflightService> investmentRiskPreflightService,
                         ObjectProvider<InvestmentRiskEventService> investmentRiskEventService,
                         ObjectProvider<com.jmj.trade.investment.InvestmentSellReadinessService> sellReadinessService,
                         ObjectMapper objectMapper) {
        this(service, tradeService.getIfAvailable(), investmentContextService.getIfAvailable(),
                investmentReviewService.getIfAvailable(), thesisVerificationService.getIfAvailable(),
                investmentOnboardingService.getIfAvailable(), investmentRiskPreflightService.getIfAvailable(),
                investmentRiskEventService.getIfAvailable(),
                sellReadinessService.getIfAvailable(),
                objectMapper);
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
        this(service, tradeService, investmentContextService, investmentReviewService,
                thesisVerificationService, null, null, objectMapper);
    }

    public ConnectorMcpProtocol(ConnectorService service, McpOrderExecutionService tradeService,
                                InvestmentContextService investmentContextService,
                                InvestmentReviewService investmentReviewService,
                                InvestmentThesisVerificationService thesisVerificationService,
                                InvestmentOnboardingService investmentOnboardingService,
                                InvestmentRiskPreflightService investmentRiskPreflightService,
                                ObjectMapper objectMapper) {
        this(service, tradeService, investmentContextService, investmentReviewService, thesisVerificationService,
                investmentOnboardingService, investmentRiskPreflightService, null, objectMapper);
    }

    public ConnectorMcpProtocol(ConnectorService service, McpOrderExecutionService tradeService,
                                InvestmentContextService investmentContextService,
                                InvestmentReviewService investmentReviewService,
                                InvestmentThesisVerificationService thesisVerificationService,
                                InvestmentOnboardingService investmentOnboardingService,
                                InvestmentRiskPreflightService investmentRiskPreflightService,
                                InvestmentRiskEventService investmentRiskEventService,
                                ObjectMapper objectMapper) {
        this(service, tradeService, investmentContextService, investmentReviewService, thesisVerificationService,
                investmentOnboardingService, investmentRiskPreflightService, investmentRiskEventService, null,
                objectMapper);
    }

    public ConnectorMcpProtocol(ConnectorService service, McpOrderExecutionService tradeService,
                                InvestmentContextService investmentContextService,
                                InvestmentReviewService investmentReviewService,
                                InvestmentThesisVerificationService thesisVerificationService,
                                InvestmentOnboardingService investmentOnboardingService,
                                InvestmentRiskPreflightService investmentRiskPreflightService,
                                InvestmentRiskEventService investmentRiskEventService,
                                com.jmj.trade.investment.InvestmentSellReadinessService sellReadinessService,
                                ObjectMapper objectMapper) {
        this.service = service;
        this.tradeService = tradeService;
        this.investmentContextService = investmentContextService;
        this.investmentReviewService = investmentReviewService;
        this.thesisVerificationService = thesisVerificationService;
        this.investmentOnboardingService = investmentOnboardingService;
        this.investmentRiskPreflightService = investmentRiskPreflightService;
        this.investmentRiskEventService = investmentRiskEventService;
        this.sellReadinessService = sellReadinessService;
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
                "Read the authenticated user's persisted investment context, including portfolio, security data, position mandates, risk, thesis, decisions, review notes (reviewLog), and tactical overlays. This tool does not call providers or write data. Optionally filter only the securities array by ticker.",
                investmentContextSchema(), investmentContextOutputSchema()));
        if (investmentRiskEventService != null) {
            tools.add(tool("get_investment_risk_events", "Get investment risk events",
                    "Read persisted thesis price-risk state transitions for the authenticated user. Does not call providers, refresh data, alter thesis or risk policy, or place orders.",
                    investmentRiskEventsSchema(), investmentRiskEventsOutputSchema()));
        }
        if (sellReadinessService != null) {
            tools.add(tool("get_investment_sell_readiness", "Get defensive sell readiness",
                    "Read an externally authored REDUCE/EXIT decision and its linked current risk event, derive account-local sell quantity, and inspect Toss order-book/calendar evidence. Never chooses an action, rounds quantities, writes data, or submits an order.",
                    sellReadinessSchema(), sellReadinessOutputSchema()));
        }
        if (thesisVerificationService != null) {
            tools.add(tool("get_investment_thesis_verification_context", "Get thesis verification context",
                    "Read the authenticated user's current thesis revision, verification policy, trusted price view, and latest verification event for one ticker. This tool does not call providers or write data.",
                    verificationContextSchema(), verificationContextOutputSchema()));
        }
        if (investmentOnboardingService != null) {
            tools.add(tool("get_investment_onboarding_readiness", "Get investment onboarding readiness",
                    "Read the authenticated user's stored onboarding readiness for one ticker, including source statuses and times, thesis receipt and eligibility, risk mark, candidate fields, and blockers. Does not refresh providers, start collection, approve an investment, or place an order.",
                    requiredTickerSchema(), investmentOnboardingOutputSchema()));
        }
        if (investmentRiskPreflightService != null) {
            tools.add(tool("preflight_investment_risk", "Preflight investment risk",
                    "Compute current and proposed-quantity risk gates for one ticker using server-selected risk inputs. Accepts no execution price, does not approve an investment, and does not prepare or submit an order.",
                    riskPreflightSchema(), riskPreflightOutputSchema()));
        }
        if (canTrade) {
            if (investmentContextService != null) {
                tools.add(tool("upsert_position_mandate", "Save position investment mandate",
                        "Store one user-confirmed ticker investment horizon in PostgreSQL. The server derives riskPriceRole and USER_CONFIRMED provenance. Does not set a price trigger, create an investment decision, or place an order. Requires connector:trade.",
                        positionMandateUpsertSchema(), positionMandateOutputSchema(), false, false, true,
                        ConnectorApiKeyService.TRADE_SCOPE));
                tools.add(tool("upsert_investment_theme", "Upsert investment theme mapping",
                        "Store or update one user-supplied theme and ticker mapping in the existing append-only PostgreSQL tactical inputs, then refresh the derived Tactical V1 snapshots. The server records USER_INPUT provenance and its own source timestamp. Does not create investment judgments or place orders. Requires connector:trade.",
                        investmentThemeUpsertSchema(), investmentThemeUpsertOutputSchema(), false, false, true,
                        ConnectorApiKeyService.TRADE_SCOPE));
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
            if (investmentOnboardingService != null) {
                tools.add(tool("register_investment_candidate", "Register investment candidate",
                        "Idempotently add one ticker to the authenticated user's existing monitoring watchlist for later normal scheduled collection. Does not call a provider, start alpha research, or place an order. Requires connector:trade.",
                        requiredTickerSchema(), registrationOutputSchema(), false, false, true,
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
                case "get_investment_risk_events" -> getInvestmentRiskEvents(request, userId, arguments);
                case "get_investment_sell_readiness" -> getInvestmentSellReadiness(
                        request, userId, connectionId, arguments);
                case "get_investment_thesis_verification_context" ->
                        investmentThesisVerificationContext(request, userId, arguments);
                case "get_investment_onboarding_readiness" ->
                        investmentOnboardingReadiness(request, userId, arguments);
                case "preflight_investment_risk" -> preflightInvestmentRisk(request, userId, arguments);
                case "register_investment_candidate" -> !tradeScopeGranted
                        ? forbiddenTrade(request)
                        : registerInvestmentCandidate(request, userId, arguments);
                case "upsert_investment_theme" -> !tradeScopeGranted
                        ? forbiddenTrade(request)
                        : upsertInvestmentTheme(request, userId, arguments);
                case "upsert_position_mandate" -> !tradeScopeGranted
                        ? forbiddenTrade(request)
                        : upsertPositionMandate(request, userId, arguments);
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
                        : prepareOrder(request, userId, connectionId, arguments);
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
                        brokerException.isRetriable(), false, brokerException.category().name());
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
        return toolError(request, code, message, retryable, reauthorizationRequired, null);
    }

    private ObjectNode toolError(ObjectNode request, String code, String message, boolean retryable,
                                 boolean reauthorizationRequired, String errorCategory) {
        var payload = objectMapper.createObjectNode();
        payload.put("isError", true);
        var structured = payload.objectNode();
        structured.put("ok", false);
        structured.put("errorCode", code);
        structured.put("retryable", retryable);
        structured.put("reauthorizationRequired", reauthorizationRequired);
        if (errorCategory != null) structured.put("errorCategory", errorCategory);
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

    private ObjectNode getInvestmentRiskEvents(ObjectNode request, UUID userId, JsonNode arguments) {
        if (investmentRiskEventService == null) return riskEventsUnavailable(request);
        try {
            if (arguments == null || !arguments.isObject())
                throw new IllegalArgumentException("arguments must be an object");
            var allowed = java.util.Set.of("ticker", "since", "includeUnresolved", "limit", "cursor");
            for (var field : arguments.propertyNames()) {
                if (!allowed.contains(field)) throw new IllegalArgumentException("unsupported argument");
            }
            String ticker = null;
            if (arguments.has("ticker")) {
                if (!arguments.path("ticker").isTextual()) throw new IllegalArgumentException("invalid ticker");
                var rawTicker = arguments.path("ticker").asText();
                if (!rawTicker.equals(rawTicker.trim())) throw new IllegalArgumentException("invalid ticker");
                ticker = rawTicker.toUpperCase(Locale.ROOT);
                if (!CONTEXT_TICKER.matcher(ticker).matches()) throw new IllegalArgumentException("invalid ticker");
            }
            Instant since = null;
            if (arguments.has("since")) {
                if (!arguments.path("since").isTextual()) throw new IllegalArgumentException("invalid since");
                since = Instant.parse(arguments.path("since").asText());
            }
            Boolean includeUnresolved = null;
            if (arguments.has("includeUnresolved")) {
                if (!arguments.path("includeUnresolved").isBoolean())
                    throw new IllegalArgumentException("invalid includeUnresolved");
                includeUnresolved = arguments.path("includeUnresolved").booleanValue();
            }
            Integer limit = null;
            if (arguments.has("limit")) {
                if (!arguments.path("limit").isIntegralNumber() || !arguments.path("limit").canConvertToInt())
                    throw new IllegalArgumentException("invalid limit");
                limit = arguments.path("limit").intValue();
                if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            String cursor = null;
            if (arguments.has("cursor")) {
                if (!arguments.path("cursor").isTextual() || arguments.path("cursor").asText().length() > 512)
                    throw new IllegalArgumentException("invalid cursor");
                cursor = arguments.path("cursor").asText();
            }
            var query = new InvestmentRiskEventService.RiskEventsQuery(
                    ticker, since, includeUnresolved, limit, cursor);
            return toolResult(request, investmentRiskEventService.read(userId, query));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            return toolError(request, "INVALID_ARGUMENT", exception.getMessage(), false, false);
        } catch (DataAccessException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_risk_events")
                    .addKeyValue("outcome", "unavailable")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Investment risk event read unavailable");
            return riskEventsUnavailable(request);
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_risk_events")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Investment risk event read failed");
            return riskEventsUnavailable(request);
        }
    }

    private ObjectNode getInvestmentSellReadiness(ObjectNode request, UUID userId, UUID connectionId,
                                                  JsonNode arguments) {
        if (sellReadinessService == null) return sellReadinessUnavailable(request);
        try {
            if (arguments == null || !arguments.isObject() || arguments.size() != 2
                    || !arguments.has("decisionId") || !arguments.has("eventId"))
                throw new IllegalArgumentException("decisionId and eventId are required");
            for (var field : arguments.propertyNames()) {
                if (!Set.of("decisionId", "eventId").contains(field))
                    throw new IllegalArgumentException("unsupported argument");
            }
            return toolResult(request, sellReadinessService.evaluate(userId, connectionId,
                    requiredUuid(arguments, "decisionId"), requiredUuid(arguments, "eventId")));
        } catch (IllegalArgumentException exception) {
            return toolError(request, "INVALID_ARGUMENT", exception.getMessage(), false, false);
        } catch (DataAccessException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_sell_readiness")
                    .addKeyValue("outcome", "unavailable")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Investment sell readiness unavailable");
            return sellReadinessUnavailable(request);
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", "mcp_tool")
                    .addKeyValue("tool", "get_investment_sell_readiness")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("Investment sell readiness failed");
            return toolError(request, "INTERNAL_ERROR", "Sell readiness could not be evaluated", false, false);
        }
    }

    private ObjectNode sellReadinessUnavailable(ObjectNode request) {
        return toolError(request, "SELL_READINESS_UNAVAILABLE",
                "Sell readiness could not be read", true, false);
    }

    private ObjectNode prepareOrder(ObjectNode request, UUID userId, UUID connectionId, JsonNode arguments) {
        if (!arguments.path("decisionId").isMissingNode() || !arguments.path("eventId").isMissingNode()) {
            if (!arguments.has("decisionId") || !arguments.has("eventId"))
                return toolError(request, "INVALID_ARGUMENT", "decisionId and eventId must be supplied together",
                        false, false);
            if (!"SELL".equalsIgnoreCase(requiredText(arguments, "side"))
                    || !"LIMIT".equalsIgnoreCase(requiredText(arguments, "orderType")))
                return toolError(request, "INVALID_ARGUMENT",
                        "Risk-linked defensive preparation requires SELL LIMIT", false, false);
            for (var field : arguments.propertyNames()) {
                if (!Set.of("symbol", "side", "orderType", "quantity", "price", "decisionId", "eventId")
                        .contains(field))
                    return toolError(request, "INVALID_ARGUMENT", "Unsupported prepare_order field", false, false);
            }
            var proposal = tradeService.prepareDefensive(userId, connectionId,
                    requiredUuid(arguments, "decisionId"), requiredUuid(arguments, "eventId"),
                    requiredText(arguments, "symbol"), decimal(arguments, "quantity"),
                    decimal(arguments, "price"));
            return toolResult(request, proposal);
        }
        return toolResult(request, tradeService.prepare(userId, connectionId, prepare(arguments)));
    }

    private ObjectNode riskEventsUnavailable(ObjectNode request) {
        return toolError(request, "INVESTMENT_RISK_EVENTS_UNAVAILABLE",
                "Investment risk events could not be read", true, false);
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

    private ObjectNode upsertPositionMandate(ObjectNode request, UUID userId, JsonNode arguments) {
        if (investmentContextService == null) return investmentContextUnavailable(request);
        final String ticker;
        final String investmentHorizon;
        final LocalDate effectiveDate;
        var fields = Set.of("ticker", "investmentHorizon", "effectiveDate");
        try {
            if (arguments == null || !arguments.isObject() || arguments.size() != fields.size())
                throw new IllegalArgumentException("All position mandate fields are required");
            ticker = requiredInvestmentTicker(arguments, fields);
            investmentHorizon = requiredText(arguments, "investmentHorizon");
            effectiveDate = LocalDate.parse(requiredText(arguments, "effectiveDate"));
            if (!Set.of("TACTICAL", "STRATEGIC").contains(investmentHorizon))
                throw new IllegalArgumentException("Unsupported investment horizon");
        } catch (IllegalArgumentException exception) {
            return invalidInvestmentArguments(request);
        }
        try {
            var input = new InvestmentContextService.PositionMandateInput(investmentHorizon, effectiveDate);
            return toolResult(request, investmentContextService.upsertPositionMandate(userId, ticker, input));
        } catch (InvestmentException exception) {
            return investmentException(request, exception);
        } catch (DataAccessException exception) {
            return investmentContextUnavailable(request);
        } catch (RuntimeException exception) {
            logInvestmentToolFailure("upsert_position_mandate", exception);
            return toolError(request, "INTERNAL_ERROR", "Position mandate could not be saved", false, false);
        }
    }

    private ObjectNode appendInvestmentDecision(ObjectNode request, UUID userId, JsonNode arguments) {
        if (investmentContextService == null) return investmentContextUnavailable(request);
        var fields = java.util.Set.of("decisionId", "asOf", "asset", "action", "referencePrice",
                "priceSession", "horizon", "alphaThesis", "invalidation", "nextReviewTrigger", "confidence",
                "riskEventIds", "targetRemainingQuantity", "targetBrokerConnectionId", "investmentHorizon",
                "priceRiskState", "thesisHealth", "actionReason");
        if (arguments == null || !arguments.isObject() || arguments.size() < 11 || arguments.size() > 18)
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
        if (arguments.has("targetRemainingQuantity") && !arguments.path("targetRemainingQuantity").isNumber())
            return toolError(request, "INVALID_ARGUMENT", "Decision fields have invalid types", false, false);
        if (arguments.has("targetBrokerConnectionId")
                && !arguments.path("targetBrokerConnectionId").isTextual())
            return toolError(request, "INVALID_ARGUMENT", "Decision fields have invalid types", false, false);
        for (var field : new String[]{"investmentHorizon", "priceRiskState", "thesisHealth", "actionReason"}) {
            if (arguments.has(field) && !arguments.path(field).isTextual())
                return toolError(request, "INVALID_ARGUMENT", "Decision fields have invalid types", false, false);
        }
        if (arguments.has("actionReason") && arguments.path("actionReason").asText().length() > 2000)
            return toolError(request, "INVALID_ARGUMENT", "actionReason exceeds the supported length", false, false);
        if (!java.util.Set.of("ADD", "HOLD", "REDUCE", "EXIT", "REPLACE")
                    .contains(arguments.path("action").asText())
                || !java.util.Set.of("REGULAR_CLOSE", "LIVE_REGULAR", "AFTER_HOURS", "PREMARKET")
                    .contains(arguments.path("priceSession").asText()))
            return toolError(request, "INVALID_ARGUMENT", "Decision action or priceSession is unsupported", false, false);
        if (arguments.has("riskEventIds")) {
            var riskEventIds = arguments.path("riskEventIds");
            if (!riskEventIds.isArray() || riskEventIds.size() > 20)
                return toolError(request, "INVALID_ARGUMENT", "riskEventIds must be an array of at most 20 UUIDs", false, false);
            var seen = new HashSet<String>();
            for (var eventId : riskEventIds) {
                if (!eventId.isTextual() || !seen.add(eventId.asText()))
                    return toolError(request, "INVALID_ARGUMENT", "riskEventIds must contain unique UUIDs", false, false);
                try {
                    UUID.fromString(eventId.asText());
                } catch (IllegalArgumentException exception) {
                    return toolError(request, "INVALID_ARGUMENT", "riskEventIds must contain unique UUIDs", false, false);
                }
            }
        }
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

    private ObjectNode investmentOnboardingReadiness(ObjectNode request, UUID userId, JsonNode arguments) {
        final String ticker;
        try {
            ticker = requiredInvestmentTicker(arguments, Set.of("ticker"));
        } catch (IllegalArgumentException exception) {
            return invalidInvestmentArguments(request);
        }
        if (investmentOnboardingService == null) return investmentContextUnavailable(request);
        try {
            return toolResult(request, investmentOnboardingService.readiness(userId, ticker));
        } catch (DataAccessException exception) {
            return investmentContextUnavailable(request);
        } catch (InvestmentException exception) {
            return investmentException(request, exception);
        } catch (RuntimeException exception) {
            logInvestmentToolFailure("get_investment_onboarding_readiness", exception);
            return toolError(request, "INTERNAL_ERROR", "Investment onboarding readiness could not be read",
                    false, false);
        }
    }

    private ObjectNode preflightInvestmentRisk(ObjectNode request, UUID userId, JsonNode arguments) {
        final String ticker;
        final java.math.BigDecimal quantity;
        try {
            ticker = requiredInvestmentTicker(arguments, Set.of("ticker", "quantity"));
            var quantityNode = arguments.get("quantity");
            if (quantityNode == null || !quantityNode.isNumber()) throw new IllegalArgumentException();
            quantity = quantityNode.decimalValue();
            if (!isValidPreflightQuantity(quantity)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            return invalidInvestmentArguments(request);
        }
        if (investmentRiskPreflightService == null) return investmentContextUnavailable(request);
        try {
            var input = new InvestmentRiskPreflightService.PreflightInput(ticker, quantity);
            return toolResult(request, investmentRiskPreflightService.preflight(userId, input));
        } catch (DataAccessException exception) {
            return investmentContextUnavailable(request);
        } catch (InvestmentException exception) {
            return investmentException(request, exception);
        } catch (RuntimeException exception) {
            logInvestmentToolFailure("preflight_investment_risk", exception);
            return toolError(request, "INTERNAL_ERROR", "Investment risk preflight could not be computed",
                    false, false);
        }
    }

    private ObjectNode registerInvestmentCandidate(ObjectNode request, UUID userId, JsonNode arguments) {
        final String ticker;
        try {
            ticker = requiredInvestmentTicker(arguments, Set.of("ticker"));
        } catch (IllegalArgumentException exception) {
            return invalidInvestmentArguments(request);
        }
        if (investmentOnboardingService == null) return investmentContextUnavailable(request);
        try {
            return toolResult(request, investmentOnboardingService.register(userId, ticker));
        } catch (DataAccessException exception) {
            return investmentContextUnavailable(request);
        } catch (InvestmentException exception) {
            return investmentException(request, exception);
        } catch (RuntimeException exception) {
            logInvestmentToolFailure("register_investment_candidate", exception);
            return toolError(request, "INTERNAL_ERROR", "Investment candidate registration failed",
                    false, false);
        }
    }

    private ObjectNode upsertInvestmentTheme(ObjectNode request, UUID userId, JsonNode arguments) {
        final com.jmj.trade.investment.tactical.TacticalOverlayService.ThemeUpsertInput input;
        try {
            input = investmentThemeUpsertInput(arguments);
        } catch (IllegalArgumentException exception) {
            return invalidInvestmentArguments(request);
        }
        if (investmentContextService == null) return investmentContextUnavailable(request);
        try {
            return toolResult(request, investmentContextService.upsertTacticalTheme(userId, input));
        } catch (DataAccessException exception) {
            return investmentContextUnavailable(request);
        } catch (InvestmentException exception) {
            return investmentException(request, exception);
        } catch (RuntimeException exception) {
            logInvestmentToolFailure("upsert_investment_theme", exception);
            return toolError(request, "INTERNAL_ERROR", "Investment theme upsert failed", false, false);
        }
    }

    private static com.jmj.trade.investment.tactical.TacticalOverlayService.ThemeUpsertInput
    investmentThemeUpsertInput(JsonNode arguments) {
        var fields = Set.of("themeId", "name", "ticker", "effectiveDate");
        if (arguments == null || !arguments.isObject() || arguments.size() != fields.size()) {
            throw new IllegalArgumentException("All theme upsert fields are required");
        }
        for (var field : arguments.propertyNames()) {
            if (!fields.contains(field)) throw new IllegalArgumentException("Unsupported theme upsert field");
        }
        var themeId = boundedText(arguments, "themeId", 116).trim();
        if (!THEME_ID.matcher(themeId).matches()) throw new IllegalArgumentException("Invalid themeId");
        var name = boundedText(arguments, "name", 120).trim();
        var ticker = requiredInvestmentTicker(arguments, fields);
        final LocalDate effectiveDate;
        try {
            effectiveDate = LocalDate.parse(requiredText(arguments, "effectiveDate"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("effectiveDate must be an ISO-8601 date");
        }
        return new com.jmj.trade.investment.tactical.TacticalOverlayService.ThemeUpsertInput(
                themeId, name, ticker, effectiveDate);
    }

    private static String requiredInvestmentTicker(JsonNode arguments, Set<String> allowedFields) {
        if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException();
        for (var field : arguments.propertyNames()) {
            if (!allowedFields.contains(field)) throw new IllegalArgumentException();
        }
        var tickerNode = arguments.get("ticker");
        if (tickerNode == null || !tickerNode.isTextual()) throw new IllegalArgumentException();
        var ticker = tickerNode.asText();
        if (!CONTEXT_TICKER.matcher(ticker).matches()) throw new IllegalArgumentException();
        return ticker.toUpperCase(Locale.ROOT);
    }

    private static boolean isValidPreflightQuantity(java.math.BigDecimal quantity) {
        if (quantity.signum() <= 0) return false;
        var scale = quantity.scale();
        return scale <= 10 && scale >= -18
                && (long) quantity.precision() - scale <= 18
                && quantity.precision() <= 28;
    }

    private ObjectNode invalidInvestmentArguments(ObjectNode request) {
        return toolError(request, "INVALID_ARGUMENT", "Investment tool arguments are invalid", false, false);
    }

    private ObjectNode investmentException(ObjectNode request, InvestmentException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT -> invalidInvestmentArguments(request);
            case INVALID_USER -> toolError(request, "AUTHENTICATED_USER_INVALID",
                    "Authenticated user is unavailable", false, true);
            case NOT_FOUND -> toolError(request, "INVESTMENT_STATE_NOT_FOUND",
                    "Required investment state was not found", false, false);
            case CONFLICT -> toolError(request, "INVESTMENT_STATE_CONFLICT",
                    "Investment state changed; retry with current state", false, false);
        };
    }

    private static void logInvestmentToolFailure(String tool, RuntimeException exception) {
        LOG.atWarn().addKeyValue("operation", "mcp_tool")
                .addKeyValue("tool", tool)
                .addKeyValue("outcome", "failure")
                .addKeyValue("error_type", exception.getClass().getSimpleName())
                .log("Investment MCP tool failed");
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
        properties.set("decisionId", uuidSchema());
        properties.set("eventId", uuidSchema());
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

    private ObjectNode investmentRiskEventsSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 32).put("pattern", "^[A-Za-z0-9._-]{1,32}$"));
        properties.set("since", objectMapper.createObjectNode().put("type", "string").put("format", "date-time"));
        properties.set("includeUnresolved", objectMapper.createObjectNode().put("type", "boolean"));
        properties.set("limit", objectMapper.createObjectNode().put("type", "integer")
                .put("minimum", 1).put("maximum", 100).put("default", 50));
        properties.set("cursor", objectMapper.createObjectNode().put("type", "string").put("maxLength", 512));
        var schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", properties);
        return schema;
    }

    private ObjectNode sellReadinessSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("decisionId", uuidSchema());
        properties.set("eventId", uuidSchema());
        var schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", properties);
        schema.putArray("required").add("decisionId").add("eventId");
        return schema;
    }

    private ObjectNode sellReadinessOutputSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("actionStatus", enumSchema("READY", "BLOCKED", "NOT_EVALUATED"));
        properties.set("sizingStatus", enumSchema("READY", "BLOCKED", "NOT_EVALUATED"));
        properties.set("pricingStatus", enumSchema("READY", "BLOCKED", "NOT_EVALUATED"));
        properties.set("prepareStatus", enumSchema("READY", "BLOCKED", "NOT_EVALUATED"));
        properties.set("decisionId", uuidSchema());
        properties.set("eventId", uuidSchema());
        properties.set("ticker", nullableStringSchema());
        properties.set("action", nullableStringSchema());
        properties.set("decisionAsOf", nullableDateTimeSchema());
        properties.set("thesisRevisionId", nullableUuidSchema());
        properties.set("quantity", nullableNumberSchema());
        properties.set("targetRemainingQuantity", nullableNumberSchema());
        properties.set("positionAsOf", nullableDateTimeSchema());
        properties.set("bid", nullableNumberSchema());
        properties.set("ask", nullableNumberSchema());
        properties.set("limitPrice", nullableNumberSchema());
        properties.set("absoluteSpread", nullableNumberSchema());
        properties.set("spreadBps", nullableNumberSchema());
        properties.set("priceSession", nullableStringSchema());
        properties.set("priceSource", nullableStringSchema());
        properties.set("currency", nullableStringSchema());
        properties.set("orderBookAsOf", nullableDateTimeSchema());
        properties.set("orderBookObservedAt", nullableDateTimeSchema());
        properties.set("marketCalendarSource", nullableStringSchema());
        properties.set("priceIncrementSource", nullableStringSchema());
        properties.set("priceIncrement", nullableNumberSchema());
        properties.set("blockers", arrayOf(stringSchema()));
        properties.set("asOf", dateTimeSchema());
        var success = closedObject(properties, "actionStatus", "sizingStatus", "pricingStatus", "prepareStatus",
                "decisionId", "eventId", "ticker", "action", "decisionAsOf", "thesisRevisionId", "quantity",
                "targetRemainingQuantity", "positionAsOf", "bid", "ask", "limitPrice", "absoluteSpread",
                "spreadBps", "priceSession", "priceSource", "currency", "orderBookAsOf", "orderBookObservedAt",
                "marketCalendarSource", "priceIncrementSource", "priceIncrement", "blockers", "asOf");
        return outputSchema(success, "INVALID_ARGUMENT", "SELL_READINESS_UNAVAILABLE", "INTERNAL_ERROR");
    }

    private ObjectNode investmentRiskEventsOutputSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("events", arrayOf(eventSchema()));
        properties.set("latestEventsByTicker", mapSchema(eventSchema()));
        properties.set("unresolvedEvents", arrayOf(eventSchema()));
        properties.set("nextCursor", nullableStringSchema());
        properties.set("hasMore", booleanSchema());
        properties.set("asOf", dateTimeSchema());
        properties.set("status", enumSchema("OK", "PARTIAL"));
        properties.set("source", stringSchema());
        properties.set("missingFields", arrayOf(stringSchema()));
        var success = closedObject(properties, "events", "latestEventsByTicker", "unresolvedEvents", "nextCursor",
                "hasMore", "asOf", "status", "source", "missingFields");
        return outputSchema(success, "INVALID_ARGUMENT", "INVESTMENT_RISK_EVENTS_UNAVAILABLE");
    }

    private ObjectNode eventSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("eventId", objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
        properties.set("incidentId", nullableStringSchema());
        properties.set("ticker", stringSchema());
        properties.set("ruleId", nullableStringSchema());
        properties.set("eventType", nullableStringSchema());
        properties.set("transition", nullableStringSchema());
        properties.set("severity", nullableStringSchema());
        properties.set("observedAt", dateTimeSchema());
        properties.set("sourceAsOf", nullableDateTimeSchema());
        properties.set("priceSource", nullableStringSchema());
        properties.set("priceSession", nullableStringSchema());
        properties.set("priceBasis", nullableStringSchema());
        properties.set("observedPrice", nullableNumberSchema());
        properties.set("triggerPrice", nullableNumberSchema());
        properties.set("regularCloseSessionDate", nullableDateSchema());
        properties.set("riskMarkStatus", nullableStringSchema());
        properties.set("riskMarkReason", nullableStringSchema());
        properties.set("thesisRevisionId", nullableStringSchema());
        properties.set("thesisRevision", nullableIntegerSchema());
        properties.set("invalidationStatus", nullableStringSchema());
        properties.set("reasonCode", nullableStringSchema());
        properties.set("sessionDate", nullableDateSchema());
        properties.set("previousState", nullableStringSchema());
        properties.set("currentState", stringSchema());
        properties.set("occurredAt", nullableDateTimeSchema());
        properties.set("resolvedAt", nullableDateTimeSchema());
        properties.set("persistedAt", dateTimeSchema());
        properties.set("dedupKey", nullableStringSchema());
        properties.set("latestObservation", objectMapper.createObjectNode().put("type", "object"));
        properties.set("relatedDecisions", arrayOf(decisionLinkSchema()));
        properties.set("relatedDecisionsTruncated", booleanSchema());
        properties.set("decisionLinkStatus", enumSchema("LINKED", "PRIOR_DECISION_UNAVAILABLE"));
        properties.set("missingFields", arrayOf(stringSchema()));
        return closedObject(properties, "eventId", "incidentId", "ticker", "ruleId", "eventType", "transition",
                "severity", "observedAt", "sourceAsOf", "priceSource", "priceSession", "priceBasis",
                "observedPrice", "triggerPrice", "regularCloseSessionDate", "riskMarkStatus", "riskMarkReason",
                "thesisRevisionId", "thesisRevision", "invalidationStatus", "reasonCode", "sessionDate",
                "previousState", "currentState", "occurredAt", "resolvedAt", "persistedAt", "dedupKey",
                "latestObservation", "relatedDecisions", "relatedDecisionsTruncated",
                "decisionLinkStatus", "missingFields");
    }

    private ObjectNode decisionLinkSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("decisionId", objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
        properties.set("decisionAsOf", dateTimeSchema());
        properties.set("ticker", stringSchema());
        properties.set("action", stringSchema());
        properties.set("priceSession", nullableStringSchema());
        properties.set("horizon", stringSchema());
        properties.set("rationale", stringSchema());
        properties.set("rationaleTruncated", booleanSchema());
        properties.set("invalidation", stringSchema());
        properties.set("invalidationTruncated", booleanSchema());
        properties.set("nextReviewTrigger", stringSchema());
        properties.set("nextReviewTriggerTruncated", booleanSchema());
        properties.set("confidence", nullableNumberSchema());
        properties.set("thesisRevisionId", nullableStringSchema());
        properties.set("linkedAt", dateTimeSchema());
        return closedObject(properties, "decisionId", "decisionAsOf", "ticker", "action", "priceSession",
                "horizon", "rationale", "rationaleTruncated", "invalidation", "invalidationTruncated",
                "nextReviewTrigger", "nextReviewTriggerTruncated", "confidence",
                "thesisRevisionId", "linkedAt");
    }

    private ObjectNode requiredTickerSchema() {
        var schema = investmentContextSchema();
        schema.putArray("required").add("ticker");
        return schema;
    }

    private ObjectNode investmentThemeUpsertSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("themeId", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 116).put("pattern", "^[A-Za-z0-9._:-]{1,116}$"));
        properties.set("name", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 120));
        properties.set("ticker", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 32).put("pattern", "^[A-Za-z0-9._-]{1,32}$"));
        properties.set("effectiveDate", objectMapper.createObjectNode().put("type", "string")
                .put("format", "date").put("pattern", "^\\d{4}-\\d{2}-\\d{2}$"));
        return closedObject(properties, "themeId", "name", "ticker", "effectiveDate");
    }

    private ObjectNode positionMandateUpsertSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 32).put("pattern", "^[A-Za-z0-9._-]{1,32}$"));
        properties.set("investmentHorizon", enumSchema("TACTICAL", "STRATEGIC"));
        properties.set("effectiveDate", objectMapper.createObjectNode().put("type", "string")
                .put("format", "date").put("pattern", "^\\d{4}-\\d{2}-\\d{2}$"));
        return closedObject(properties, "ticker", "investmentHorizon", "effectiveDate");
    }

    private ObjectNode positionMandateOutputSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", stringSchema());
        properties.set("investmentHorizon", enumSchema("TACTICAL", "STRATEGIC"));
        properties.set("riskPriceRole", enumSchema("EXIT_EVALUATION", "REVIEW_TRIGGER"));
        properties.set("source", enumSchema("USER_CONFIRMED"));
        properties.set("effectiveDate", objectMapper.createObjectNode().put("type", "string").put("format", "date"));
        properties.set("updatedAt", dateTimeSchema());
        return outputSchema(closedObject(properties, "ticker", "investmentHorizon", "riskPriceRole", "source",
                "effectiveDate", "updatedAt"), "TRADE_SCOPE_REQUIRED", "INVALID_ARGUMENT",
                "AUTHENTICATED_USER_INVALID", "INVESTMENT_STATE_NOT_FOUND", "INVESTMENT_CONTEXT_UNAVAILABLE",
                "INTERNAL_ERROR");
    }

    private ObjectNode investmentThemeUpsertOutputSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("themeId", stringSchema());
        properties.set("name", stringSchema());
        properties.set("ticker", stringSchema());
        properties.set("effectiveDate", objectMapper.createObjectNode().put("type", "string").put("format", "date"));
        properties.set("themeInputId", objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
        properties.set("themeSourceAsOf", nullableDateTimeSchema());
        properties.set("themeSource", stringSchema());
        properties.set("mappingInputId", objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
        properties.set("mappingSourceAsOf", nullableDateTimeSchema());
        properties.set("mappingSource", stringSchema());
        properties.set("changed", booleanSchema());
        var success = closedObject(properties, "themeId", "name", "ticker", "effectiveDate", "themeInputId",
                "themeSourceAsOf", "themeSource", "mappingInputId", "mappingSourceAsOf", "mappingSource", "changed");
        return outputSchema(success, "TRADE_SCOPE_REQUIRED", "INVALID_ARGUMENT", "AUTHENTICATED_USER_INVALID",
                "INVESTMENT_STATE_NOT_FOUND", "INVESTMENT_STATE_CONFLICT",
                "INVESTMENT_CONTEXT_UNAVAILABLE", "INTERNAL_ERROR");
    }

    private ObjectNode riskPreflightSchema() {
        var schema = requiredTickerSchema();
        var properties = (ObjectNode) schema.path("properties");
        properties.set("quantity", objectMapper.createObjectNode().put("type", "number")
                .put("exclusiveMinimum", 0)
                .put("description", "Positive JSON decimal; scale -18..10, at most 18 integer digits and 28 significant digits."));
        schema.putArray("required").add("ticker").add("quantity");
        return schema;
    }

    private ObjectNode investmentOnboardingOutputSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", stringSchema());
        properties.set("registered", booleanSchema());
        properties.set("status", stringSchema());
        properties.set("snapshotAsOf", nullableDateTimeSchema());
        properties.set("dataStatuses", mapSchema(stringSchema()));
        properties.set("dataAsOf", mapSchema(dateTimeSchema()));
        properties.set("collection", nullableSchema(pipelineSummarySchema()));
        properties.set("thesis", nullableSchema(thesisSummarySchema()));
        properties.set("riskMark", nullableSchema(riskMarkSummarySchema()));
        properties.set("candidates", arrayOf(candidateSchema()));
        properties.set("blockers", arrayOf(stringSchema()));
        var success = closedObject(properties, "ticker", "registered", "status", "snapshotAsOf",
                "dataStatuses", "dataAsOf", "collection", "thesis", "riskMark", "candidates", "blockers");
        return outputSchema(success, "INVALID_ARGUMENT", "AUTHENTICATED_USER_INVALID",
                "INVESTMENT_STATE_NOT_FOUND", "INVESTMENT_STATE_CONFLICT",
                "INVESTMENT_CONTEXT_UNAVAILABLE", "INTERNAL_ERROR");
    }

    private ObjectNode registrationOutputSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", stringSchema());
        properties.set("registered", booleanSchema());
        properties.set("alreadyRegistered", booleanSchema());
        properties.set("status", stringSchema());
        var success = closedObject(properties, "ticker", "registered", "alreadyRegistered", "status");
        return outputSchema(success, "TRADE_SCOPE_REQUIRED", "INVALID_ARGUMENT", "AUTHENTICATED_USER_INVALID",
                "INVESTMENT_STATE_NOT_FOUND", "INVESTMENT_STATE_CONFLICT",
                "INVESTMENT_CONTEXT_UNAVAILABLE", "INTERNAL_ERROR");
    }

    private ObjectNode pipelineSummarySchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("status", stringSchema());
        properties.set("lastAttemptAt", nullableDateTimeSchema());
        properties.set("lastSuccessAt", nullableDateTimeSchema());
        return closedObject(properties, "status", "lastAttemptAt", "lastSuccessAt");
    }

    private ObjectNode thesisSummarySchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("present", booleanSchema());
        properties.set("revision", nullableIntegerSchema());
        properties.set("invalidationStatus", nullableStringSchema());
        properties.set("receiptStatus", stringSchema());
        properties.set("verificationOutcome", nullableStringSchema());
        properties.set("verificationReason", nullableStringSchema());
        properties.set("receiptMatchesCurrentRevision", booleanSchema());
        properties.set("triggerConfigured", booleanSchema());
        properties.set("sizingEligible", booleanSchema());
        properties.set("eligibilityReasons", arrayOf(stringSchema()));
        return closedObject(properties, "present", "revision", "invalidationStatus", "receiptStatus",
                "verificationOutcome", "verificationReason", "receiptMatchesCurrentRevision",
                "triggerConfigured", "sizingEligible", "eligibilityReasons");
    }

    private ObjectNode riskMarkSummarySchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("value", nullableNumberSchema());
        properties.set("asOf", nullableDateTimeSchema());
        properties.set("source", nullableStringSchema());
        properties.set("basis", nullableStringSchema());
        properties.set("asOfBasis", nullableStringSchema());
        properties.set("status", stringSchema());
        properties.set("reason", nullableStringSchema());
        return closedObject(properties, "value", "asOf", "source", "basis", "asOfBasis", "status", "reason");
    }

    private ObjectNode candidateSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("source", stringSchema());
        properties.set("status", stringSchema());
        properties.set("reason", nullableStringSchema());
        properties.set("trigger", nullableNumberSchema());
        properties.set("sourceAsOf", nullableDateTimeSchema());
        properties.set("inputs", objectMapper.createObjectNode().put("type", "object")
                .put("additionalProperties", true));
        return closedObject(properties, "source", "status", "reason", "trigger", "sourceAsOf", "inputs");
    }

    private ObjectNode riskPreflightOutputSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", stringSchema());
        properties.set("quantity", numberSchema());
        properties.set("buyEligible", booleanSchema());
        properties.set("blockers", arrayOf(stringSchema()));
        properties.set("thesis", nullableSchema(thesisReceiptSchema()));
        properties.set("portfolio", nullableSchema(portfolioGateSchema()));
        properties.set("policy", nullableSchema(policyGateSchema()));
        properties.set("cash", nullableSchema(cashGateSchema()));
        properties.set("currentRisk", mapSchema(riskContributionSchema()));
        properties.set("proFormaRisk", mapSchema(riskContributionSchema()));
        var success = closedObject(properties, "ticker", "quantity", "buyEligible", "blockers", "thesis",
                "portfolio", "policy", "cash", "currentRisk", "proFormaRisk");
        return outputSchema(success, "INVALID_ARGUMENT", "AUTHENTICATED_USER_INVALID",
                "INVESTMENT_STATE_NOT_FOUND", "INVESTMENT_STATE_CONFLICT",
                "INVESTMENT_CONTEXT_UNAVAILABLE", "INTERNAL_ERROR");
    }

    private ObjectNode thesisReceiptSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("invalidationStatus", nullableStringSchema());
        properties.set("revision", nullableIntegerSchema());
        properties.set("revisionId", nullableUuidSchema());
        properties.set("actorType", nullableStringSchema());
        properties.set("reason", nullableStringSchema());
        properties.set("policyVersion", nullableStringSchema());
        properties.set("verificationEventId", nullableUuidSchema());
        properties.set("outcome", nullableStringSchema());
        properties.set("reasonCode", nullableStringSchema());
        properties.set("receiptRequired", booleanSchema());
        properties.set("receiptMatchesCurrentRevision", booleanSchema());
        return closedObject(properties, "invalidationStatus", "revision", "revisionId", "actorType", "reason",
                "policyVersion", "verificationEventId", "outcome", "reasonCode", "receiptRequired",
                "receiptMatchesCurrentRevision");
    }

    private ObjectNode portfolioGateSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("status", stringSchema());
        properties.set("riskNumbersAvailable", booleanSchema());
        properties.set("manualFresh", booleanSchema());
        properties.set("manualStatus", nullableStringSchema());
        properties.set("manualAsOf", nullableDateSchema());
        return closedObject(properties, "status", "riskNumbersAvailable", "manualFresh", "manualStatus",
                "manualAsOf");
    }

    private ObjectNode policyGateSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("status", stringSchema());
        properties.set("quantityWithinLimit", booleanSchema());
        properties.set("orderAmountWithinLimit", booleanSchema());
        properties.set("concentrationWithinLimit", booleanSchema());
        properties.set("maxQuantity", nullableNumberSchema());
        properties.set("maxOrderAmount", nullableNumberSchema());
        properties.set("maxConcentration", nullableNumberSchema());
        properties.set("softRiskBudget", nullableNumberSchema());
        return closedObject(properties, "status", "quantityWithinLimit", "orderAmountWithinLimit",
                "concentrationWithinLimit", "maxQuantity", "maxOrderAmount", "maxConcentration",
                "softRiskBudget");
    }

    private ObjectNode cashGateSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("status", stringSchema());
        properties.set("currency", nullableStringSchema());
        properties.set("availableBefore", nullableNumberSchema());
        properties.set("purchaseCost", nullableNumberSchema());
        properties.set("availableAfter", nullableNumberSchema());
        properties.set("source", nullableStringSchema());
        return closedObject(properties, "status", "currency", "availableBefore", "purchaseCost",
                "availableAfter", "source");
    }

    private ObjectNode riskContributionSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("portfolioWeight", nullableNumberSchema());
        properties.set("invalidationDownside", nullableNumberSchema());
        properties.set("plannedLossContribution", nullableNumberSchema());
        properties.set("status", stringSchema());
        properties.set("thesisFailureStress", nullableNumberSchema());
        properties.set("thesisFailureStressStatus", stringSchema());
        properties.set("top2CorrelatedStress", nullableNumberSchema());
        properties.set("top2CorrelatedAssets", nullableStringSchema());
        properties.set("top2Correlation", nullableNumberSchema());
        properties.set("top2CorrelatedStatus", stringSchema());
        properties.set("sizingEligible", booleanSchema());
        properties.set("softBudgetStatus", stringSchema());
        properties.set("eligibilityReasons", arrayOf(stringSchema()));
        properties.set("riskMark", nullableSchema(riskMarkViewSchema()));
        return closedObject(properties, "portfolioWeight", "invalidationDownside", "plannedLossContribution",
                "status", "thesisFailureStress", "thesisFailureStressStatus", "top2CorrelatedStress",
                "top2CorrelatedAssets", "top2Correlation", "top2CorrelatedStatus", "sizingEligible",
                "softBudgetStatus", "eligibilityReasons", "riskMark");
    }

    private ObjectNode riskMarkViewSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("value", nullableNumberSchema());
        properties.set("asOf", nullableDateTimeSchema());
        properties.set("source", nullableStringSchema());
        properties.set("basis", nullableStringSchema());
        properties.set("asOfBasis", nullableStringSchema());
        properties.set("status", stringSchema());
        properties.set("reason", nullableStringSchema());
        return closedObject(properties, "value", "asOf", "source", "basis", "asOfBasis", "status", "reason");
    }

    private ObjectNode outputSchema(ObjectNode success, String... errorCodes) {
        var output = objectMapper.createObjectNode().put("type", "object");
        output.putArray("anyOf").add(success).add(verificationErrorSchema(errorCodes));
        return output;
    }

    private ObjectNode closedObject(ObjectNode properties, String... required) {
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required");
        for (var field : required) schema.withArray("required").add(field);
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode mapSchema(JsonNode valueSchema) {
        return objectMapper.createObjectNode().put("type", "object").set("additionalProperties", valueSchema);
    }

    private ObjectNode arrayOf(JsonNode itemSchema) {
        return objectMapper.createObjectNode().put("type", "array").set("items", itemSchema);
    }

    private ObjectNode nullableSchema(JsonNode valueSchema) {
        var schema = objectMapper.createObjectNode();
        schema.putArray("anyOf").add(valueSchema).add(objectMapper.createObjectNode().put("type", "null"));
        return schema;
    }

    private ObjectNode nullableStringSchema() {
        return nullableString(stringSchema(), null);
    }

    private ObjectNode nullableNumberSchema() {
        return nullableSchema(numberSchema());
    }

    private ObjectNode nullableIntegerSchema() {
        return nullableSchema(objectMapper.createObjectNode().put("type", "integer"));
    }

    private ObjectNode nullableUuidSchema() {
        return nullableSchema(uuidSchema());
    }

    private ObjectNode nullableDateTimeSchema() {
        return nullableSchema(dateTimeSchema());
    }

    private ObjectNode nullableDateSchema() {
        return nullableSchema(objectMapper.createObjectNode().put("type", "string").put("format", "date"));
    }

    private ObjectNode dateTimeSchema() {
        return objectMapper.createObjectNode().put("type", "string").put("format", "date-time");
    }

    private ObjectNode stringSchema() {
        return objectMapper.createObjectNode().put("type", "string");
    }

    private ObjectNode numberSchema() {
        return objectMapper.createObjectNode().put("type", "number");
    }

    private ObjectNode booleanSchema() {
        return objectMapper.createObjectNode().put("type", "boolean");
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
        errorProperties.set("errorCategory", enumSchema(java.util.Arrays.stream(
                com.jmj.trade.broker.BrokerErrorCategory.values()).map(Enum::name).toArray(String[]::new)));
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
        fields.set("riskEventIds", objectMapper.createObjectNode().put("type", "array")
                .put("maxItems", 20).put("uniqueItems", true)
                .set("items", objectMapper.createObjectNode().put("type", "string").put("format", "uuid")));
        fields.set("targetRemainingQuantity", objectMapper.createObjectNode()
                .put("type", "number")
                .put("minimum", 0)
                .put("description", "Required together with targetBrokerConnectionId "
                        + "for a targeted REDUCE or EXIT decision."));
        fields.set("targetBrokerConnectionId", objectMapper.createObjectNode()
                .put("type", "string")
                .put("format", "uuid")
                .put("description", "Active user-owned broker connection. Must be paired "
                        + "with targetRemainingQuantity and one riskEventId."));
        var investmentHorizon = enumSchema("TACTICAL", "STRATEGIC", "UNKNOWN");
        investmentHorizon.put("description", "Persisted ticker mandate horizon; use UNKNOWN only when no mandate is available.");
        fields.set("investmentHorizon", investmentHorizon);
        var priceRiskState = enumSchema("UNKNOWN", "CLEAR", "NEAR", "INTRADAY_BREACH",
                "PRICE_RISK_BREACH", "RECOVERED");
        priceRiskState.put("description", "Current risk state; keep separate from the action decision.");
        fields.set("priceRiskState", priceRiskState);
        var thesisHealth = enumSchema("UNKNOWN", "INTACT", "IMPAIRED", "INVALIDATED");
        thesisHealth.put("description", "Current thesis assessment, independent of the price-risk state.");
        fields.set("thesisHealth", thesisHealth);
        fields.set("actionReason", objectMapper.createObjectNode().put("type", "string")
                .put("minLength", 1).put("maxLength", 2000)
                .put("description", "Caller-authored reason for HOLD, REDUCE, EXIT, or other action."));
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
        contextProperties.set("positionMandates", arrayOf(positionMandateViewSchema()));
        var context = objectMapper.createObjectNode().put("type", "object");
        context.set("properties", contextProperties);
        context.putArray("required").add("portfolio").add("securities").add("watchlist")
                .add("riskPolicy").add("decisionLedger").add("pipeline")
                .add("tacticalOverlay").add("decisionOverlays").add("reviewLog").add("positionMandates");
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

    private ObjectNode positionMandateViewSchema() {
        var properties = objectMapper.createObjectNode();
        properties.set("ticker", stringSchema());
        properties.set("investmentHorizon", enumSchema("TACTICAL", "STRATEGIC"));
        properties.set("riskPriceRole", enumSchema("EXIT_EVALUATION", "REVIEW_TRIGGER"));
        properties.set("source", enumSchema("USER_CONFIRMED"));
        properties.set("effectiveDate", objectMapper.createObjectNode().put("type", "string").put("format", "date"));
        properties.set("updatedAt", dateTimeSchema());
        return closedObject(properties, "ticker", "investmentHorizon", "riskPriceRole", "source",
                "effectiveDate", "updatedAt");
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
        var success = objectMapper.createObjectNode()
                .put("type", "object")
                .set("properties", properties);
        success.putArray("required").add(field);
        var schema = objectMapper.createObjectNode().put("type", "object");
        schema.putArray("anyOf").add(success).add(verificationErrorSchema(
                "INVALID_ARGUMENT", "TOSS_API_ERROR", "CONNECTOR_ERROR"));
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
