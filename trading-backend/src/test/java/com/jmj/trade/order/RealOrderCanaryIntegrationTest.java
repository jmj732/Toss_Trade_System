package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderAck;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderSide;
import com.jmj.trade.broker.BrokerOrderType;
import com.jmj.trade.broker.BrokerOrderView;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.toss.TossCredentialMetadata;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
                "real-order.enabled=false",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
class RealOrderCanaryIntegrationTest extends PostgresIntegrationTest {

    private static final String BROKER_ORDER_ID = "broker-order-canary";
    private static final Instant NOW = Instant.now();

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
                TRUNCATE real_order_canary_runs, real_order_canary_audit_events, order_reconciliation_actions,
                         order_approval_step_up_tokens, notification_outbox_events,
                         order_submission_outbox_events, order_submission_audit_logs,
                         reconciliation_checks, submission_attempts, submission_idempotency_keys,
                         order_intent_outbox_events, order_intent_audit_logs, execution_snapshots,
                         broker_orders, real_order_daily_reservations, real_order_account_allowlist,
                         order_intents, broker_accounts, broker_connections, users CASCADE
                """);
    }

    @Test
    void canaryRunsSubmitObserveCancelAndReconcileExactlyOnceWithRedactedAudit() {
        var owner = owner();
        var f = fixture(owner, Fault.NONE);

        var result = f.service().run(owner.userId(), order(), NOW.minusSeconds(1), "canary-test", "happy");

        assertThat(result.outcome()).withFailMessage("outcome=%s blockers=%s unknown=%s",
                result.outcome(), result.blockers(), result.unknown()).isEqualTo("FINAL_RECONCILED");
        assertThat(result.orderSubmitted()).isTrue();
        assertThat(result.unknown()).withFailMessage("outcome=%s blockers=%s",
                result.outcome(), result.blockers()).isFalse();
        verify(f.orders()).placeOrder(any(), any(), any());
        verify(f.orders()).cancelOrder(any(), eq(BROKER_ORDER_ID));
        verify(f.orders(), times(2)).getOrders(any(), eq(BrokerOrderGroup.OPEN));
        verify(f.orders(), times(2)).getOrders(any(), eq(BrokerOrderGroup.CLOSED));

        var rows = jdbc.queryForList("""
                SELECT client_order_id_hash, broker_order_id_hash, evidence::text
                  FROM real_order_canary_audit_events
                 WHERE run_id = ? ORDER BY event_number
                """, result.runId());
        assertThat(rows).hasSize(7);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("evidence").toString()).doesNotContain("secret", BROKER_ORDER_ID);
            if (row.get("client_order_id_hash") != null) {
                assertThat(row.get("client_order_id_hash").toString()).hasSize(64);
            }
            if (row.get("broker_order_id_hash") != null) {
                assertThat(row.get("broker_order_id_hash").toString()).hasSize(64);
            }
        });
    }

    @Test
    void sameRunKeyReplaysCompletedResultWithoutSecondBrokerSubmission() {
        var owner = owner();
        var f = fixture(owner, Fault.NONE);

        var first = f.service().run(owner.userId(), order(), NOW.minusSeconds(1), "canary-test", "same-run");
        var second = f.service().run(owner.userId(), order(), NOW.minusSeconds(1), "canary-test", "same-run");

        assertThat(second).isEqualTo(first);
        verify(f.orders(), times(1)).placeOrder(any(), any(), any());
        verify(f.orders(), times(1)).cancelOrder(any(), eq(BROKER_ORDER_ID));
    }

    @Test
    void lookupFaultStopsAtManualReviewWithoutResubmission() {
        var owner = owner();
        var f = fixture(owner, Fault.LOOKUP_UNKNOWN);

        var result = f.service().run(owner.userId(), order(), NOW.minusSeconds(1), "canary-test", "lookup-unknown");

        assertThat(result.outcome()).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(result.unknown()).isTrue();
        verify(f.orders(), times(1)).placeOrder(any(), any(), any());
        verify(f.orders(), never()).cancelOrder(any(), any());
    }

    @Test
    void cancelUnknownIsRecordedAndFinalOpenStateEndsInManualReviewWithoutRetry() {
        var owner = owner();
        var f = fixture(owner, Fault.CANCEL_UNKNOWN);

        var result = f.service().run(owner.userId(), order(), NOW.minusSeconds(1), "canary-test", "cancel-unknown");

        assertThat(result.outcome()).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(result.unknown()).isTrue();
        verify(f.orders(), times(1)).placeOrder(any(), any(), any());
        verify(f.orders(), times(1)).cancelOrder(any(), eq(BROKER_ORDER_ID));
        verify(f.orders(), times(2)).getOrders(any(), eq(BrokerOrderGroup.OPEN));
        verify(f.orders(), times(2)).getOrders(any(), eq(BrokerOrderGroup.CLOSED));
    }

    @Test
    void staleQuoteReturnsPreflightOnlyWithoutOrderSubmission() {
        var owner = owner();
        var f = fixture(owner, Fault.STALE_QUOTE);

        var result = f.service().run(owner.userId(), order(), NOW.minusSeconds(1), "canary-test", "stale-quote");

        assertThat(result.outcome()).isEqualTo("PREFLIGHT_ONLY");
        assertThat(result.orderSubmitted()).isFalse();
        assertThat(result.blockers()).contains("CANARY_QUOTE_STALE");
        verify(f.orders(), never()).placeOrder(any(), any(), any());
        verify(f.orders(), never()).getOrders(any(), any());
    }

    @Test
    void quoteIdentityMismatchReturnsPreflightOnlyWithoutOrderSubmission() {
        var owner = owner();
        var f = fixture(owner, Fault.QUOTE_MISMATCH);

        var result = f.service().run(owner.userId(), order(), NOW.minusSeconds(1), "canary-test", "quote-mismatch");

        assertThat(result.outcome()).isEqualTo("PREFLIGHT_ONLY");
        assertThat(result.orderSubmitted()).isFalse();
        assertThat(result.blockers()).contains("CANARY_QUOTE_MISMATCH");
        verify(f.orders(), never()).placeOrder(any(), any(), any());
    }

    @Test
    void clientOrderIdMismatchStopsAtManualReviewWithoutLookupOrResubmission() {
        var owner = owner();
        var f = fixture(owner, Fault.CLIENT_ID_MISMATCH);

        var result = f.service().run(owner.userId(), order(), NOW.minusSeconds(1), "canary-test", "client-id-mismatch");

        assertThat(result.outcome()).isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(result.orderSubmitted()).isTrue();
        assertThat(result.unknown()).isTrue();
        assertThat(result.blockers()).contains("CANARY_CLIENT_ORDER_ID_MAPPING_MISMATCH");
        verify(f.orders(), times(1)).placeOrder(any(), any(), any());
        verify(f.orders(), never()).getOrders(any(), any());
        verify(f.orders(), never()).cancelOrder(any(), any());
    }

    private Fixture fixture(Owner owner, Fault fault) {
        var quotes = mock(BrokerAdapter.class);
        var orders = mock(BrokerOrderPort.class);
        var risk = mock(PreTradeRiskEngine.class);
        var stepUp = mock(OrderApprovalStepUpService.class);
        var killSwitches = mock(KillSwitchStateReader.class);
        var credentials = mock(TossCredentialProvider.class);
        var reconciler = mock(UnknownAttemptReconciler.class);
        var safety = new LiveOrderSafetyLedger(jdbc, ZoneId.of("Asia/Seoul"));
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);

        when(credentials.current(owner.connectionId())).thenReturn(new TossCredentialMetadata(1));
        when(killSwitches.anyEngaged(any(), any())).thenReturn(false);
        when(stepUp.issue(any(), any(), any())).thenReturn(
                new OrderApprovalStepUpService.IssuedStepUp("step-up", NOW.plusSeconds(300)));
        when(stepUp.accepts(any())).thenReturn(true);
        doAnswer(invocation -> null).when(stepUp).consume(any(), any(), any());
        var observedAt = fault == Fault.STALE_QUOTE ? NOW.minus(Duration.ofMinutes(2)) : NOW;
        var quoteConnection = fault == Fault.QUOTE_MISMATCH ? UUID.randomUUID() : owner.connectionId();
        when(quotes.getQuote(any(), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(new BrokerConnectionRef(quoteConnection), "AAPL", Currency.USD,
                        new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"), null, observedAt),
                BrokerOrderPort.localMetadata()));
        doAnswer(invocation -> {
            var command = invocation.getArgument(0, PreTradeRiskEngine.ApprovalCommand.class);
            transitions.approve(command.orderIntentId(), command.actor());
            return decision(PreTradeRiskEngine.Phase.APPROVAL, new BigDecimal("10"));
        }).when(risk).approveLive(any());
        doAnswer(invocation -> {
            var orderIntentId = invocation.getArgument(2, UUID.class);
            var actor = invocation.getArgument(5, String.class);
            transitions.startRevalidation(orderIntentId, actor);
            transitions.markSubmissionPending(orderIntentId, actor);
            return new PreTradeRiskEngine.LiveSubmission(
                    decision(PreTradeRiskEngine.Phase.FINAL, new BigDecimal("10")),
                    orderIntentId, owner.accountId(), OrderSide.BUY, OrderType.LIMIT,
                    "AAPL", BigDecimal.ONE, new BigDecimal("10"), Currency.USD);
        }).when(risk).submitLive(any(), any(), any(), any(), any(), any());
        when(orders.placeOrder(any(), any(), any())).thenAnswer(invocation ->
                BrokerOrderPort.ack(BrokerOrderAck.accepted(BROKER_ORDER_ID,
                        fault == Fault.CLIENT_ID_MISMATCH ? "different-client" : invocation.getArgument(2))));
        when(orders.cancelOrder(any(), eq(BROKER_ORDER_ID))).thenReturn(
                BrokerOrderPort.ack(fault == Fault.CANCEL_UNKNOWN
                        ? BrokerOrderAck.unknown(BROKER_ORDER_ID, null)
                        : BrokerOrderAck.accepted(BROKER_ORDER_ID, null)));
        if (fault == Fault.LOOKUP_UNKNOWN) {
            when(orders.getOrders(any(), eq(BrokerOrderGroup.OPEN)))
                    .thenThrow(new IllegalStateException("injected lookup timeout"));
            when(orders.getOrders(any(), eq(BrokerOrderGroup.CLOSED)))
                    .thenReturn(response(List.of()));
        } else if (fault == Fault.CANCEL_UNKNOWN) {
            when(orders.getOrders(any(), eq(BrokerOrderGroup.OPEN)))
                    .thenReturn(response(List.of(view(BrokerOrderLifecycle.PENDING))), response(List.of(view(BrokerOrderLifecycle.PENDING))));
            when(orders.getOrders(any(), eq(BrokerOrderGroup.CLOSED)))
                    .thenReturn(response(List.of()), response(List.of()));
        } else {
            when(orders.getOrders(any(), eq(BrokerOrderGroup.OPEN)))
                    .thenReturn(response(List.of(view(BrokerOrderLifecycle.PENDING))), response(List.of()));
            when(orders.getOrders(any(), eq(BrokerOrderGroup.CLOSED)))
                    .thenReturn(response(List.of()), response(List.of(view(BrokerOrderLifecycle.CANCELED))));
        }

        insertAllowlist(owner);
        var live = new LiveOrderActivationService(
                quotes, orders, intents, brokerOrders, attempts, submissions, transitions, risk, stepUp,
                safety, killSwitches, new TransactionTemplate(transactionManager));
        var properties = new RealOrderCanaryProperties(true, owner.connectionId(), owner.accountId(),
                BigDecimal.ONE, new BigDecimal("100000"), new BigDecimal("100"),
                Duration.ofMinutes(1), "canary");
        var service = new RealOrderCanaryService(
                provider(quotes), provider(credentials), provider(live), provider(safety), provider(killSwitches),
                provider(orders), provider(stepUp), provider(submissions), provider(brokerOrders), provider(reconciler),
                jdbc, new RealOrderCanaryAuditLedger(jdbc), properties, clock);
        return new Fixture(service, orders);
    }

    private BrokerOrderView view(BrokerOrderLifecycle status) {
        return new BrokerOrderView(BROKER_ORDER_ID, null, BrokerOrderSide.BUY, BrokerOrderType.LIMIT,
                "AAPL", BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("10"), Currency.USD, status);
    }

    private static <T> BrokerResponse<List<T>> response(List<T> value) {
        return new BrokerResponse<>(value, BrokerOrderPort.localMetadata());
    }

    private PreTradeRiskEngine.Decision decision(PreTradeRiskEngine.Phase phase, BigDecimal amount) {
        return new PreTradeRiskEngine.Decision(UUID.randomUUID(), phase, true, List.of(),
                UUID.randomUUID(), amount, Currency.USD, 1, NOW);
    }

    private RealOrderCanaryService.CanaryOrder order() {
        return new RealOrderCanaryService.CanaryOrder(OrderSide.BUY, OrderType.LIMIT,
                "AAPL", BigDecimal.ONE, new BigDecimal("10"), Currency.USD);
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
                """, connectionId, userId, new byte[17], new byte[12], at(NOW), at(NOW));
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        return new Owner(userId, connectionId, accountId);
    }

    private void insertAllowlist(Owner owner) {
        jdbc.update("""
                INSERT INTO real_order_account_allowlist (
                    id, user_id, broker_connection_id, broker_account_id, toss_account_seq,
                    display_account_number, enabled, daily_limit_krw, daily_limit_usd, created_at
                ) VALUES (?, ?, ?, ?, '01', '******0001', TRUE, 1000, 400, ?)
                """, UUID.randomUUID(), owner.userId(), owner.connectionId(), owner.accountId(), at(NOW));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private enum Fault {
        NONE, LOOKUP_UNKNOWN, CANCEL_UNKNOWN, STALE_QUOTE, CLIENT_ID_MISMATCH, QUOTE_MISMATCH
    }

    private record Owner(UUID userId, UUID connectionId, UUID accountId) {
    }

    private record Fixture(RealOrderCanaryService service, BrokerOrderPort orders) {
    }
}
