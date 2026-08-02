package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {
        TradingBackendApplication.class,
        OrderSubmissionServiceIntegrationTest.AttemptRepositoryFindGateConfiguration.class
})
class OrderSubmissionServiceIntegrationTest extends PostgresIntegrationTest {

    private static final String ACTOR = "submission-test";
    private static final Instant T0 = Instant.parse("2026-07-27T01:00:00Z");
    private static final AttemptRepositoryFindGate ATTEMPT_REPOSITORY_FIND_GATE = new AttemptRepositoryFindGate();

    @Autowired
    private OrderSubmissionService service;

    @Autowired
    private OrderIntentTransitionService transitionService;

    @Autowired
    private OrderIntentRepository orderIntentRepository;

    @Autowired
    private SubmissionAttemptRepository attemptRepository;

    @Autowired
    private BrokerOrderRepository brokerOrderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanLedger() {
        ATTEMPT_REPOSITORY_FIND_GATE.disable();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_order_submission_outbox_insert ON order_submission_outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_order_submission_outbox_insert()");
        jdbcTemplate.execute("""
                TRUNCATE order_approval_step_up_tokens, paper_order_workflow_commands,
                         pre_trade_risk_decisions,
                         order_submission_outbox_events,
                         order_submission_audit_logs,
                         reconciliation_checks,
                         submission_attempts,
                         submission_idempotency_keys,
                         order_intent_outbox_events,
                         order_intent_audit_logs,
                         execution_snapshots,
                         broker_orders,
                         real_order_daily_reservations, real_order_account_allowlist, order_intents,
                         broker_accounts
                """);
    }

    @Test
    void initialAttemptOwnsAccountScopedClientOrderId() {
        var intentId = submissionPendingIntent("10");
        var attemptId = service.createInitialAttempt(
                intentId, "client-1", "hash-1", "internal-1", T0, ACTOR);

        var attempt = attemptRepository.findById(attemptId).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(SubmissionAttemptStatus.CREATED);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getIdempotencyExpiresAt()).isEqualTo(T0.plusSeconds(600));
        assertSubmissionLedger(intentId, "InitialAttemptCreated", 1);

