package com.jmj.trade.connector;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.jmj.trade.order.McpOrderExecutionService;
import com.jmj.trade.order.LiveOrderActivationException;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.investment.InvestmentContextService;
import org.springframework.dao.DataAccessResourceFailureException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;

class ConnectorMcpProtocolTest {
    @Test
    void cannotConfirmThesisThroughModelTool() throws Exception {
        var investment = mock(InvestmentContextService.class);
        var writer = new ConnectorMcpProtocol(service, null, investment, new ObjectMapper());
        var response = writer.handle(toolCall("confirm", "put_investment_thesis",
                "{\"ticker\":\"AVT\",\"thesis\":{\"coreThesis\":\"External\",\"invalidationStatus\":\"CONFIRMED\"}}"), USER, CONNECTION, true);
        assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("CONFIRMATION_REQUIRED");
        verifyNoInteractions(investment);
    }
    @Test
    void writesCallerThesisThroughExistingServiceOnlyWithWriteScope() throws Exception {
        var mapper = new ObjectMapper();
        var investment = mock(InvestmentContextService.class);
        var writer = new ConnectorMcpProtocol(service, null, investment, mapper);
        var args = "{\"ticker\":\"avt\",\"thesis\":{\"coreThesis\":\"External evidence\",\"invalidationStatus\":\"AI_PROPOSED\"}}";
        var denied = writer.handle(toolCall("denied", "put_investment_thesis", args), USER, CONNECTION);
        assertThat(denied.path("result").path("structuredContent").path("errorCode").asText()).isEqualTo("TRADE_SCOPE_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(investment);
        var saved = new InvestmentContextService.ThesisView("AVT", "External evidence", null, null,
                null, null, null, null, "AI_PROPOSED", null, null, null, Instant.now());
        when(investment.putThesisProposal(eq(USER), eq("AVT"), any(), org.mockito.ArgumentMatchers.isNull())).thenReturn(saved);
        var response = writer.handle(toolCall("write", "put_investment_thesis", args), USER, CONNECTION, true);
        assertThat(response.path("result").path("structuredContent").path("invalidationStatus").asText()).isEqualTo("AI_PROPOSED");
        verify(investment).putThesisProposal(eq(USER), eq("AVT"), argThat(input ->
                input.coreThesis().equals("External evidence") && input.invalidationStatus().equals("AI_PROPOSED")), org.mockito.ArgumentMatchers.isNull());
        verifyNoInteractions(service, tradeService);
    }

    @Test
    void listsAndWritesDecisionRecordWithTradeScopeEvenWhenOrdersAreDisabled() throws Exception {
        var mapper = new ObjectMapper();
        var investment = mock(InvestmentContextService.class);
        var disabledOrdersProtocol = new ConnectorMcpProtocol(service,
                (McpOrderExecutionService) null, investment, mapper);
        var listed = disabledOrdersProtocol.handle(request("decision-tools", "tools/list", "{}"),
                USER, CONNECTION, true);
        var tools = listed.path("result").path("tools");
        var writeTool = java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                .filter(tool -> "append_investment_decision".equals(tool.path("name").asText()))
                .findFirst().orElseThrow();
        assertThat(writeTool.path("securitySchemes").get(0).path("scopes").get(0).asText())
                .isEqualTo(ConnectorApiKeyService.TRADE_SCOPE);
        assertThat(writeTool.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(writeTool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        assertThat(writeTool.path("annotations").path("idempotentHint").asBoolean()).isTrue();
        assertThat(writeTool.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(writeTool.path("inputSchema").path("required").toString())
                .contains("decisionId", "asOf", "asset", "action", "referencePrice", "priceSession",
                        "horizon", "alphaThesis", "invalidation", "nextReviewTrigger", "confidence");

        var decisionId = UUID.fromString("018f0000-0000-7000-8000-000000000003");
        var asOf = Instant.parse("2026-10-08T15:04:05Z");
        var riskCheck = mapper.readTree("{\"status\":\"NOT_CONFIGURED\"}");
        var saved = new InvestmentContextService.DecisionView(decisionId, asOf, "AVT", "HOLD",
                new BigDecimal("123.45000000"), "REGULAR_CLOSE", "swing-5d", "caller thesis",
                "caller invalidation", "review after next close", new BigDecimal("0.75"), riskCheck, asOf);
        when(investment.recordDecision(eq(USER), any(InvestmentContextService.DecisionInput.class)))
                .thenReturn(saved);
        var response = disabledOrdersProtocol.handle(toolCall("append", "append_investment_decision",
                "{\"decisionId\":\"018f0000-0000-7000-8000-000000000003\","
                        + "\"asOf\":\"2026-10-08T15:04:05Z\",\"asset\":\"AVT\","
                        + "\"action\":\"HOLD\",\"referencePrice\":123.45,"
                        + "\"priceSession\":\"REGULAR_CLOSE\",\"horizon\":\"swing-5d\","
                        + "\"alphaThesis\":\"caller thesis\",\"invalidation\":\"caller invalidation\","
                        + "\"nextReviewTrigger\":\"review after next close\",\"confidence\":0.75}"),
                USER, CONNECTION, true);

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.path("result").path("structuredContent").path("decisionId").asText())
                .isEqualTo(decisionId.toString());
        assertThat(response.path("result").path("structuredContent").path("asset").asText()).isEqualTo("AVT");
        assertThat(response.path("result").path("structuredContent").path("action").asText()).isEqualTo("HOLD");
        verify(investment).recordDecision(eq(USER), argThat(input ->
                decisionId.equals(input.decisionId()) && asOf.equals(input.asOf())
                        && "AVT".equals(input.asset()) && "HOLD".equals(input.action())
                        && input.referencePrice().compareTo(new BigDecimal("123.45")) == 0
                        && "REGULAR_CLOSE".equals(input.priceSession())
                        && "swing-5d".equals(input.horizon())
                        && "caller thesis".equals(input.alphaThesis())
                        && "caller invalidation".equals(input.invalidation())
                        && "review after next close".equals(input.nextReviewTrigger())
                        && input.confidence().compareTo(new BigDecimal("0.75")) == 0));
        verifyNoInteractions(service);
    }

    @Test
    void decisionWriteSeparatesScopeAndArgumentFailures() throws Exception {
        var investment = mock(InvestmentContextService.class);
        var writer = new ConnectorMcpProtocol(service, (McpOrderExecutionService) null,
                investment, new ObjectMapper());
        var validArgs = "{\"decisionId\":\"018f0000-0000-7000-8000-000000000004\","
                + "\"asOf\":\"2026-10-08T15:04:05Z\",\"asset\":\"AVT\",\"action\":\"HOLD\","
                + "\"referencePrice\":123.45,\"priceSession\":\"REGULAR_CLOSE\",\"horizon\":\"5d\","
                + "\"alphaThesis\":\"caller thesis\",\"invalidation\":\"caller supplied\","
                + "\"nextReviewTrigger\":\"caller supplied\",\"confidence\":0.5}";
        var denied = writer.handle(toolCall("denied-decision", "append_investment_decision", validArgs),
                USER, CONNECTION);
        assertThat(denied.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("TRADE_SCOPE_REQUIRED");

        var invalid = writer.handle(toolCall("invalid-decision", "append_investment_decision",
                validArgs.substring(0, validArgs.length() - 1) + ",\"unknown\":true}"), USER, CONNECTION, true);
        assertThat(invalid.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");

        var invalidEnum = writer.handle(toolCall("invalid-enum", "append_investment_decision",
                validArgs.replace("\"action\":\"HOLD\"", "\"action\":\"hold\"")),
                USER, CONNECTION, true);
        assertThat(invalidEnum.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");

        var legacyAction = writer.handle(toolCall("legacy-action", "append_investment_decision",
                validArgs.replace("\"action\":\"HOLD\"", "\"action\":\"REVIEW\"")),
                USER, CONNECTION, true);
        var legacySession = writer.handle(toolCall("legacy-session", "append_investment_decision",
                validArgs.replace("\"priceSession\":\"REGULAR_CLOSE\"", "\"priceSession\":\"PORTFOLIO_VALUE\"")),
                USER, CONNECTION, true);
        var missingPrice = writer.handle(toolCall("missing-price", "append_investment_decision",
                validArgs.replace("\"referencePrice\":123.45", "\"referencePrice\":null")),
                USER, CONNECTION, true);
        assertThat(legacyAction.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(legacySession.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(missingPrice.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");
        verifyNoInteractions(investment);
    }

    @Test
    void decisionWriteReturnsConflictAndValidationAsDistinctSafeErrors() throws Exception {
        var investment = mock(InvestmentContextService.class);
        var writer = new ConnectorMcpProtocol(service, (McpOrderExecutionService) null,
                investment, new ObjectMapper());
        var validArgs = "{\"decisionId\":\"018f0000-0000-7000-8000-000000000005\","
                + "\"asOf\":\"2026-10-08T15:04:05Z\",\"asset\":\"AVT\",\"action\":\"HOLD\","
                + "\"referencePrice\":123.45,\"priceSession\":\"REGULAR_CLOSE\",\"horizon\":\"5d\","
                + "\"alphaThesis\":\"caller thesis\",\"invalidation\":\"caller supplied\","
                + "\"nextReviewTrigger\":\"caller supplied\",\"confidence\":0.5}";
        when(investment.recordDecision(eq(USER), any(InvestmentContextService.DecisionInput.class)))
                .thenThrow(new com.jmj.trade.investment.InvestmentException(
                        com.jmj.trade.investment.InvestmentException.Code.CONFLICT));
        var conflict = writer.handle(toolCall("conflict", "append_investment_decision", validArgs),
                USER, CONNECTION, true);
        assertThat(conflict.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("DECISION_CONFLICT");

        when(investment.recordDecision(eq(USER), any(InvestmentContextService.DecisionInput.class)))
                .thenThrow(new com.jmj.trade.investment.InvestmentException(
                        com.jmj.trade.investment.InvestmentException.Code.INVALID_INPUT));
        var invalid = writer.handle(toolCall("validation", "append_investment_decision", validArgs),
                USER, CONNECTION, true);
        assertThat(invalid.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");
    }


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
        assertThat(tools.size()).isEqualTo(5);
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
        assertThat(tools.get(3).path("name").asText()).isEqualTo("get_order");
        var contextTool = tools.get(4);
        assertThat(contextTool.path("name").asText()).isEqualTo("get_investment_context");
        assertThat(contextTool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        assertThat(contextTool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        assertThat(contextTool.path("securitySchemes").get(0).path("scopes").get(0).asText())
                .isEqualTo(ConnectorApiKeyService.READ_SCOPE);
        var inputSchema = contextTool.path("inputSchema");
        assertThat(inputSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(inputSchema.path("properties").path("ticker").path("type").asText()).isEqualTo("string");
        assertThat(inputSchema.path("properties").path("ticker").path("pattern").asText())
                .isEqualTo("^[A-Za-z0-9._-]{1,32}$");
        assertThat(inputSchema.path("required").isMissingNode()).isTrue();
        var outputSchema = contextTool.path("outputSchema");
        assertThat(outputSchema.path("type").asText()).isEqualTo("object");
        assertThat(outputSchema.path("anyOf").size()).isEqualTo(2);
        assertThat(outputSchema.path("anyOf").get(0).path("properties").has("portfolio")).isTrue();
        assertThat(outputSchema.path("anyOf").get(0).path("properties").has("decisionOverlays")).isTrue();
        assertThat(outputSchema.path("anyOf").get(1).path("properties").path("errorCode")
                .path("enum").toString()).contains("INVESTMENT_CONTEXT_UNAVAILABLE");
        for (var tool : tools) {
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool.path("annotations").path("openWorldHint").asBoolean()).isFalse();
            assertThat(tool.path("annotations").path("idempotentHint").asBoolean()).isTrue();
        }
    }

    @Test
    void listsTradeToolsOnlyWhenTradeScopeIsGranted() throws Exception {
        var response = protocol.handle(request("trade", "tools/list", "{}"), USER, CONNECTION, true);

        var tools = response.path("result").path("tools");
        assertThat(tools.size()).isEqualTo(8);
        assertThat(tools.get(4).path("name").asText()).isEqualTo("get_investment_context");
        assertThat(tools.get(5).path("name").asText()).isEqualTo("prepare_order");
        assertThat(tools.get(6).path("name").asText()).isEqualTo("submit_order");
        assertThat(tools.get(7).path("name").asText()).isEqualTo("cancel_order");
        assertThat(tools.get(5).path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(tools.get(6).path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(tools.get(6).path("inputSchema").path("required").toString()).contains("proposalId");
    }

    @Test
    void listsTradeToolsForTradeScopeWhenLiveExecutionIsDisabled() throws Exception {
        var disabledProtocol = new ConnectorMcpProtocol(service, (McpOrderExecutionService) null,
                new ObjectMapper());

        var response = disabledProtocol.handle(request("trade-disabled", "tools/list", "{}"),
                USER, CONNECTION, true);

        var tools = response.path("result").path("tools");
        assertThat(tools.size()).isEqualTo(8);
        assertThat(tools.get(4).path("name").asText()).isEqualTo("get_investment_context");
        assertThat(tools.get(5).path("name").asText()).isEqualTo("prepare_order");
        assertThat(tools.get(6).path("name").asText()).isEqualTo("submit_order");
        assertThat(tools.get(7).path("name").asText()).isEqualTo("cancel_order");
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
                Arguments.of("cancel_order", "{\"brokerOrderId\":\"broker-1\"}"));
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
    void readScopeCallsOrderLookupWithoutTradeExecution() throws Exception {
        when(service.order(USER, CONNECTION, "broker-1", null)).thenReturn(new ConnectorResponse.Order(
                "broker-1", ConnectorResponse.BrokerOrderSide.BUY, ConnectorResponse.BrokerOrderType.LIMIT,
                "AAPL", BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("180"), "USD",
                ConnectorResponse.BrokerOrderLifecycle.PENDING, ConnectorResponse.BrokerOrderGroup.OPEN,
                null, null, null, null));

        var response = protocol.handle(toolCall("read-order", "get_order", "{\"brokerOrderId\":\"broker-1\"}"),
                USER, CONNECTION);

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.path("result").path("structuredContent").path("brokerOrderId").asText())
                .isEqualTo("broker-1");
        verify(service).order(USER, CONNECTION, "broker-1", null);
        verify(tradeService, org.mockito.Mockito.never()).getOrder(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void returnsPersistedInvestmentContextWithoutChangingItsStatusesOrNulls() throws Exception {
        var mapper = new ObjectMapper();
        var contextService = mock(InvestmentContextService.class);
        var expected = exampleContext(mapper);
        when(contextService.context(USER)).thenReturn(expected);
        var contextProtocol = new ConnectorMcpProtocol(service, tradeService, contextService, mapper);

        var response = contextProtocol.handle(toolCall("context-full", "get_investment_context", "{}"),
                USER, CONNECTION);
        var structured = response.path("result").path("structuredContent");
        var serializedText = response.path("result").path("content").get(0).path("text").asText();

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(structured).isEqualTo(mapper.valueToTree(expected));
        assertThat(mapper.readTree(serializedText)).isEqualTo(structured);
        assertThat(structured.path("securities").get(0).path("price").path("status").asText())
                .isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(structured.path("securities").get(0).path("tacticalOverlay").path("performance")
                .path("currentR").path("status").asText()).isEqualTo("STALE");
        assertThat(structured.path("tacticalOverlay").path("status").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(structured.path("securities").get(0).path("tacticalOverlay").path("anchoredVwaps").isNull())
                .isTrue();
        verify(contextService).context(USER);
        org.mockito.Mockito.verifyNoInteractions(service, tradeService);
    }

    @Test
    void tickerFilterChangesOnlyTheSecuritiesArray() throws Exception {
        var mapper = new ObjectMapper();
        var contextService = mock(InvestmentContextService.class);
        var expected = exampleContext(mapper);
        when(contextService.context(USER)).thenReturn(expected);
        var contextProtocol = new ConnectorMcpProtocol(service, tradeService, contextService, mapper);

        var response = contextProtocol.handle(toolCall("context-filter", "get_investment_context",
                "{\"ticker\":\"aapl\"}"), USER, CONNECTION);
        var actual = (ObjectNode) response.path("result").path("structuredContent");
        var expectedFiltered = (ObjectNode) mapper.valueToTree(expected);
        expectedFiltered.set("securities", mapper.createArrayNode().add(mapper.valueToTree(expected.securities().get(0))));

        assertThat(actual).isEqualTo(expectedFiltered);
        assertThat(actual.path("securities").size()).isEqualTo(1);
        assertThat(actual.path("securities").get(0).path("ticker").asText()).isEqualTo("AAPL");
        verify(contextService).context(USER);
    }

    @ParameterizedTest
    @MethodSource("invalidInvestmentContextArguments")
    void rejectsMalformedInvestmentContextArgumentsWithoutEchoingInput(String arguments) throws Exception {
        var contextService = mock(InvestmentContextService.class);
        var contextProtocol = new ConnectorMcpProtocol(service, tradeService, contextService, new ObjectMapper());

        var response = contextProtocol.handle(toolCall("context-invalid", "get_investment_context", arguments),
                USER, CONNECTION);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .doesNotContain("secret", "AAPL", "bad-field");
        org.mockito.Mockito.verifyNoInteractions(contextService);
    }

    private static Stream<Arguments> invalidInvestmentContextArguments() {
        return Stream.of(
                Arguments.of("[]"),
                Arguments.of("null"),
                Arguments.of("{\"ticker\":7}"),
                Arguments.of("{\"ticker\":\"   \"}"),
                Arguments.of("{\"ticker\":\" AAPL \"}"),
                Arguments.of("{\"ticker\":\"AAPL!\"}"),
                Arguments.of("{\"bad-field\":\"secret\"}"));
    }

    @Test
    void rejectsUnknownTickerWithoutReturningSecurityData() throws Exception {
        var mapper = new ObjectMapper();
        var contextService = mock(InvestmentContextService.class);
        when(contextService.context(USER)).thenReturn(exampleContext(mapper));
        var contextProtocol = new ConnectorMcpProtocol(service, tradeService, contextService, mapper);

        var response = contextProtocol.handle(toolCall("context-unknown", "get_investment_context",
                "{\"ticker\":\"secret-ticker\"}"), USER, CONNECTION);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .doesNotContain("secret-ticker");
        assertThat(response.path("result").path("structuredContent").path("securities").isMissingNode())
                .isTrue();
    }

    @Test
    void reportsUnavailableContextAsRetryableAndSanitizesDatabaseFailure() throws Exception {
        var absentService = new ConnectorMcpProtocol(service, tradeService, (InvestmentContextService) null,
                new ObjectMapper());
        var unavailable = absentService.handle(
                toolCall("context-unavailable", "get_investment_context", "{}"), USER, CONNECTION);
        assertContextUnavailable(unavailable);

        var contextService = mock(InvestmentContextService.class);
        when(contextService.context(USER)).thenThrow(new DataAccessResourceFailureException("secret database URL"));
        var contextProtocol = new ConnectorMcpProtocol(service, tradeService, contextService, new ObjectMapper());
        var failedRead = contextProtocol.handle(
                toolCall("context-db-failure", "get_investment_context", "{}"), USER, CONNECTION);
        assertContextUnavailable(failedRead);
        assertThat(failedRead.path("result").path("content").get(0).path("text").asText())
                .doesNotContain("secret database URL");
    }

    @Test
    void reportsSanitizedInternalContextFailure() throws Exception {
        var contextService = mock(InvestmentContextService.class);
        when(contextService.context(USER)).thenThrow(new IllegalStateException("secret internal details"));
        var contextProtocol = new ConnectorMcpProtocol(service, tradeService, contextService, new ObjectMapper());

        var response = contextProtocol.handle(toolCall("context-internal", "get_investment_context", "{}"),
                USER, CONNECTION);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INTERNAL_ERROR");
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .doesNotContain("secret internal details");
    }

    private static void assertContextUnavailable(ObjectNode response) {
        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("INVESTMENT_CONTEXT_UNAVAILABLE");
        assertThat(response.path("result").path("structuredContent").path("retryable").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("reauthorizationRequired").asBoolean())
                .isFalse();
    }

    private static InvestmentContextService.ContextView exampleContext(ObjectMapper mapper) throws Exception {
        var price = mapper.readTree("{\"latestPrice\":null,\"status\":\"INSUFFICIENT_HISTORY\"}");
        var performance = mapper.readTree("{\"currentR\":{\"value\":null,\"status\":\"STALE\"}}");
        var securityOverlay = new InvestmentContextService.SecurityTacticalOverlayView(
                "PARTIAL", "MISSING_INPUT", null, null, null, null, null,
                "CALCULATED", Instant.parse("2026-10-07T00:00:00Z"), null, "TACTICAL_V1",
                null, null, null, null, performance);
        var security = new InvestmentContextService.SecurityView(
                "AAPL", null, null, price, null, null, null, null, null, null, null, null, securityOverlay);
        var portfolio = new InvestmentContextService.PortfolioView(
                null, List.of(), Map.of(), false, List.of("price.latestPrice"), "PARTIAL");
        return new InvestmentContextService.ContextView(
                portfolio, List.of(security, new InvestmentContextService.SecurityView(
                "MSFT", null, null, null, null, null, null, null, null, null, null, null)),
                List.of(), null, List.of(), null,
                InvestmentContextService.TacticalOverlayPortfolioView.notConfigured(), Map.of());
    }

    @Test
    void exposesSafeLiveOrderBlockReason() throws Exception {
        when(tradeService.prepare(eq(USER), eq(CONNECTION), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new LiveOrderActivationException(
                        LiveOrderActivationException.Code.SAFETY_BLOCKED,
                        "live account mapping is missing or ambiguous"));

        var response = protocol.handle(toolCall("6b", "prepare_order",
                "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"quantity\":1,\"price\":180}"),
                USER, CONNECTION, true);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .contains("live account mapping is missing or ambiguous");
        assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("ORDER_BLOCKED");
    }

    @Test
    void returnsHeadlessStructuredErrorForRetryableBrokerFailure() throws Exception {
        when(service.orders(USER, CONNECTION, "OPEN")).thenThrow(new BrokerException(
                BrokerErrorCategory.NETWORK, null, null, null, null, true, "Toss timeout"));

        var response = protocol.handle(toolCall("broker-failure", "get_orders", "{}"), USER, CONNECTION);

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("errorCode").asText())
                .isEqualTo("TOSS_API_ERROR");
        assertThat(response.path("result").path("structuredContent").path("retryable").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("reauthorizationRequired").asBoolean())
                .isFalse();
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
