package com.jmj.trade.connector;

import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.investment.InvestmentDataCalculator;
import com.jmj.trade.investment.InvestmentOnboardingService;
import com.jmj.trade.investment.InvestmentRiskPreflightService;
import com.jmj.trade.order.McpOrderExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConnectorMcpInvestmentOnboardingTest {

    private static final UUID USER = UUID.fromString("01990000-0000-7000-8000-000000000021");
    private static final UUID CONNECTION = UUID.fromString("01990000-0000-7000-8000-000000000022");

    @Test
    void publishesReadToolsWithReadScopeAndRegistrationWithWriteScope() throws Exception {
        var mapper = new ObjectMapper();
        var onboarding = mock(InvestmentOnboardingService.class);
        var preflight = mock(InvestmentRiskPreflightService.class);
        var protocol = protocol(null, onboarding, preflight, mapper);

        var readTools = tools(protocol.handle(request("read-tools", "tools/list", "{}"), USER, CONNECTION));
        var readiness = findTool(readTools, "get_investment_onboarding_readiness");
        var risk = findTool(readTools, "preflight_investment_risk");
        assertReadTool(readiness);
        assertReadTool(risk);
        assertThat(readiness.path("securitySchemes").get(0).path("scopes").get(0).asText())
                .isEqualTo(ConnectorApiKeyService.READ_SCOPE);
        assertThat(risk.path("securitySchemes").get(0).path("scopes").get(0).asText())
                .isEqualTo(ConnectorApiKeyService.READ_SCOPE);
        assertThat(readiness.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(readiness.path("inputSchema").path("required").toString()).contains("ticker");
        assertThat(readiness.path("inputSchema").path("properties").path("ticker").path("pattern").asText())
                .isEqualTo("^[A-Za-z0-9._-]{1,32}$");
        assertThat(readiness.path("outputSchema").path("anyOf").size()).isEqualTo(2);
        assertThat(readiness.path("outputSchema").path("anyOf").get(0).path("properties").toString())
                .contains("dataAsOf", "thesis", "riskMark", "candidates", "blockers");
        assertThat(readiness.path("outputSchema").path("anyOf").get(0).path("properties")
                .path("candidates").path("items").path("properties").toString())
                .contains("trigger", "sourceAsOf", "inputs");
        assertThat(risk.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(risk.path("inputSchema").path("required").toString()).contains("ticker", "quantity");
        assertThat(risk.path("inputSchema").path("properties").path("ticker").path("pattern").asText())
                .isEqualTo("^[A-Za-z0-9._-]{1,32}$");
        assertThat(risk.path("inputSchema").path("properties").path("quantity").path("type").asText())
                .isEqualTo("number");
        assertThat(risk.path("inputSchema").path("properties").path("quantity").path("exclusiveMinimum").asInt())
                .isZero();
        assertThat(risk.path("inputSchema").path("properties").path("quantity").path("description").asText())
                .contains("scale -18..10", "18 integer digits", "28 significant digits");
        assertThat(risk.path("inputSchema").path("properties").has("executionPrice")).isFalse();
        assertThat(risk.path("outputSchema").path("anyOf").get(0).path("properties").toString())
                .contains("ticker", "quantity", "buyEligible", "thesis", "portfolio", "policy",
                        "cash", "currentRisk", "proFormaRisk");
        assertThat(readTools.toString()).doesNotContain("register_investment_candidate");

        var writeTools = tools(protocol.handle(request("write-tools", "tools/list", "{}"), USER, CONNECTION, true));
        var registration = findTool(writeTools, "register_investment_candidate");
        assertThat(registration.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(registration.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        assertThat(registration.path("annotations").path("idempotentHint").asBoolean()).isTrue();
        assertThat(registration.path("securitySchemes").get(0).path("scopes").get(0).asText())
                .isEqualTo(ConnectorApiKeyService.TRADE_SCOPE);
        assertThat(registration.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(registration.path("inputSchema").path("required").toString()).contains("ticker");
        assertThat(registration.path("outputSchema").path("anyOf").get(0).path("properties").toString())
                .contains("ticker", "registered", "alreadyRegistered", "status");
    }

    @Test
    void passesOnboardingAndRiskDtoFieldsThroughWithoutRecomputingOrExecutingOrders() throws Exception {
        var mapper = new ObjectMapper();
        var connector = mock(ConnectorService.class);
        var orders = mock(McpOrderExecutionService.class);
        var onboarding = mock(InvestmentOnboardingService.class);
        var preflight = mock(InvestmentRiskPreflightService.class);
        var protocol = protocol(connector, orders, onboarding, preflight, mapper);
        var asOf = Instant.parse("2026-10-10T13:40:00Z");

        var dataStatuses = Map.of("price", "STALE", "fundamentals", "DATA_MISSING");
        var dataAsOf = Map.of("price", asOf);
        var readinessView = new InvestmentOnboardingService.ReadinessView(
                "AAPL", true, "PARTIAL", asOf, dataStatuses, dataAsOf,
                new InvestmentOnboardingService.PipelineSummary("COMPLETE", asOf, asOf),
                new InvestmentOnboardingService.ThesisSummary(true, 4L, "CONFIRMED", "AI_RECEIPT_CURRENT_REVISION",
                        "AUTO_APPROVED", null, true, true, false, List.of("PRICE_STALE")),
                new InvestmentOnboardingService.RiskMarkSummary(null, asOf, "TOSS", "REGULAR_CLOSE",
                        "CLOSED_INTERVAL", "STALE", "MARK_STALE"),
                List.of(new com.jmj.trade.investment.ThesisCandidateGenerator.Candidate("WATCHLIST", "WATCH", null,
                        new BigDecimal("185.25"), asOf, Map.of("lookbackDays", 20))),
                List.of("PRICE_STALE"));
        var preflightView = preflightView(asOf);
        when(onboarding.readiness(USER, "AAPL")).thenReturn(readinessView);
        when(preflight.preflight(eq(USER), eq(new InvestmentRiskPreflightService.PreflightInput(
                "AAPL", new BigDecimal("1.2500"))))).thenReturn(preflightView);

        var readinessResponse = protocol.handle(toolCall("ready", "get_investment_onboarding_readiness",
                "{\"ticker\":\"aapl\"}"), USER, CONNECTION);
        var preflightResponse = protocol.handle(toolCall("risk", "preflight_investment_risk",
                "{\"ticker\":\"aapl\",\"quantity\":1.2500}"), USER, CONNECTION);

        assertSerializedExactly(readinessResponse, readinessView, mapper);
        assertSerializedExactly(preflightResponse, preflightView, mapper);
        assertThat(readinessResponse.path("result").path("structuredContent").path("riskMark")
                .path("value").isNull()).isTrue();
        assertThat(readinessResponse.path("result").path("structuredContent").path("riskMark")
                .path("reason").asText()).isEqualTo("MARK_STALE");
        assertThat(readinessResponse.path("result").path("structuredContent").path("dataStatuses")
                .path("price").asText()).isEqualTo("STALE");
        assertThat(readinessResponse.path("result").path("structuredContent").path("dataAsOf")
                .path("price").asText()).isEqualTo(asOf.toString());
        assertThat(readinessResponse.path("result").path("structuredContent").path("candidates").get(0)
                .path("trigger").decimalValue()).isEqualByComparingTo("185.25");
        assertThat(preflightResponse.path("result").path("structuredContent").path("currentRisk")
                .path("AAPL").path("status").asText()).isEqualTo("STALE");
        assertThat(preflightResponse.path("result").path("structuredContent").path("currentRisk")
                .path("AAPL").path("riskMark").path("value").isNull()).isTrue();
        verify(onboarding).readiness(USER, "AAPL");
        verify(preflight).preflight(USER, new InvestmentRiskPreflightService.PreflightInput(
                "AAPL", new BigDecimal("1.2500")));
        verifyNoInteractions(connector, orders);
    }

    @Test
    void registrationRequiresWriteScopeAndPassesIdempotentReceiptsThrough() throws Exception {
        var mapper = new ObjectMapper();
        var connector = mock(ConnectorService.class);
        var orders = mock(McpOrderExecutionService.class);
        var onboarding = mock(InvestmentOnboardingService.class);
        var protocol = protocol(connector, orders, onboarding, null, mapper);
        var registered = new InvestmentOnboardingService.RegistrationView("AAPL", true, false, "REGISTERED");
        var replay = new InvestmentOnboardingService.RegistrationView("AAPL", true, true, "ALREADY_REGISTERED");
        when(onboarding.register(USER, "AAPL")).thenReturn(registered, replay);

        var denied = protocol.handle(toolCall("denied", "register_investment_candidate",
                "{\"ticker\":\"AAPL\"}"), USER, CONNECTION);
        assertThat(denied.path("result").path("isError").asBoolean()).isTrue();
        assertThat(denied.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("TRADE_SCOPE_REQUIRED");
        verifyNoInteractions(onboarding);

        var first = protocol.handle(toolCall("first", "register_investment_candidate",
                "{\"ticker\":\"aapl\"}"), USER, CONNECTION, true);
        var second = protocol.handle(toolCall("second", "register_investment_candidate",
                "{\"ticker\":\"AAPL\"}"), USER, CONNECTION, true);
        assertSerializedExactly(first, registered, mapper);
        assertSerializedExactly(second, replay, mapper);
        verify(onboarding, org.mockito.Mockito.times(2)).register(USER, "AAPL");
        verifyNoInteractions(connector, orders);
    }

    @Test
    void acceptsQuantitiesAtServiceNumericBoundsAndPreservesExponentScale() throws Exception {
        var preflight = mock(InvestmentRiskPreflightService.class);
        var protocol = protocol(null, null, null, preflight, new ObjectMapper());
        when(preflight.preflight(eq(USER), org.mockito.ArgumentMatchers.any(
                InvestmentRiskPreflightService.PreflightInput.class))).thenReturn(preflightView(Instant.EPOCH));

        var maximum = new BigDecimal("999999999999999999.9999999999");
        var maximumResponse = protocol.handle(toolCall("max", "preflight_investment_risk",
                "{\"ticker\":\"AAPL\",\"quantity\":999999999999999999.9999999999}"), USER, CONNECTION);
        assertThat(maximumResponse.path("result").path("isError").asBoolean()).isFalse();
        verify(preflight).preflight(USER, new InvestmentRiskPreflightService.PreflightInput("AAPL", maximum));

        var minimumScale = new BigDecimal("1e-10");
        var minimumScaleResponse = protocol.handle(toolCall("min-scale", "preflight_investment_risk",
                "{\"ticker\":\"AAPL\",\"quantity\":1e-10}"), USER, CONNECTION);
        assertThat(minimumScaleResponse.path("result").path("isError").asBoolean()).isFalse();
        verify(preflight).preflight(USER, new InvestmentRiskPreflightService.PreflightInput("AAPL", minimumScale));
    }

    @Test
    void legacyConstructorRemainsUsableAndMissingOptionalServicesReturnUnavailable() throws Exception {
        var protocol = new ConnectorMcpProtocol(null, new ObjectMapper());

        var readiness = protocol.handle(toolCall("missing-readiness", "get_investment_onboarding_readiness",
                "{\"ticker\":\"AAPL\"}"), USER, CONNECTION);
        var preflight = protocol.handle(toolCall("missing-preflight", "preflight_investment_risk",
                "{\"ticker\":\"AAPL\",\"quantity\":1}"), USER, CONNECTION);
        var registration = protocol.handle(toolCall("missing-register", "register_investment_candidate",
                "{\"ticker\":\"AAPL\"}"), USER, CONNECTION, true);

        for (var response : List.of(readiness, preflight, registration)) {
            assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                    .isEqualTo("INVESTMENT_CONTEXT_UNAVAILABLE");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidToolArguments")
    void rejectsInvalidArgumentsBeforeCallingAnyInvestmentService(String tool, String arguments) throws Exception {
        var onboarding = mock(InvestmentOnboardingService.class);
        var preflight = mock(InvestmentRiskPreflightService.class);
        var protocol = protocol(null, onboarding, preflight, new ObjectMapper());
        var name = switch (tool) {
            case "readiness" -> "get_investment_onboarding_readiness";
            case "preflight" -> "preflight_investment_risk";
            default -> "register_investment_candidate";
        };

        var response = protocol.handle(toolCall("invalid", name, arguments), USER, CONNECTION,
                "registration".equals(tool));

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .doesNotContain("secret", "bad-field", "AAPL");
        verifyNoInteractions(onboarding, preflight);
    }

    private static Stream<Arguments> invalidToolArguments() {
        return Stream.of(
                Arguments.of("readiness", "[]"),
                Arguments.of("readiness", "null"),
                Arguments.of("readiness", "{}"),
                Arguments.of("readiness", "{\"ticker\":null}"),
                Arguments.of("readiness", "{\"ticker\":\"\"}"),
                Arguments.of("readiness", "{\"ticker\":7}"),
                Arguments.of("readiness", "{\"ticker\":\" AAPL \"}"),
                Arguments.of("readiness", "{\"ticker\":\"AAPL!\"}"),
                Arguments.of("readiness", "{\"ticker\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567\"}"),
                Arguments.of("readiness", "{\"ticker\":\"AAPL\",\"bad-field\":\"secret\"}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\"}"),
                Arguments.of("preflight", "{\"quantity\":1}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":0}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":-1}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":\"1\"}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":null}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":0.0}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":0.00000000001}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":1000000000000000000}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":1e+19}"),
                Arguments.of("preflight", "{\"ticker\":\"AAPL\",\"quantity\":1,\"executionPrice\":1}"),
                Arguments.of("registration", "{}"),
                Arguments.of("registration", "{\"ticker\":\"AAPL\",\"bad-field\":\"secret\"}"));
    }

    private static InvestmentRiskPreflightService.PreflightView preflightView(Instant asOf) {
        var mark = new InvestmentContextService.RiskMarkView(null, asOf, "TOSS", "REGULAR_CLOSE",
                "CLOSED_INTERVAL", InvestmentDataCalculator.DataStatus.STALE, "MARK_STALE");
        var contribution = new InvestmentContextService.RiskContributionView(
                null, null, null, InvestmentDataCalculator.DataStatus.STALE,
                null, InvestmentDataCalculator.DataStatus.UNVERIFIED,
                null, null, null, InvestmentDataCalculator.DataStatus.INSUFFICIENT_HISTORY,
                false, "UNEVALUATED", List.of("PRICE_STALE"), mark);
        return new InvestmentRiskPreflightService.PreflightView(
                "AAPL", new BigDecimal("1.2500"), false, List.of("PRICE_STALE"),
                new InvestmentRiskPreflightService.ThesisReceipt("CONFIRMED", 4L,
                        UUID.fromString("01990000-0000-7000-8000-000000000023"), "SESSION_USER",
                        "OWNER_CONFIRMED", "policy-v1", null, null, null, false, true),
                new InvestmentRiskPreflightService.PortfolioGate("PARTIAL", false, false,
                        "DATA_MISSING", null),
                new InvestmentRiskPreflightService.PolicyGate("UNEVALUATED", true, true,
                        true, new BigDecimal("10"), new BigDecimal("1000"),
                        new BigDecimal("0.15"), new BigDecimal("0.02")),
                new InvestmentRiskPreflightService.CashGate("DATA_MISSING", "USD",
                        null, null, null, null),
                Map.of("AAPL", contribution), Map.of("AAPL", contribution));
    }

    private static void assertSerializedExactly(ObjectNode response, Object expected, ObjectMapper mapper)
            throws Exception {
        var result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        var structured = mapper.valueToTree(expected);
        assertThat(result.path("structuredContent")).isEqualTo(structured);
        assertThat(result.path("content").get(0).path("text").asText())
                .isEqualTo(mapper.writeValueAsString(expected));
    }

    private static void assertReadTool(ObjectNode tool) {
        assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
    }

    private static tools.jackson.databind.JsonNode tools(ObjectNode response) {
        return response.path("result").path("tools");
    }

    private static ObjectNode findTool(tools.jackson.databind.JsonNode tools, String name) {
        for (var tool : tools) if (name.equals(tool.path("name").asText())) return (ObjectNode) tool;
        throw new AssertionError("MCP tool not found: " + name);
    }

    private static ConnectorMcpProtocol protocol(McpOrderExecutionService orders,
            InvestmentOnboardingService onboarding, InvestmentRiskPreflightService preflight,
            ObjectMapper mapper) {
        return protocol(null, orders, onboarding, preflight, mapper);
    }

    private static ConnectorMcpProtocol protocol(ConnectorService connector, McpOrderExecutionService orders,
            InvestmentOnboardingService onboarding, InvestmentRiskPreflightService preflight,
            ObjectMapper mapper) {
        return new ConnectorMcpProtocol(connector, orders, null, null, null,
                onboarding, preflight, mapper);
    }

    private static ObjectNode request(String id, String method, String params) throws Exception {
        var mapper = new ObjectMapper();
        var request = (ObjectNode) mapper.reader(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                + "\",\"method\":\"" + method + "\",\"params\":" + params + "}");
        return request;
    }

    private static ObjectNode toolCall(String id, String name, String arguments) throws Exception {
        return request(id, "tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}");
    }

}