        var otherIntent = submissionPendingIntent("5", attempt.getBrokerAccountId());
        assertThatThrownBy(() -> service.createInitialAttempt(
                otherIntent, "client-1", "hash-2", "internal-2", T0.plusSeconds(1), ACTOR))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void unknownSubmissionMovesIntentToReconciliationRequiredAtomically() {
        var intentId = submissionPendingIntent("10");
        var attemptId = service.createInitialAttempt(intentId, "client-2", "hash-2", "internal-2", T0, ACTOR);
        service.startDispatch(attemptId, T0.plusSeconds(1), new DispatchEvidence("req-2", "sent"), ACTOR);

        service.markUnknown(attemptId, T0.plusSeconds(2), new DispatchEvidence("req-2", "timeout"), ACTOR);

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.UNKNOWN);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.RECONCILIATION_REQUIRED);
        assertIntentLedger(intentId, OrderIntentStatus.SUBMISSION_PENDING, OrderIntentStatus.RECONCILIATION_REQUIRED);
        assertSubmissionLedger(intentId, "SubmissionUnknown", 3);
    }

    @Test
    void completeNoMatchBeforeExpiryAllowsExactlyOneSameKeyRetry() {
        var intentId = submissionPendingIntent("10");
        var parentId = unknownAttempt(intentId, "client-3", "hash-3", "internal-3");

        service.recordReconciliation(parentId, noMatch(T0.plusSeconds(30)), ACTOR);
        var childId = service.retrySameKey(parentId, "internal-3b", T0.plusSeconds(40), ACTOR);

        var parent = attemptRepository.findById(parentId).orElseThrow();
        var child = attemptRepository.findById(childId).orElseThrow();
        assertThat(parent.getStatus()).isEqualTo(SubmissionAttemptStatus.RECONCILED_NO_MATCH);
        assertThat(child.getRetryOfAttemptId()).isEqualTo(parentId);
        assertThat(child.getAttemptNumber()).isEqualTo(2);
        assertThat(child.getClientOrderId()).isEqualTo(parent.getClientOrderId());
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.SUBMISSION_PENDING);
        assertThatThrownBy(() -> service.retrySameKey(parentId, "internal-3c", T0.plusSeconds(41), ACTOR))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void incompleteOrExpiredNoMatchRequiresManualReviewAndDoesNotRetry() {
        var intentId = submissionPendingIntent("10");
        var attemptId = unknownAttempt(intentId, "client-4", "hash-4", "internal-4");

        service.recordReconciliation(attemptId, noMatch(T0.plusSeconds(600)), ACTOR);

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.RECONCILIATION_FAILED);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
        assertThatThrownBy(() -> service.retrySameKey(attemptId, "internal-4b", T0.plusSeconds(601), ACTOR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reconciliationBeforeUnknownFinishIsRejected() {
        var intentId = submissionPendingIntent("10");
        var attemptId = unknownAttempt(intentId, "client-boundary-1", "hash-boundary-1", "internal-boundary-1");

        assertThatThrownBy(() -> service.recordReconciliation(attemptId, noMatch(T0.plusSeconds(2)), ACTOR))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.UNKNOWN);
        assertThat(count("reconciliation_checks", intentId)).isZero();
    }

    @Test
    void noMatchJustBeforeExpiryCanRetryAtSameInstantButExpiryCannotRetry() {
        var intentId = submissionPendingIntent("10");
        var parentId = unknownAttempt(intentId, "client-boundary-2", "hash-boundary-2", "internal-boundary-2");
        var checkedAt = T0.plusSeconds(600).minusMillis(1);

        service.recordReconciliation(parentId, noMatch(checkedAt), ACTOR);
        var childId = service.retrySameKey(parentId, "internal-boundary-2b", checkedAt, ACTOR);

        assertThat(attemptRepository.findById(childId).orElseThrow().getCreatedAt()).isEqualTo(checkedAt);

        var expiredIntentId = submissionPendingIntent("10");
        var expiredAttemptId = unknownAttempt(
                expiredIntentId, "client-boundary-3", "hash-boundary-3", "internal-boundary-3");
        service.recordReconciliation(expiredAttemptId, noMatch(T0.plusSeconds(600)), ACTOR);

        assertThatThrownBy(() -> service.retrySameKey(
                expiredAttemptId, "internal-boundary-3b", T0.plusSeconds(600), ACTOR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void brokerEvidenceWithoutBrokerOrderIdRequiresManualReviewAndWritesLedger() {
        var intentId = submissionPendingIntent("10");
        var attemptId = unknownAttempt(intentId, "client-no-id", "hash-no-id", "internal-no-id");

        service.recordReconciliation(attemptId, new OrderSubmissionService.ReconciliationResult(
                true,
                true,
                T0.minusSeconds(60),
                T0.plusSeconds(60),
                true,
                "result-no-id",
                null,
                null,
                BrokerOrderStatus.FILLED,
                new BigDecimal("1"),
                T0.plusSeconds(30)), ACTOR);

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.RECONCILIATION_FAILED);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
        assertSubmissionLedger(intentId, "ManualReviewRequired", 4);
    }

    @Test
    void brokerFoundLinksAttemptStoresExecutionAndMapsTerminalStatus() {
        var intentId = submissionPendingIntent("10");
        var attemptId = unknownAttempt(intentId, "client-5", "hash-5", "internal-5");

        service.recordReconciliation(attemptId, brokerFound(
                "broker-5", "client-5", BrokerOrderStatus.FILLED, new BigDecimal("10"), T0.plusSeconds(30)), ACTOR);

        var intent = orderIntentRepository.findById(intentId).orElseThrow();
        var brokerOrder = brokerOrderRepository.findByBrokerAccountIdAndBrokerOrderId(
                intent.getBrokerAccountId(), "broker-5").orElseThrow();
        assertThat(attemptRepository.findById(attemptId).orElseThrow().getConfirmedBrokerOrderId())
                .isEqualTo(brokerOrder.getId());
        assertThat(intent.getStatus()).isEqualTo(OrderIntentStatus.COMPLETED);
        assertThat(intent.getFinalFilledQuantity()).isEqualByComparingTo("10");
        assertThat(executionSnapshotCount(brokerOrder.getId())).isEqualTo(1);
    }

    @Test
    void brokerStatusDrivesActiveCanceledPartialAndManualReviewOutcomes() {
        assertBrokerOutcome(BrokerOrderStatus.PENDING, "0", OrderIntentStatus.ACTIVE);
        assertBrokerOutcome(BrokerOrderStatus.CANCELED, "0", OrderIntentStatus.CANCELED);
        assertBrokerOutcome(BrokerOrderStatus.CANCELED, "4", OrderIntentStatus.PARTIALLY_COMPLETED);
        assertBrokerOutcome(BrokerOrderStatus.FILLED, "4", OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
    }

    @Test
    void existingBrokerOrderConflictRequiresManualReviewAndWritesLedger() {
        var accountId = UUID.randomUUID();
        var ownerIntentId = submissionPendingIntent("10", accountId);
        brokerOrderRepository.saveAndFlush(BrokerOrder.confirmed(
                UUID.randomUUID(), ownerIntentId, accountId, "broker-conflict", "owner-client", BrokerOrderStatus.PENDING));
        var intentId = submissionPendingIntent("10", accountId);
        var attemptId = unknownAttempt(intentId, "client-conflict", "hash-conflict", "internal-conflict");

        service.recordReconciliation(attemptId, brokerFound(
                "broker-conflict", "client-conflict", BrokerOrderStatus.PENDING, BigDecimal.ZERO, T0.plusSeconds(30)), ACTOR);

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.RECONCILIATION_FAILED);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
        assertSubmissionLedger(intentId, "ManualReviewRequired", 4);
    }

    @Test
    void sameIntentBrokerOrderWithDifferentClientOrderIdRequiresManualReviewAndWritesLedger() {
        var intentId = submissionPendingIntent("10");
        var attemptId = unknownAttempt(intentId, "client-mismatch", "hash-mismatch", "internal-mismatch");
        var accountId = attemptRepository.findById(attemptId).orElseThrow().getBrokerAccountId();
        brokerOrderRepository.saveAndFlush(BrokerOrder.confirmed(
                UUID.randomUUID(), intentId, accountId, "broker-mismatch", "other-client", BrokerOrderStatus.PENDING));

        service.recordReconciliation(attemptId, brokerFound(
                "broker-mismatch", "client-mismatch", BrokerOrderStatus.PENDING, BigDecimal.ZERO, T0.plusSeconds(30)), ACTOR);

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.RECONCILIATION_FAILED);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
        assertSubmissionLedger(intentId, "ManualReviewRequired", 4);
    }

    @Test
    void reconciliationBrokerClientOrderIdMismatchRequiresManualReviewWithoutBrokerOrder() {
        var intentId = submissionPendingIntent("10");
        var attemptId = unknownAttempt(intentId, "client-reconcile-match", "hash-reconcile-match", "internal-reconcile-match");

        service.recordReconciliation(attemptId, brokerFound(
                "broker-reconcile-match",
                "different-client",
                BrokerOrderStatus.PENDING,
                BigDecimal.ZERO,
                T0.plusSeconds(30)), ACTOR);

        var intent = orderIntentRepository.findById(intentId).orElseThrow();
        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.RECONCILIATION_FAILED);
        assertThat(intent.getStatus()).isEqualTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(brokerOrderRepository.findByBrokerAccountIdAndBrokerOrderId(
                intent.getBrokerAccountId(), "broker-reconcile-match")).isEmpty();
        assertSubmissionLedger(intentId, "ManualReviewRequired", 4);
    }

    @Test
    void directBrokerConfirmationAcknowledgesDispatchingAttemptWithoutReconciliationCheck() {
        var intentId = submissionPendingIntent("10");
        var attemptId = service.createInitialAttempt(
                intentId, "client-direct", "hash-direct", "internal-direct", T0, ACTOR);
        service.startDispatch(attemptId, T0.plusSeconds(1), new DispatchEvidence("client-direct", "sent"), ACTOR);

        service.confirmBrokerOrder(attemptId, new OrderSubmissionService.BrokerConfirmation(
                "broker-direct",
                "client-direct",
                BrokerOrderStatus.PENDING,
                BigDecimal.ZERO,
                T0.plusSeconds(2)), ACTOR);

        var intent = orderIntentRepository.findById(intentId).orElseThrow();
        var brokerOrder = brokerOrderRepository.findByBrokerAccountIdAndBrokerOrderId(
                intent.getBrokerAccountId(), "broker-direct").orElseThrow();
        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.ACKNOWLEDGED);
        assertThat(attemptRepository.findById(attemptId).orElseThrow().getConfirmedBrokerOrderId())
                .isEqualTo(brokerOrder.getId());
        assertThat(intent.getStatus()).isEqualTo(OrderIntentStatus.ACTIVE);
        assertThat(count("reconciliation_checks", intentId)).isZero();
        assertThat(executionSnapshotCount(brokerOrder.getId())).isEqualTo(1);
        assertSubmissionLedger(intentId, "BrokerOrderConfirmed", 3);
    }

    @Test
    void directBrokerConfirmationUsesBrokerStatusForTerminalOutcome() {
        var intentId = submissionPendingIntent("10");
        var attemptId = service.createInitialAttempt(
                intentId, "client-direct-filled", "hash-direct-filled", "internal-direct-filled", T0, ACTOR);
        service.startDispatch(
                attemptId, T0.plusSeconds(1), new DispatchEvidence("client-direct-filled", "sent"), ACTOR);

        service.confirmBrokerOrder(attemptId, new OrderSubmissionService.BrokerConfirmation(
                "broker-direct-filled",
                "client-direct-filled",
                BrokerOrderStatus.FILLED,
                new BigDecimal("10"),
                T0.plusSeconds(2)), ACTOR);

        var intent = orderIntentRepository.findById(intentId).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(OrderIntentStatus.COMPLETED);
        assertThat(intent.getFinalFilledQuantity()).isEqualByComparingTo("10");
        assertThat(intent.getRemainingQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void directBrokerConfirmationWithInconsistentFillRequiresManualReviewAfterAcknowledgement() {
        var intentId = submissionPendingIntent("10");
        var attemptId = service.createInitialAttempt(
                intentId, "client-direct-review", "hash-direct-review", "internal-direct-review", T0, ACTOR);
        service.startDispatch(
                attemptId, T0.plusSeconds(1), new DispatchEvidence("client-direct-review", "sent"), ACTOR);

        service.confirmBrokerOrder(attemptId, new OrderSubmissionService.BrokerConfirmation(
                "broker-direct-review",
                "client-direct-review",
                BrokerOrderStatus.FILLED,
                new BigDecimal("4"),
                T0.plusSeconds(2)), ACTOR);

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.ACKNOWLEDGED);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
    }

    @Test
    void preBrokerIdRejectionTerminatesIntentWithZeroFill() {
        var intentId = submissionPendingIntent("10");
        var attemptId = service.createInitialAttempt(intentId, "client-6", "hash-6", "internal-6", T0, ACTOR);
        service.startDispatch(attemptId, T0.plusSeconds(1), new DispatchEvidence("req-6", "sent"), ACTOR);

        service.rejectBeforeBrokerOrderId(
                attemptId, T0.plusSeconds(2), new DispatchEvidence("req-6", "rejected"), ACTOR);

        var intent = orderIntentRepository.findById(intentId).orElseThrow();
        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.BROKER_REJECTED);
        assertThat(intent.getStatus()).isEqualTo(OrderIntentStatus.REJECTED);
        assertThat(intent.getFinalFilledQuantity()).isEqualByComparingTo("0");
        assertThat(intent.getRemainingQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void submissionOutboxFailureRollsBackAttemptAndIntentChanges() {
        var intentId = submissionPendingIntent("10");
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_order_submission_outbox_insert()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced submission outbox failure';
                END;
                $$;
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_fail_order_submission_outbox_insert
                BEFORE INSERT ON order_submission_outbox_events
                FOR EACH ROW
                EXECUTE FUNCTION fail_order_submission_outbox_insert()
                """);

        assertThatThrownBy(() -> service.createInitialAttempt(
                intentId, "client-7", "hash-7", "internal-7", T0, ACTOR))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("submission_attempts", intentId)).isZero();
        assertThat(count("order_submission_audit_logs", intentId)).isZero();
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.SUBMISSION_PENDING);
    }

    @Test
    void optimisticLockLoserRollsBackReconciliationLedger() throws Exception {
        var intentId = submissionPendingIntent("10");
        var attemptId = unknownAttempt(intentId, "client-8", "hash-8", "internal-8");
        ATTEMPT_REPOSITORY_FIND_GATE.enable(attemptId, 2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<ConcurrentResult> command = () -> recordNoMatchConcurrently(attemptId);
            var first = executor.submit(command);
            var second = executor.submit(command);

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(ConcurrentResult.COMMITTED, ConcurrentResult.OPTIMISTIC_LOCK_REJECTED);
        }

        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.RECONCILED_NO_MATCH);
        assertThat(count("reconciliation_checks", intentId)).isEqualTo(1);
        assertThat(count("order_submission_audit_logs", intentId)).isEqualTo(4);
        assertThat(count("order_submission_outbox_events", intentId)).isEqualTo(4);
    }

    private UUID unknownAttempt(UUID intentId, String clientOrderId, String hash, String internalKey) {
        var attemptId = service.createInitialAttempt(intentId, clientOrderId, hash, internalKey, T0, ACTOR);
        service.startDispatch(attemptId, T0.plusSeconds(1), new DispatchEvidence(clientOrderId, "sent"), ACTOR);
        service.markUnknown(attemptId, T0.plusSeconds(2), new DispatchEvidence(clientOrderId, "timeout"), ACTOR);
        return attemptId;
    }

    private OrderSubmissionService.ReconciliationResult noMatch(Instant checkedAt) {
        return new OrderSubmissionService.ReconciliationResult(
                true,
                true,
                T0.minusSeconds(60),
                T0.plusSeconds(60),
                true,
                "result-" + checkedAt.getEpochSecond(),
                null,
                null,
                null,
                BigDecimal.ZERO,
                checkedAt);
    }

    private OrderSubmissionService.ReconciliationResult brokerFound(
            String brokerOrderId,
            String brokerReturnedClientOrderId,
            BrokerOrderStatus status,
            BigDecimal filledQuantity,
            Instant checkedAt
    ) {
        return new OrderSubmissionService.ReconciliationResult(
                true,
                true,
                T0.minusSeconds(60),
                T0.plusSeconds(60),
                true,
                "result-" + brokerOrderId,
                brokerOrderId,
                brokerReturnedClientOrderId,
                status,
                filledQuantity,
                checkedAt);
    }

    private void assertBrokerOutcome(BrokerOrderStatus brokerStatus, String filled, OrderIntentStatus expected) {
        var intentId = submissionPendingIntent("10");
        var clientOrderId = "client-" + UUID.randomUUID().toString().substring(0, 8);
        var attemptId = unknownAttempt(
                intentId,
                clientOrderId,
                "hash-" + UUID.randomUUID(),
                "internal-" + UUID.randomUUID());

        service.recordReconciliation(attemptId, brokerFound(
                "broker-" + UUID.randomUUID(),
                clientOrderId,
                brokerStatus,
                new BigDecimal(filled),
                T0.plusSeconds(30)), ACTOR);

        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus()).isEqualTo(expected);
    }

    private UUID submissionPendingIntent(String quantity) {
        return submissionPendingIntent(quantity, UUID.randomUUID());
    }

    private UUID submissionPendingIntent(String quantity, UUID accountId) {
        var intentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO broker_accounts (id) VALUES (?) ON CONFLICT DO NOTHING", accountId);
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(intentId, accountId, new BigDecimal(quantity)));
        transitionService.approve(intentId, ACTOR);
        transitionService.startRevalidation(intentId, ACTOR);
        transitionService.markSubmissionPending(intentId, ACTOR);
        return intentId;
    }

    private void assertIntentLedger(
            UUID intentId,
            OrderIntentStatus fromStatus,
            OrderIntentStatus toStatus
    ) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM order_intent_audit_logs
                 WHERE order_intent_id = ? AND from_status = ? AND to_status = ?
                """, Long.class, intentId, fromStatus.name(), toStatus.name())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM order_intent_outbox_events
                 WHERE order_intent_id = ? AND from_status = ? AND to_status = ?
                """, Long.class, intentId, fromStatus.name(), toStatus.name())).isEqualTo(1);
    }

    private void assertSubmissionLedger(UUID intentId, String action, long expectedRows) {
        assertThat(count("order_submission_audit_logs", intentId)).isEqualTo(expectedRows);
        assertThat(count("order_submission_outbox_events", intentId)).isEqualTo(expectedRows);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM order_submission_audit_logs
                 WHERE order_intent_id = ? AND action = ?
                """, Long.class, intentId, action)).isEqualTo(1);
    }

    private long executionSnapshotCount(UUID brokerOrderId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM execution_snapshots WHERE broker_order_id = ?",
                Long.class,
                brokerOrderId);
    }

    private long count(String table, UUID intentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE order_intent_id = ?",
                Long.class,
                intentId);
    }

    private ConcurrentResult recordNoMatchConcurrently(UUID attemptId) {
        try {
            service.recordReconciliation(attemptId, noMatch(T0.plusSeconds(30)), ACTOR);
            return ConcurrentResult.COMMITTED;
        } catch (RuntimeException exception) {
            if (hasOptimisticLockCause(exception)) {
                return ConcurrentResult.OPTIMISTIC_LOCK_REJECTED;
            }
            throw exception;
        }
    }

    private boolean hasOptimisticLockCause(Throwable throwable) {
        for (var current = throwable; current != null; current = current.getCause()) {
            if (current instanceof OptimisticLockException
                    || current instanceof OptimisticLockingFailureException
                    || current instanceof ObjectOptimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }

    enum ConcurrentResult {
        COMMITTED,
        OPTIMISTIC_LOCK_REJECTED
    }

    @TestConfiguration
    static class AttemptRepositoryFindGateConfiguration {
        @Bean
        static BeanPostProcessor submissionAttemptRepositoryFindGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (!(bean instanceof SubmissionAttemptRepository repository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            bean.getClass().getClassLoader(),
                            new Class<?>[]{SubmissionAttemptRepository.class},
                            (proxy, method, args) -> {
                                var result = method.invoke(repository, args);
                                if ("findById".equals(method.getName())
                                        && args != null
                                        && args.length == 1
                                        && args[0] instanceof UUID id
                                        && result instanceof Optional<?> optional
                                        && optional.orElse(null) instanceof SubmissionAttempt) {
                                    ATTEMPT_REPOSITORY_FIND_GATE.afterFindById(id);
                                }
                                return result;
                            });
                }
            };
        }
    }

    static class AttemptRepositoryFindGate {
        private UUID attemptId;
        private CountDownLatch loaded;
        private CountDownLatch release;

        synchronized void enable(UUID attemptId, int parties) {
            this.attemptId = attemptId;
            this.loaded = new CountDownLatch(parties);
            this.release = new CountDownLatch(1);
        }

        synchronized void disable() {
            attemptId = null;
            loaded = null;
            release = null;
        }

        void afterFindById(UUID foundAttemptId) throws InterruptedException {
            CountDownLatch localLoaded;
            CountDownLatch localRelease;
            synchronized (this) {
                if (!foundAttemptId.equals(attemptId)) {
                    return;
                }
                localLoaded = loaded;
                localRelease = release;
            }
            localLoaded.countDown();
            if (localLoaded.await(5, TimeUnit.SECONDS)) {
                localRelease.countDown();
            }
            if (!localRelease.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent attempt repository find gate timed out");
            }
        }
    }
}
