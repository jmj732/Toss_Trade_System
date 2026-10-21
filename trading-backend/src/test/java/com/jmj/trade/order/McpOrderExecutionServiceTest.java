package com.jmj.trade.order;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpOrderExecutionServiceTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID INTENT = UUID.randomUUID();
    private static final UUID DECISION = UUID.randomUUID();
    private static final UUID RISK_EVENT = UUID.randomUUID();

    @Test
    void prepareCreatesReadyProposalWithoutCallingBrokerWrite() {
        var activation = mock(LiveOrderActivationService.class);
        var risk = mock(PreTradeRiskEngine.class);
        var portfolios = mock(FreshPortfolioReadService.class);
        var quotes = mock(BrokerAdapter.class);
        var orders = mock(BrokerOrderPort.class);
        var safety = mock(LiveOrderSafetyLedger.class);
        var intents = mock(OrderIntentRepository.class);
        var brokerOrders = mock(BrokerOrderRepository.class);
        var attempts = mock(SubmissionAttemptRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(USER), eq(CONNECTION)))
                .thenReturn(List.of(ACCOUNT));
        when(jdbc.query(contains("FOR UPDATE"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(CONNECTION), eq(USER))).thenReturn(List.of(CONNECTION));
        when(safety.resolve(USER, CONNECTION, ACCOUNT)).thenReturn(
                new com.jmj.trade.broker.BrokerAccountRef(CONNECTION, "01", "LIVE", "****0001"));
        var portfolio = new PortfolioReadService.PortfolioView(UUID.randomUUID(), Instant.now(), false, null, false,
                List.of(), List.of(), null, List.of(), Map.of("USD",
                new PortfolioReadService.BuyingPowerView(new BigDecimal("1000"), Instant.now())));
        when(portfolios.read(USER, CONNECTION)).thenReturn(portfolio);
        when(quotes.getQuote(any(BrokerConnectionRef.class), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(new BrokerConnectionRef(CONNECTION), "AAPL", Currency.USD,
                        new BigDecimal("180"), new BigDecimal("179.9"), new BigDecimal("180.1"), null, Instant.now()),
                BrokerOrderPort.localMetadata()));
        when(orders.getOrders(any(), any())).thenReturn(new BrokerResponse<>(List.of(), BrokerOrderPort.localMetadata()));
        when(risk.preview(any())).thenReturn(new PreTradeRiskEngine.Decision(UUID.randomUUID(),
                PreTradeRiskEngine.Phase.APPROVAL, true, List.of(), UUID.randomUUID(), new BigDecimal("180.1"),
                Currency.USD, 1, Instant.now()));
        var intent = OrderIntent.proposedLive(INTENT, ACCOUNT, USER, CONNECTION,
                com.jmj.trade.order.OrderSide.BUY, com.jmj.trade.order.OrderType.LIMIT,
                "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD);
        intent.stampProposal(Instant.now(), Instant.now().plusSeconds(300));
        when(activation.propose(eq(USER), any(), any())).thenReturn(INTENT);
        when(intents.findOwnedById(INTENT, USER, CONNECTION)).thenReturn(Optional.of(intent));

        var service = new McpOrderExecutionService(activation, risk, portfolios, quotes, orders, safety,
                intents, brokerOrders, attempts, jdbc, mock(UnknownAttemptReconciler.class), mock(OrderSubmissionService.class));
        var result = service.prepare(USER, CONNECTION,
                new McpOrderExecutionService.PrepareCommand("aapl", "buy", "limit", BigDecimal.ONE,
                        new BigDecimal("180")));

        assertThat(result.status()).isEqualTo("READY_FOR_APPROVAL");
        assertThat(result.proposalId()).isEqualTo("ordp_" + INTENT);
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test
    void prepareRejectsMarketPriceBeforeAnyAccountLookup() {
        var service = new McpOrderExecutionService(mock(LiveOrderActivationService.class), mock(PreTradeRiskEngine.class),
                mock(FreshPortfolioReadService.class), mock(BrokerAdapter.class), mock(BrokerOrderPort.class),
                mock(LiveOrderSafetyLedger.class), mock(OrderIntentRepository.class), mock(BrokerOrderRepository.class),
                mock(SubmissionAttemptRepository.class), mock(JdbcTemplate.class), mock(UnknownAttemptReconciler.class), mock(OrderSubmissionService.class));

        assertThatThrownBy(() -> service.prepare(USER, CONNECTION,
                new McpOrderExecutionService.PrepareCommand("AAPL", "BUY", "MARKET", BigDecimal.ONE,
                BigDecimal.ONE))).hasMessageContaining("MARKET price");
    }

    @Test
    void submitExpiredProposalNeverTouchesBroker() {
        var activation = mock(LiveOrderActivationService.class);
        var intents = mock(OrderIntentRepository.class);
        var brokerOrders = mock(BrokerOrderRepository.class);
        var attempts = mock(SubmissionAttemptRepository.class);
        var intent = OrderIntent.proposedLive(INTENT, ACCOUNT, USER, CONNECTION,
                OrderSide.BUY, OrderType.LIMIT, "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD);
        intent.stampProposal(Instant.now().minusSeconds(10), Instant.now().minusSeconds(1));
        when(intents.findOwnedById(INTENT, USER, CONNECTION)).thenReturn(Optional.of(intent));
        when(brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(INTENT)).thenReturn(Optional.empty());
        when(attempts.findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(eq(INTENT), anyString()))
                .thenReturn(Optional.empty());
        var orders = mock(BrokerOrderPort.class);
        var service = new McpOrderExecutionService(activation, mock(PreTradeRiskEngine.class),
                mock(FreshPortfolioReadService.class), mock(BrokerAdapter.class), orders,
                mock(LiveOrderSafetyLedger.class), intents, brokerOrders, attempts, mock(JdbcTemplate.class),
                mock(UnknownAttemptReconciler.class), mock(OrderSubmissionService.class));

        var result = service.submit(USER, CONNECTION, "ordp_" + INTENT);

        assertThat(result.status()).isEqualTo("EXPIRED");
        verify(activation).expireFromMcp(eq(USER), eq(INTENT), anyString());
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test
    void duplicateSubmitReplaysPersistedBrokerOrder() {
        var intents = mock(OrderIntentRepository.class);
        var brokerOrders = mock(BrokerOrderRepository.class);
        var intent = OrderIntent.proposedLive(INTENT, ACCOUNT, USER, CONNECTION,
                OrderSide.BUY, OrderType.LIMIT, "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD);
        when(intents.findOwnedById(INTENT, USER, CONNECTION)).thenReturn(Optional.of(intent));
        when(brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(INTENT)).thenReturn(Optional.of(
                BrokerOrder.confirmed(UUID.randomUUID(), INTENT, ACCOUNT, "toss-1", "mcp-client",
                        BrokerOrderStatus.PENDING)));
        var orders = mock(BrokerOrderPort.class);
        var service = new McpOrderExecutionService(mock(LiveOrderActivationService.class), mock(PreTradeRiskEngine.class),
                mock(FreshPortfolioReadService.class), mock(BrokerAdapter.class), orders, mock(LiveOrderSafetyLedger.class),
                intents, brokerOrders, mock(SubmissionAttemptRepository.class), mock(JdbcTemplate.class),
                mock(UnknownAttemptReconciler.class), mock(OrderSubmissionService.class));

        var result = service.submit(USER, CONNECTION, "ordp_" + INTENT);

        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.brokerOrderId()).isEqualTo("toss-1");
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test
    void riskLinkedSubmitRevalidatesAndUsesVerifiedOrderBookPriceWithoutQuoteFallback() throws Exception {
        var activation = mock(LiveOrderActivationService.class);
        var intents = mock(OrderIntentRepository.class);
        var brokerOrders = mock(BrokerOrderRepository.class);
        var attempts = mock(SubmissionAttemptRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var readiness = mock(com.jmj.trade.investment.InvestmentSellReadinessService.class);
        var quotes = mock(BrokerAdapter.class);
        var intent = OrderIntent.proposedLive(INTENT, ACCOUNT, USER, CONNECTION,
                OrderSide.SELL, OrderType.LIMIT, "LUNR", new BigDecimal("2"), new BigDecimal("10.50"), Currency.USD);
        intent.stampProposal(Instant.now(), Instant.now().plusSeconds(300));
        intent.stampProposalReferencePrice(new BigDecimal("10.50"));
        when(intents.findOwnedById(INTENT, USER, CONNECTION)).thenReturn(Optional.of(intent));
        when(brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(INTENT)).thenReturn(Optional.empty(),
                Optional.of(BrokerOrder.confirmed(UUID.randomUUID(), INTENT, ACCOUNT, "toss-verified", "mcp-client",
                        BrokerOrderStatus.PENDING)));
        when(attempts.findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(eq(INTENT), anyString()))
                .thenReturn(Optional.empty());
        var observedAt = Instant.now();
        when(jdbc.query(contains("SELECT investment_decision_id"),
                any(org.springframework.jdbc.core.RowMapper.class), eq(INTENT), eq(USER), eq(CONNECTION)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    var rowMapper = (org.springframework.jdbc.core.RowMapper<Object>) invocation.getArgument(1);
                    var row = mock(ResultSet.class);
                    when(row.getObject("investment_decision_id")).thenReturn(DECISION);
                    when(row.getObject("investment_risk_event_id")).thenReturn(RISK_EVENT);
                    return List.of(rowMapper.mapRow(row, 0));
                });
        var readinessResult = new com.jmj.trade.investment.InvestmentSellReadinessService.Readiness(
                "READY", "READY", "READY", "READY", DECISION, RISK_EVENT, "LUNR", "REDUCE", observedAt,
                UUID.randomUUID(), new BigDecimal("2"), BigDecimal.ONE, observedAt,
                new BigDecimal("10.50"), new BigDecimal("10.60"), new BigDecimal("10.50"),
                new BigDecimal("0.10"), new BigDecimal("94.79"), "LIVE_REGULAR", "TOSS", "USD",
                observedAt, observedAt, "/api/v1/market-calendar/US", "official-capability-source",
                new BigDecimal("0.01"), List.of(), observedAt);
        when(readiness.evaluate(USER, CONNECTION, DECISION, RISK_EVENT, INTENT)).thenReturn(readinessResult);
        when(activation.dispatchFromMcpWithVerifiedReference(eq(USER), eq(INTENT), anyString(),
                eq("MCP:" + USER), eq(new BigDecimal("10.50"))))
                .thenReturn(new LiveOrderActivationService.DispatchResult(UUID.randomUUID(),
                        SubmissionAttemptStatus.ACKNOWLEDGED, OrderIntentStatus.SUBMISSION_PENDING, null));

        var portfolio = new PortfolioReadService.PortfolioView(UUID.randomUUID(), observedAt, false, null,
                false, List.of(), List.of(), null, List.of(), Map.of());
        var portfolios = mock(FreshPortfolioReadService.class);
        when(portfolios.read(USER, CONNECTION)).thenReturn(portfolio);
        var service = new McpOrderExecutionService(activation, mock(PreTradeRiskEngine.class),
                portfolios, quotes, mock(BrokerOrderPort.class),
                mock(LiveOrderSafetyLedger.class), intents, brokerOrders, attempts, jdbc,
                mock(UnknownAttemptReconciler.class), mock(OrderSubmissionService.class), readiness, null);

        var result = service.submit(USER, CONNECTION, "ordp_" + INTENT);

        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.brokerOrderId()).isEqualTo("toss-verified");
        verify(readiness).evaluate(USER, CONNECTION, DECISION, RISK_EVENT, INTENT);
        verify(activation).dispatchFromMcpWithVerifiedReference(eq(USER), eq(INTENT), anyString(),
                eq("MCP:" + USER), eq(new BigDecimal("10.50")));
        verify(activation, never()).dispatchFromMcp(any(), any(), anyString(), anyString(), any());
        verify(quotes, never()).getQuote(any(BrokerConnectionRef.class), eq("LUNR"));
    }

    @Test
    void getOrderReadsConnectedBrokerAccountWithoutLiveAllowlist() {
        var quotes = mock(BrokerAdapter.class);
        var orders = mock(BrokerOrderPort.class);
        var account = new com.jmj.trade.broker.BrokerAccountRef(CONNECTION, "01", "LIVE", "****0001");
        when(quotes.getAccounts(any(BrokerConnectionRef.class))).thenReturn(new BrokerResponse<>(
                List.of(new BrokerAccountView(account, "Toss")), BrokerOrderPort.localMetadata()));
        when(orders.getOrder(eq(account), eq("toss-1"))).thenReturn(new BrokerResponse<>(
                new com.jmj.trade.broker.BrokerOrderView("toss-1", null,
                        com.jmj.trade.broker.BrokerOrderSide.BUY, com.jmj.trade.broker.BrokerOrderType.LIMIT,
                        "AAPL", BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("180"), Currency.USD,
                        com.jmj.trade.broker.BrokerOrderLifecycle.PENDING), BrokerOrderPort.localMetadata()));

        var service = new McpOrderExecutionService(mock(LiveOrderActivationService.class), mock(PreTradeRiskEngine.class),
                mock(FreshPortfolioReadService.class), quotes, orders, mock(LiveOrderSafetyLedger.class),
                mock(OrderIntentRepository.class), mock(BrokerOrderRepository.class), mock(SubmissionAttemptRepository.class),
                mock(JdbcTemplate.class), mock(UnknownAttemptReconciler.class), mock(OrderSubmissionService.class));

        assertThat(service.getOrder(USER, CONNECTION, "toss-1").status()).isEqualTo("SUBMITTED");
        verify(orders).getOrder(account, "toss-1");
    }
}
