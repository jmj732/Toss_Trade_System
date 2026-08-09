package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderAck;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
class LiveOrderActivationFlowIntegrationTest extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-02T03:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderIntentRepository intents;

    @Autowired
    private SubmissionAttemptRepository attempts;

    @Autowired
    private BrokerOrderRepository brokerOrders;

    @Autowired
    private OrderSubmissionService submissions;

    @Autowired
    private OrderIntentTransitionService transitions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.execute("""
                TRUNCATE order_reconciliation_actions, order_approval_step_up_tokens,
                         notification_outbox_events, order_submission_outbox_events,
                         order_submission_audit_logs, reconciliation_checks, submission_attempts,
                         submission_idempotency_keys, order_intent_outbox_events, order_intent_audit_logs,
                         execution_snapshots, broker_orders, real_order_daily_reservations,
                         real_order_account_allowlist, order_intents, broker_accounts,
                         broker_connections, users CASCADE
                """);
    }

    @Test
    void smallAllowlistApprovalRevalidationAndDispatchPersistsExactlyOneBrokerOrder() {
        var owner = owner();
        var f = fixture(owner, BrokerOrderAck.accepted("toss-order-e2e", "client-e2e"));
        var intentId = f.service().propose(owner.userId(), proposal(owner));

        f.service().issueStepUp(owner.userId(), intentId, T0);
        f.service().approve(owner.userId(), intentId, "step-up", "e2e", BigDecimal.ONE,
                new BigDecimal("180"), Currency.USD);
        f.service().issueStepUp(owner.userId(), intentId, T0);
        var result = f.service().dispatch(owner.userId(), intentId, "client-e2e", "step-up", "e2e");

        assertThat(result.attemptStatus()).isEqualTo(SubmissionAttemptStatus.ACKNOWLEDGED);
        assertThat(result.intentStatus()).isEqualTo(OrderIntentStatus.ACTIVE);
        assertThat(brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(intentId)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM real_order_daily_reservations WHERE order_intent_id = ?",
                Long.class, intentId)).isEqualTo(1);
    }

    @Test
    void injectedTransportFaultLeavesUnknownWithoutResend() {
        var owner = owner();
        var f = fixture(owner, null);
        var intentId = f.service().propose(owner.userId(), proposal(owner));

        f.service().issueStepUp(owner.userId(), intentId, T0);
        f.service().approve(owner.userId(), intentId, "step-up", "e2e", BigDecimal.ONE,
                new BigDecimal("180"), Currency.USD);
        f.service().issueStepUp(owner.userId(), intentId, T0);
        var result = f.service().dispatch(owner.userId(), intentId, "client-timeout", "step-up", "e2e");

        assertThat(result.attemptStatus()).isEqualTo(SubmissionAttemptStatus.UNKNOWN);
        assertThat(result.intentStatus()).isEqualTo(OrderIntentStatus.RECONCILIATION_REQUIRED);
        verify(f.orders(), times(1)).placeOrder(any(), any(), eq("client-timeout"));
        assertThat(attempts.findById(result.attemptId()).orElseThrow().getConfirmedBrokerOrderId()).isNull();
    }

    @Test
    void approvingAnExpiredProposalIsRejectedAndLeavesItProposedWithoutApproval() {
        var owner = owner();
        var f = fixture(owner, BrokerOrderAck.accepted("toss-order-expired", "client-expired"));
        var intentId = f.service().propose(owner.userId(), proposal(owner));
        jdbc.update("UPDATE order_intents SET expires_at = ? WHERE id = ?",
                at(Instant.now().minusSeconds(5)), intentId);
        f.service().issueStepUp(owner.userId(), intentId, T0);

        assertThatThrownBy(() -> f.service().approve(owner.userId(), intentId, "step-up", "e2e",
                BigDecimal.ONE, new BigDecimal("180"), Currency.USD))
                .isInstanceOfSatisfying(LiveOrderActivationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(LiveOrderActivationException.Code.PROPOSAL_EXPIRED));

        assertThat(intents.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.PROPOSED);
        verify(f.risk(), never()).approveLive(any());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM real_order_daily_reservations WHERE order_intent_id = ?",
                Long.class, intentId)).isEqualTo(0);
    }

    @Test
    void approvingBeforeExpiryStillSucceeds() {
        var owner = owner();
        var f = fixture(owner, BrokerOrderAck.accepted("toss-order-fresh", "client-fresh"));
        var intentId = f.service().propose(owner.userId(), proposal(owner));
        f.service().issueStepUp(owner.userId(), intentId, T0);

        f.service().approve(owner.userId(), intentId, "step-up", "e2e", BigDecimal.ONE,
                new BigDecimal("180"), Currency.USD);

        assertThat(intents.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.APPROVED);
        verify(f.risk(), times(1)).approveLive(any(), any());
    }

    @Test
    void legacyProposalWithNullExpiryRemainsApprovable() {
        var owner = owner();
        var f = fixture(owner, BrokerOrderAck.accepted("toss-order-null", "client-null"));
        var intentId = f.service().propose(owner.userId(), proposal(owner));
        jdbc.update("UPDATE order_intents SET expires_at = NULL, created_at = NULL WHERE id = ?", intentId);
        f.service().issueStepUp(owner.userId(), intentId, T0);

        f.service().approve(owner.userId(), intentId, "step-up", "e2e", BigDecimal.ONE,
                new BigDecimal("180"), Currency.USD);

        assertThat(intents.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.APPROVED);
    }

    private Fixture fixture(Owner owner, BrokerOrderAck ack) {
        var quotes = mock(BrokerAdapter.class);
        var orders = mock(BrokerOrderPort.class);
        var risk = mock(PreTradeRiskEngine.class);
        var portfolios = mock(FreshPortfolioReadService.class);
        var stepUp = mock(OrderApprovalStepUpService.class);
        var killSwitches = mock(KillSwitchStateReader.class);
        var safety = new LiveOrderSafetyLedger(jdbc, ZoneId.of("Asia/Seoul"));
        when(killSwitches.anyEngaged(any(), any())).thenReturn(false);
        when(quotes.getQuote(any(), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(new BrokerConnectionRef(owner.connectionId()), "AAPL", Currency.USD,
                        new BigDecimal("180"), new BigDecimal("180"), new BigDecimal("180"), null, T0),
                BrokerOrderPort.localMetadata()));
        when(stepUp.issue(any(), any(), any())).thenReturn(
                new OrderApprovalStepUpService.IssuedStepUp("step-up", T0.plusSeconds(300)));
        doAnswer(invocation -> null).when(stepUp).consume(any(), any(), any());
        doAnswer(invocation -> {
            var command = invocation.getArgument(0, PreTradeRiskEngine.ApprovalCommand.class);
            transitions.approve(command.orderIntentId(), command.actor());
            return decision(PreTradeRiskEngine.Phase.APPROVAL, new BigDecimal("180"));
        }).when(risk).approveLive(any(), any());
        doAnswer(invocation -> {
            var orderIntentId = invocation.getArgument(2, UUID.class);
            var actor = invocation.getArgument(5, String.class);
            transitions.startRevalidation(orderIntentId, actor);
            transitions.markSubmissionPending(orderIntentId, actor);
            return new PreTradeRiskEngine.LiveSubmission(
                    decision(PreTradeRiskEngine.Phase.FINAL, new BigDecimal("180")),
                    orderIntentId, owner.accountId(), OrderSide.BUY, OrderType.LIMIT,
                    "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD);
        }).when(risk).submitLive(any(), any(), any(), any(), any(), any(), any());
        when(portfolios.read(any(), any())).thenReturn(mock(PortfolioReadService.PortfolioView.class));
        if (ack == null) {
            when(orders.placeOrder(any(), any(), eq("client-timeout")))
                    .thenThrow(new IllegalStateException("injected timeout"));
        } else {
            when(orders.placeOrder(any(), any(), eq("client-e2e")))
                    .thenReturn(new BrokerResponse<>(ack, BrokerOrderPort.localMetadata()));
        }
        insertAllowlist(owner);
        return new Fixture(new LiveOrderActivationService(
                quotes, orders, intents, brokerOrders, attempts, submissions, transitions, risk, portfolios, stepUp,
                safety, killSwitches, new TransactionTemplate(transactionManager),
                java.time.Duration.ofMinutes(15)), orders, risk);
    }

    private PreTradeRiskEngine.Decision decision(PreTradeRiskEngine.Phase phase, BigDecimal amount) {
        return new PreTradeRiskEngine.Decision(UUID.randomUUID(), phase, true, List.of(),
                UUID.randomUUID(), amount, Currency.USD, 1, T0);
    }

    private LiveOrderActivationService.Proposal proposal(Owner owner) {
        return new LiveOrderActivationService.Proposal(owner.connectionId(), owner.accountId(),
                OrderSide.BUY, OrderType.LIMIT, "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD);
    }

    private Owner owner() {
        var userId = UUID.randomUUID();
        var connectionId = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], at(T0), at(T0));
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        return new Owner(userId, connectionId, accountId);
    }

    private void insertAllowlist(Owner owner) {
        jdbc.update("""
                INSERT INTO real_order_account_allowlist (
                    id, user_id, broker_connection_id, broker_account_id, toss_account_seq,
                    display_account_number, enabled, daily_limit_krw, daily_limit_usd, created_at
                ) VALUES (?, ?, ?, ?, '01', '******0001', TRUE, 1000, 400, ?)
                """, UUID.randomUUID(), owner.userId(), owner.connectionId(), owner.accountId(), at(T0));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Owner(UUID userId, UUID connectionId, UUID accountId) {
    }

    private record Fixture(LiveOrderActivationService service, BrokerOrderPort orders, PreTradeRiskEngine risk) {
    }
}
