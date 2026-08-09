package com.jmj.trade.order;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderAck;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderSide;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveOrderActivationServiceTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    private static final UUID INTENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000004");
    private static final UUID ATTEMPT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000005");
    private static final String CLIENT = "live-order-001";

    @Test
    void clientOrderIdEchoMismatchBecomesManualReviewWithoutResend() {
        var f = fixture(BrokerOrderAck.accepted("toss-order-1", "different-client"));

        var result = f.service().dispatch(USER, INTENT_ID, CLIENT, "step-up", "test");

        assertThat(result.attemptId()).isEqualTo(ATTEMPT_ID);
        verify(f.submissions()).markManualReview(eq(ATTEMPT_ID), any(), any(), eq("test"));
        verify(f.submissions(), org.mockito.Mockito.never())
                .confirmBrokerOrder(any(), any(), any());
        verify(f.orders()).placeOrder(any(), any(), eq(CLIENT));
        var order = inOrder(f.portfolios(), f.intents());
        order.verify(f.portfolios()).read(USER, CONNECTION);
        order.verify(f.intents()).findOwnedByIdForUpdate(INTENT_ID, USER, CONNECTION);
    }

    @Test
    void transportUnknownIsRecordedOnceAndNeverReplayed() {
        var f = fixture(null);
        when(f.orders().placeOrder(any(), any(), eq(CLIENT))).thenThrow(new RuntimeException("timeout"));

        var result = f.service().dispatch(USER, INTENT_ID, CLIENT, "step-up", "test");

        assertThat(result.attemptId()).isEqualTo(ATTEMPT_ID);
        verify(f.submissions()).markUnknown(eq(ATTEMPT_ID), any(), any(), eq("test"));
        verify(f.orders()).placeOrder(any(), any(), eq(CLIENT));
    }

    @Test
    void liveCancelAndModifyUseTheOrderPortWithoutAutomaticRetry() {
        var f = fixture(BrokerOrderAck.accepted("operation-1", null), true);
        when(f.orders().cancelOrder(any(), eq("broker-order-1")))
                .thenReturn(new BrokerResponse<>(BrokerOrderAck.accepted("cancel-2", null),
                        BrokerOrderPort.localMetadata()));
        when(f.orders().modifyOrder(any(), any()))
                .thenReturn(new BrokerResponse<>(BrokerOrderAck.accepted("replace-2", null),
                        BrokerOrderPort.localMetadata()));
        when(f.safety().resolve(USER, CONNECTION, ACCOUNT))
                .thenReturn(new BrokerAccountRef(CONNECTION, "01", "LIVE", "****0001"));

        assertThat(f.service().cancel(USER, INTENT_ID, "step-up", "test").status())
                .isEqualTo(com.jmj.trade.broker.BrokerOrderDispatchStatus.ACCEPTED);
        assertThat(f.service().modify(USER, INTENT_ID, new BigDecimal("179"), "step-up", "test").status())
                .isEqualTo(com.jmj.trade.broker.BrokerOrderDispatchStatus.ACCEPTED);
        verify(f.orders()).cancelOrder(any(), eq("broker-order-1"));
        verify(f.orders()).modifyOrder(any(), any());
        verify(f.orders(), never()).placeOrder(any(), any(), any());
    }

    private Fixture fixture(BrokerOrderAck ack) {
        return fixture(ack, false);
    }

    private Fixture fixture(BrokerOrderAck ack, boolean active) {
        var quotes = mock(BrokerAdapter.class);
        var orders = mock(BrokerOrderPort.class);
        var intents = mock(OrderIntentRepository.class);
        var brokerOrders = mock(BrokerOrderRepository.class);
        var attempts = mock(SubmissionAttemptRepository.class);
        var submissions = mock(OrderSubmissionService.class);
        var transitions = mock(OrderIntentTransitionService.class);
        var risk = mock(PreTradeRiskEngine.class);
        var portfolios = mock(FreshPortfolioReadService.class);
        var stepUp = mock(OrderApprovalStepUpService.class);
        var safety = mock(LiveOrderSafetyLedger.class);
        var transaction = transactionTemplate();
        var intent = OrderIntent.proposedLive(INTENT_ID, ACCOUNT, USER, CONNECTION,
                OrderSide.BUY, OrderType.LIMIT, "AAPL", new BigDecimal("1"), new BigDecimal("180"), Currency.USD);
        intent.approve();
        if (active) {
            intent.startRevalidation();
            intent.markSubmissionPending();
            intent.activate();
        }
        var brokerOrder = BrokerOrder.confirmed(UUID.randomUUID(), INTENT_ID, ACCOUNT,
                "broker-order-1", CLIENT, BrokerOrderStatus.PENDING);
        when(brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(INTENT_ID)).thenReturn(Optional.of(brokerOrder));
        var attempt = mock(SubmissionAttempt.class);
        when(attempt.getId()).thenReturn(ATTEMPT_ID);
        when(attempt.getOrderIntentId()).thenReturn(INTENT_ID);
        when(attempt.getStatus()).thenReturn(SubmissionAttemptStatus.UNKNOWN);
        when(attempt.getConfirmedBrokerOrderId()).thenReturn(null);
        when(intents.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        when(intents.findOwnedByIdForUpdate(INTENT_ID, USER, CONNECTION)).thenReturn(Optional.of(intent));
        when(attempts.findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(INTENT_ID, CLIENT))
                .thenReturn(Optional.empty());
        when(attempts.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        when(quotes.getQuote(any(), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new com.jmj.trade.broker.Quote(new BrokerConnectionRef(CONNECTION), "AAPL", Currency.USD,
                        new BigDecimal("180"), new BigDecimal("179.9"), new BigDecimal("180.1"), null, Instant.now()),
                BrokerOrderPort.localMetadata()));
        when(portfolios.read(USER, CONNECTION)).thenReturn(mock(PortfolioReadService.PortfolioView.class));
        when(risk.submitLive(eq(USER), eq(CONNECTION), eq(INTENT_ID), any(), any(), eq("test"), any()))
                .thenReturn(new PreTradeRiskEngine.LiveSubmission(
                        new PreTradeRiskEngine.Decision(UUID.randomUUID(), PreTradeRiskEngine.Phase.FINAL,
                                true, List.of(), UUID.randomUUID(), new BigDecimal("180"), Currency.USD, 1, Instant.now()),
                        INTENT_ID, ACCOUNT, OrderSide.BUY, OrderType.LIMIT, "AAPL", new BigDecimal("1"),
                        new BigDecimal("180"), Currency.USD));
        when(safety.reserve(eq(USER), eq(CONNECTION), eq(ACCOUNT), eq(INTENT_ID), eq(Currency.USD), any(), any()))
                .thenReturn(new LiveOrderSafetyLedger.Reservation(
                        new BrokerAccountRef(CONNECTION, "01", "LIVE", "****0001"), new BigDecimal("180"),
                        java.time.LocalDate.now()));
        when(submissions.createInitialAttempt(any(), eq(CLIENT), any(), eq(CLIENT), any(), eq("test")))
                .thenReturn(ATTEMPT_ID);
        if (ack != null) {
            when(orders.placeOrder(any(), any(), eq(CLIENT))).thenReturn(
                    new BrokerResponse<>(ack, BrokerOrderPort.localMetadata()));
        }
        doNothing().when(submissions).startDispatch(any(), any(), any(), any());
        var killSwitches = mock(KillSwitchStateReader.class);
        when(killSwitches.anyEngaged(any(), any())).thenReturn(false);
        return new Fixture(new LiveOrderActivationService(
                quotes, orders, intents, brokerOrders, attempts, submissions, transitions, risk, portfolios, stepUp, safety,
                killSwitches, transaction, java.time.Duration.ofMinutes(15)), orders, submissions, safety, portfolios, intents);
    }

    @SuppressWarnings("unchecked")
    private TransactionTemplate transactionTemplate() {
        var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        return transaction;
    }

    private record Fixture(LiveOrderActivationService service, BrokerOrderPort orders,
                           OrderSubmissionService submissions, LiveOrderSafetyLedger safety,
                           FreshPortfolioReadService portfolios, OrderIntentRepository intents) {
    }
}
