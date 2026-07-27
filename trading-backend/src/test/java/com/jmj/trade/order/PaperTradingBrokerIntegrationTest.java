package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TradingBackendApplication.class)
@TestPropertySource(properties = {
        "paper-trading.commission-rate=0.001",
        "paper-trading.tax-rate=0.002",
        "paper-trading.amount-scale=2"
})
class PaperTradingBrokerIntegrationTest extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-07-28T01:00:00Z");

    @Autowired
    private PaperTradingBroker broker;

    @Autowired
    private OrderIntentRepository orderIntentRepository;

    @Autowired
    private SubmissionAttemptRepository attemptRepository;

    @Autowired
    private BrokerOrderRepository brokerOrderRepository;

    @Autowired
    private OrderIntentTransitionService transitionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanLedger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_paper_outbox_insert ON order_submission_outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_paper_outbox_insert()");
        jdbcTemplate.execute("""
                TRUNCATE order_submission_outbox_events,
                         order_submission_audit_logs,
                         reconciliation_checks,
                         submission_attempts,
                         submission_idempotency_keys,
                         order_intent_outbox_events,
                         order_intent_audit_logs,
                         execution_snapshots,
                         broker_orders,
                         order_intents,
                         broker_accounts
                """);
    }

    @Test
    void marketOrderFillsAndStoresConfiguredCommissionAndSellTax() {
        var intentId = submissionPendingIntent(
                OrderSide.SELL, OrderType.MARKET, "AAPL", "10", null, Currency.USD);

        var result = broker.submit(new PaperTradingBroker.SubmitCommand(
                intentId, "paper-market-1", new BigDecimal("50"), new BigDecimal("10"),
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0, "paper-test"));

        assertThat(result.intentStatus()).isEqualTo(OrderIntentStatus.COMPLETED);
        assertThat(result.brokerStatus()).isEqualTo(BrokerOrderStatus.FILLED);
        assertThat(result.filledQuantity()).isEqualByComparingTo("10");
        assertThat(result.filledAmount()).isEqualByComparingTo("500");
        assertThat(result.commission()).isEqualByComparingTo("0.50");
        assertThat(result.tax()).isEqualByComparingTo("1.00");
        assertThat(snapshot(result.brokerOrderId()))
                .containsExactly("10.0000000000", "50.0000000000", "500.0000000000",
                        "0.5000000000", "1.0000000000", "USD");
    }

    @Test
    void limitOrderCanRemainPendingOrPartiallyFillThenCancel() {
        var pendingId = submissionPendingIntent(
                OrderSide.BUY, OrderType.LIMIT, "AAPL", "10", "100", Currency.USD);
        var pending = broker.submit(new PaperTradingBroker.SubmitCommand(
                pendingId, "paper-limit-pending", new BigDecimal("101"), new BigDecimal("10"),
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0, "paper-test"));

        assertThat(pending.intentStatus()).isEqualTo(OrderIntentStatus.ACTIVE);
        assertThat(pending.brokerStatus()).isEqualTo(BrokerOrderStatus.PENDING);
        assertThat(pending.filledQuantity()).isZero();

        var partialId = submissionPendingIntent(
                OrderSide.BUY, OrderType.LIMIT, "MSFT", "10", "100", Currency.USD);
        var partial = broker.submit(new PaperTradingBroker.SubmitCommand(
                partialId, "paper-limit-partial", new BigDecimal("99"), new BigDecimal("4"),
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0, "paper-test"));

        assertThat(partial.intentStatus()).isEqualTo(OrderIntentStatus.ACTIVE);
        assertThat(partial.brokerStatus()).isEqualTo(BrokerOrderStatus.PARTIALLY_FILLED);
        assertThat(partial.commission()).isEqualByComparingTo("0.40");
        assertThat(partial.tax()).isZero();

        var canceled = broker.cancel(partialId, T0.plusSeconds(1), "paper-test");
        assertThat(canceled.intentStatus()).isEqualTo(OrderIntentStatus.PARTIALLY_COMPLETED);
        assertThat(canceled.brokerStatus()).isEqualTo(BrokerOrderStatus.CANCELED);
        assertThat(canceled.filledQuantity()).isEqualByComparingTo("4");
    }

    @Test
    void explicitRejectionAndUnknownRecoveryReuseSubmissionLedger() {
        var rejectedId = submissionPendingIntent(
                OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null, Currency.USD);
        var rejected = broker.submit(new PaperTradingBroker.SubmitCommand(
                rejectedId, "paper-rejected", new BigDecimal("10"), BigDecimal.ONE,
                PaperTradingBroker.DispatchOutcome.REJECTED, T0, "paper-test"));
        assertThat(rejected.attemptStatus()).isEqualTo(SubmissionAttemptStatus.BROKER_REJECTED);
        assertThat(rejected.intentStatus()).isEqualTo(OrderIntentStatus.REJECTED);
        assertThat(rejected.brokerOrderId()).isNull();

        var recoveredId = submissionPendingIntent(
                OrderSide.BUY, OrderType.MARKET, "NVDA", "2", null, Currency.USD);
        var unknown = broker.submit(new PaperTradingBroker.SubmitCommand(
                recoveredId, "paper-unknown", new BigDecimal("20"), new BigDecimal("2"),
                PaperTradingBroker.DispatchOutcome.UNKNOWN, T0, "paper-test"));
        assertThat(unknown.attemptStatus()).isEqualTo(SubmissionAttemptStatus.UNKNOWN);
        assertThat(unknown.intentStatus()).isEqualTo(OrderIntentStatus.RECONCILIATION_REQUIRED);

        var recovered = broker.recover(new PaperTradingBroker.RecoveryCommand(
                unknown.attemptId(), true, new BigDecimal("20"), new BigDecimal("2"),
                T0.plusSeconds(1), "paper-test"));
        assertThat(recovered.attemptStatus()).isEqualTo(SubmissionAttemptStatus.ACKNOWLEDGED);
        assertThat(recovered.intentStatus()).isEqualTo(OrderIntentStatus.COMPLETED);

        var noMatchId = submissionPendingIntent(
                OrderSide.BUY, OrderType.MARKET, "AMD", "1", null, Currency.USD);
        var noMatchUnknown = broker.submit(new PaperTradingBroker.SubmitCommand(
                noMatchId, "paper-no-match", new BigDecimal("10"), BigDecimal.ONE,
                PaperTradingBroker.DispatchOutcome.UNKNOWN, T0, "paper-test"));
        var noMatch = broker.recover(new PaperTradingBroker.RecoveryCommand(
                noMatchUnknown.attemptId(), false, new BigDecimal("10"), BigDecimal.ONE,
                T0.plusSeconds(1), "paper-test"));
        assertThat(noMatch.attemptStatus()).isEqualTo(SubmissionAttemptStatus.RECONCILED_NO_MATCH);
        assertThat(noMatch.intentStatus()).isEqualTo(OrderIntentStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void sameRequestIsIdempotentAndConcurrentSubmissionCreatesOneAttempt() throws Exception {
        var intentId = submissionPendingIntent(
                OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null, Currency.USD);
        var command = new PaperTradingBroker.SubmitCommand(
                intentId, "paper-concurrent", new BigDecimal("10"), BigDecimal.ONE,
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0, "paper-test");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> submitAfterGate(command, ready, start));
            var second = executor.submit(() -> submitAfterGate(command, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS).attemptId())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).attemptId());
        }

        assertThat(attemptRepository.count()).isEqualTo(1);
        assertThat(broker.submit(command).attemptId())
                .isEqualTo(attemptRepository.findAll().getFirst().getId());
    }

    @Test
    void concurrentDifferentOrdersForOneIntentAllowOnlyOneSubmission() throws Exception {
        var intentId = submissionPendingIntent(
                OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null, Currency.USD);
        var first = command(intentId, "paper-race-a");
        var second = command(intentId, "paper-race-b");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> submitOutcome(first, ready, start));
            var secondFuture = executor.submit(() -> submitOutcome(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(new SubmitOutcome[]{
                    firstFuture.get(20, TimeUnit.SECONDS),
                    secondFuture.get(20, TimeUnit.SECONDS)
            }).containsExactlyInAnyOrder(SubmitOutcome.COMMITTED, SubmitOutcome.REJECTED);
        }

        assertThat(attemptRepository.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        var intentId = submissionPendingIntent(
                OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null, Currency.USD);
        broker.submit(new PaperTradingBroker.SubmitCommand(
                intentId, "paper-conflict", new BigDecimal("10"), BigDecimal.ONE,
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0, "paper-test"));

        assertThatThrownBy(() -> broker.submit(new PaperTradingBroker.SubmitCommand(
                intentId, "paper-conflict", new BigDecimal("11"), BigDecimal.ONE,
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0, "paper-test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency");
    }

    @Test
    void ledgerFailureRollsBackWholePaperSubmission() {
        var intentId = submissionPendingIntent(
                OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null, Currency.USD);
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_paper_outbox_insert()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced paper outbox failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_fail_paper_outbox_insert
                BEFORE INSERT ON order_submission_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_paper_outbox_insert()
                """);

        assertThatThrownBy(() -> broker.submit(new PaperTradingBroker.SubmitCommand(
                intentId, "paper-rollback", new BigDecimal("10"), BigDecimal.ONE,
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0, "paper-test")))
                .isInstanceOf(RuntimeException.class);

        assertThat(attemptRepository.count()).isZero();
        assertThat(count("submission_idempotency_keys")).isZero();
        assertThat(count("broker_orders")).isZero();
        assertThat(count("execution_snapshots")).isZero();
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.SUBMISSION_PENDING);
    }

    @Test
    void approvedPaperOrderFieldsAreImmutableAtPostgresBoundary() {
        var intentId = submissionPendingIntent(
                OrderSide.BUY, OrderType.LIMIT, "AAPL", "1", "10", Currency.USD);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE order_intents SET limit_price = 11 WHERE id = ?", intentId))
                .isInstanceOf(RuntimeException.class);

        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getLimitPrice())
                .isEqualByComparingTo("10");
    }

    @Test
    void paperCancelRejectsLiveBrokerOrder() {
        var intentId = submissionPendingIntent(
                OrderSide.BUY, OrderType.LIMIT, "AAPL", "1", "10", Currency.USD);
        var intent = orderIntentRepository.findById(intentId).orElseThrow();
        brokerOrderRepository.saveAndFlush(BrokerOrder.confirmed(
                UUID.randomUUID(),
                intentId,
                intent.getBrokerAccountId(),
                "live-order-1",
                "live-client-1",
                BrokerOrderStatus.PENDING));
        transitionService.activate(intentId, "paper-test");

        assertThatThrownBy(() -> broker.cancel(intentId, T0.plusSeconds(1), "paper-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("live broker order");

        assertThat(brokerOrderRepository.findFirstByOrderIntentIdOrderByIdAsc(intentId)
                .orElseThrow().getStatus()).isEqualTo(BrokerOrderStatus.PENDING);
    }

    private PaperTradingBroker.Result submitAfterGate(
            PaperTradingBroker.SubmitCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return broker.submit(command);
    }

    private SubmitOutcome submitOutcome(
            PaperTradingBroker.SubmitCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            broker.submit(command);
            return SubmitOutcome.COMMITTED;
        } catch (IllegalStateException exception) {
            return SubmitOutcome.REJECTED;
        }
    }

    private PaperTradingBroker.SubmitCommand command(UUID intentId, String clientOrderId) {
        return new PaperTradingBroker.SubmitCommand(
                intentId, clientOrderId, new BigDecimal("10"), BigDecimal.ONE,
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0, "paper-test");
    }

    private UUID submissionPendingIntent(
            OrderSide side,
            OrderType type,
            String symbol,
            String quantity,
            String limitPrice,
            Currency currency
    ) {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(
                intentId,
                accountId,
                side,
                type,
                symbol,
                new BigDecimal(quantity),
                limitPrice == null ? null : new BigDecimal(limitPrice),
                currency));
        transitionService.approve(intentId, "paper-test");
        transitionService.startRevalidation(intentId, "paper-test");
        transitionService.markSubmissionPending(intentId, "paper-test");
        return intentId;
    }

    private String[] snapshot(UUID brokerOrderId) {
        return jdbcTemplate.queryForObject("""
                SELECT filled_quantity::text, average_filled_price::text, filled_amount::text,
                       commission::text, tax::text, currency
                  FROM execution_snapshots
                 WHERE broker_order_id = ?
                 ORDER BY captured_at DESC, id DESC
                 LIMIT 1
                """, (resultSet, rowNumber) -> new String[]{
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getString(5),
                resultSet.getString(6)
        }, brokerOrderId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private enum SubmitOutcome {
        COMMITTED,
        REJECTED
    }
}
