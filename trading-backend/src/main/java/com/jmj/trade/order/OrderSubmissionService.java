package com.jmj.trade.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class OrderSubmissionService {

    private final OrderIntentRepository orderIntentRepository;
    private final SubmissionIdempotencyKeyRepository idempotencyKeyRepository;
    private final SubmissionAttemptRepository attemptRepository;
    private final ReconciliationCheckRepository reconciliationCheckRepository;
    private final BrokerOrderRepository brokerOrderRepository;
    private final OrderIntentAuditLogRepository intentAuditLogRepository;
    private final OrderIntentOutboxEventRepository intentOutboxEventRepository;
    private final OrderSubmissionAuditLogRepository submissionAuditLogRepository;
    private final OrderSubmissionOutboxEventRepository submissionOutboxEventRepository;
    private final JdbcTemplate jdbcTemplate;

    public OrderSubmissionService(
            OrderIntentRepository orderIntentRepository,
            SubmissionIdempotencyKeyRepository idempotencyKeyRepository,
            SubmissionAttemptRepository attemptRepository,
            ReconciliationCheckRepository reconciliationCheckRepository,
            BrokerOrderRepository brokerOrderRepository,
            OrderIntentAuditLogRepository intentAuditLogRepository,
            OrderIntentOutboxEventRepository intentOutboxEventRepository,
            OrderSubmissionAuditLogRepository submissionAuditLogRepository,
            OrderSubmissionOutboxEventRepository submissionOutboxEventRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.orderIntentRepository = orderIntentRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.attemptRepository = attemptRepository;
        this.reconciliationCheckRepository = reconciliationCheckRepository;
        this.brokerOrderRepository = brokerOrderRepository;
        this.intentAuditLogRepository = intentAuditLogRepository;
        this.intentOutboxEventRepository = intentOutboxEventRepository;
        this.submissionAuditLogRepository = submissionAuditLogRepository;
        this.submissionOutboxEventRepository = submissionOutboxEventRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public UUID createInitialAttempt(
            UUID orderIntentId,
            String clientOrderId,
            String requestBodyHash,
            String internalIdempotencyKey,
            Instant createdAt,
            String actor
    ) {
        requireActor(actor);
        var intent = intent(orderIntentId);
        if (intent.getStatus() != OrderIntentStatus.SUBMISSION_PENDING) {
            throw new IllegalStateException("initial attempt requires SUBMISSION_PENDING intent");
        }
        var expiresAt = createdAt.plusSeconds(600);
        idempotencyKeyRepository.saveAndFlush(SubmissionIdempotencyKey.create(
                intent.getBrokerAccountId(),
                clientOrderId,
                intent.getId(),
                requestBodyHash,
                expiresAt,
                createdAt));
        var attempt = SubmissionAttempt.initial(
                UUID.randomUUID(),
                intent.getId(),
                intent.getBrokerAccountId(),
                clientOrderId,
                requestBodyHash,
                internalIdempotencyKey,
                createdAt);
        attemptRepository.saveAndFlush(attempt);
        submissionLedger(intent.getId(), "SubmissionAttempt", attempt.getId(),
                "InitialAttemptCreated", "InitialAttemptCreated", actor,
                "{\"attemptId\":\"%s\"}".formatted(attempt.getId()), createdAt);
        return attempt.getId();
    }

    @Transactional
    public void startDispatch(UUID attemptId, Instant startedAt, DispatchEvidence evidence, String actor) {
        requireActor(actor);
        var attempt = attempt(attemptId);
        attempt.startDispatch(startedAt, evidence);
        attemptRepository.saveAndFlush(attempt);
        submissionLedger(attempt.getOrderIntentId(), "SubmissionAttempt", attempt.getId(),
                "SubmissionDispatchStarted", "SubmissionDispatchStarted", actor,
                "{\"attemptId\":\"%s\"}".formatted(attempt.getId()), startedAt);
    }

    @Transactional
    public void markUnknown(UUID attemptId, Instant finishedAt, DispatchEvidence evidence, String actor) {
        requireActor(actor);
        var attempt = attempt(attemptId);
        var intent = intent(attempt.getOrderIntentId());
        attempt.markUnknown(finishedAt, evidence);
        attemptRepository.saveAndFlush(attempt);
        transitionIntent(intent, actor, finishedAt, null, OrderIntent::requireReconciliation);
        submissionLedger(intent.getId(), "SubmissionAttempt", attempt.getId(),
                "SubmissionUnknown", "SubmissionUnknown", actor,
                "{\"attemptId\":\"%s\"}".formatted(attempt.getId()), finishedAt);
    }

    @Transactional
    public void rejectBeforeBrokerOrderId(UUID attemptId, Instant finishedAt, DispatchEvidence evidence, String actor) {
        requireActor(actor);
        var attempt = attempt(attemptId);
        var intent = intent(attempt.getOrderIntentId());
        attempt.reject(finishedAt, evidence);
        attemptRepository.saveAndFlush(attempt);
        transitionIntent(intent, actor, finishedAt, "BROKER_REJECTED_BEFORE_ORDER_ID",
                i -> i.terminate(OrderIntentStatus.REJECTED, "BROKER_REJECTED_BEFORE_ORDER_ID",
                        finishedAt, BigDecimal.ZERO));
        submissionLedger(intent.getId(), "SubmissionAttempt", attempt.getId(),
                "SubmissionRejectedBeforeBrokerOrderId", "SubmissionRejectedBeforeBrokerOrderId", actor,
                "{\"attemptId\":\"%s\"}".formatted(attempt.getId()), finishedAt);
    }

    @Transactional
    public void confirmBrokerOrder(UUID attemptId, BrokerConfirmation confirmation, String actor) {
        requireActor(actor);
        var attempt = attempt(attemptId);
        var intent = intent(attempt.getOrderIntentId());
        if (attempt.getStatus() != SubmissionAttemptStatus.DISPATCHING) {
            throw new IllegalStateException("broker confirmation requires DISPATCHING attempt");
        }
        var brokerOrder = brokerOrderOrNullOnConflict(
                attempt,
                intent,
                confirmation.brokerOrderId(),
                confirmation.brokerReturnedClientOrderId(),
                confirmation.brokerStatus());
        if (brokerOrder == null) {
            throw new IllegalStateException("broker order confirmation does not match submission attempt");
        }
        attempt.acknowledge(
                confirmation.capturedAt(),
                brokerOrder.getId(),
                new DispatchEvidence(confirmation.brokerReturnedClientOrderId(), "confirmed"));
        attemptRepository.saveAndFlush(attempt);
        insertExecutionSnapshot(brokerOrder.getId(), confirmation.cumulativeFilledQuantity(), confirmation.capturedAt());
        applyBrokerOutcome(
                intent,
                confirmation.brokerStatus(),
                confirmation.cumulativeFilledQuantity(),
                actor,
                confirmation.capturedAt());
        submissionLedger(intent.getId(), "SubmissionAttempt", attempt.getId(),
                "BrokerOrderConfirmed", "BrokerOrderConfirmed", actor,
                "{\"brokerOrderId\":\"%s\"}".formatted(brokerOrder.getId()), confirmation.capturedAt());
    }

    @Transactional
    public void recordReconciliation(UUID attemptId, ReconciliationResult result, String actor) {
        requireActor(actor);
        var attempt = attempt(attemptId);
        var intent = intent(attempt.getOrderIntentId());
        requireCheckedAfterUnknown(attempt, result.checkedAt());
        attempt.startReconciliation();
        attemptRepository.saveAndFlush(attempt);

        if (result.brokerOrderId() == null && hasBrokerEvidence(result)) {
            manualReview(attempt, intent, result, actor);
        } else if (result.brokerOrderId() == null) {
            recordNoMatch(attempt, intent, result, actor);
        } else {
            recordBrokerFound(attempt, intent, result, actor);
        }
    }

    @Transactional
    public UUID retrySameKey(UUID parentAttemptId, String internalIdempotencyKey, Instant createdAt, String actor) {
        requireActor(actor);
        var parent = attempt(parentAttemptId);
        var latest = reconciliationCheckRepository.findTopBySubmissionAttemptIdOrderByCheckNumberDesc(parentAttemptId)
                .orElseThrow(() -> new IllegalStateException("latest reconciliation check is required"));
        var child = SubmissionAttempt.retry(
                UUID.randomUUID(),
                parent,
                internalIdempotencyKey,
                createdAt,
                latest.getDecision());
        attemptRepository.saveAndFlush(child);
        var intent = intent(parent.getOrderIntentId());
        transitionIntent(intent, actor, createdAt, null, OrderIntent::markSubmissionPending);
        submissionLedger(intent.getId(), "SubmissionAttempt", child.getId(),
                "SameKeyRetryCreated", "SameKeyRetryCreated", actor,
                "{\"parentAttemptId\":\"%s\",\"attemptId\":\"%s\"}".formatted(parentAttemptId, child.getId()),
                createdAt);
        return child.getId();
    }

    private void recordNoMatch(
            SubmissionAttempt attempt,
            OrderIntent intent,
            ReconciliationResult result,
            String actor
    ) {
        var retryAllowed = result.openOrdersComplete()
                && result.closedOrdersComplete()
                && result.allPagesRead()
                && result.checkedAt().isBefore(attempt.getIdempotencyExpiresAt());
        var decision = retryAllowed
                ? ReconciliationDecision.RETRY_SAME_KEY_ALLOWED
                : ReconciliationDecision.MANUAL_REVIEW_REQUIRED;
        var checkNumber = attempt.allocateNextReconciliationCheckNumber();
        attemptRepository.saveAndFlush(attempt);
        reconciliationCheckRepository.saveAndFlush(check(attempt, result, null, decision, checkNumber));
        if (retryAllowed) {
            attempt.markNoMatch(result.checkedAt());
            attemptRepository.saveAndFlush(attempt);
            submissionLedger(intent.getId(), "SubmissionAttempt", attempt.getId(),
                    "ReconciliationCheckRecorded", "ReconciliationCheckRecorded", actor,
                    "{\"checkNumber\":" + checkNumber + ",\"decision\":\"" + decision + "\"}",
                    result.checkedAt());
        } else {
            attempt.markReconciliationFailed(result.checkedAt());
            attemptRepository.saveAndFlush(attempt);
            transitionIntent(intent, actor, result.checkedAt(), null, OrderIntent::requireManualReview);
            submissionLedger(intent.getId(), "SubmissionAttempt", attempt.getId(),
                    "ManualReviewRequired", "ManualReviewRequired", actor,
                    "{\"checkNumber\":" + checkNumber + ",\"decision\":\"" + decision + "\"}",
                    result.checkedAt());
        }
    }

    private void recordBrokerFound(
            SubmissionAttempt attempt,
            OrderIntent intent,
            ReconciliationResult result,
            String actor
    ) {
        var brokerOrder = brokerOrderOrNullOnConflict(
                attempt,
                intent,
                result.brokerOrderId(),
                result.brokerReturnedClientOrderId(),
                result.brokerStatus());
        if (brokerOrder == null) {
            manualReview(attempt, intent, result, actor);
            return;
        }
        recordBrokerFound(attempt, intent, result, actor, brokerOrder);
    }

    private void recordBrokerFound(
            SubmissionAttempt attempt,
            OrderIntent intent,
            ReconciliationResult result,
            String actor,
            BrokerOrder brokerOrder
    ) {
        var checkNumber = attempt.allocateNextReconciliationCheckNumber();
        attemptRepository.saveAndFlush(attempt);
        reconciliationCheckRepository.saveAndFlush(check(
                attempt,
                result,
                brokerOrder.getId(),
                ReconciliationDecision.BROKER_ORDER_FOUND,
                checkNumber));
        attempt.acknowledge(
                result.checkedAt(),
                brokerOrder.getId(),
                new DispatchEvidence(attempt.getClientOrderId(), "reconciled"));
        attemptRepository.saveAndFlush(attempt);
        insertExecutionSnapshot(brokerOrder.getId(), result.cumulativeFilledQuantity(), result.checkedAt());
        applyBrokerOutcome(intent, result.brokerStatus(), result.cumulativeFilledQuantity(), actor, result.checkedAt());
        submissionLedger(intent.getId(), "SubmissionAttempt", attempt.getId(),
                "BrokerOrderFound", "BrokerOrderFound", actor,
                "{\"checkNumber\":" + checkNumber + ",\"brokerOrderId\":\"" + brokerOrder.getId() + "\"}",
                result.checkedAt());
    }

    private void manualReview(
            SubmissionAttempt attempt,
            OrderIntent intent,
            ReconciliationResult result,
            String actor
    ) {
        var checkNumber = attempt.allocateNextReconciliationCheckNumber();
        attemptRepository.saveAndFlush(attempt);
        reconciliationCheckRepository.saveAndFlush(check(
                attempt,
                result,
                null,
                ReconciliationDecision.MANUAL_REVIEW_REQUIRED,
                checkNumber));
        attempt.markReconciliationFailed(result.checkedAt());
        attemptRepository.saveAndFlush(attempt);
        transitionIntent(intent, actor, result.checkedAt(), null, OrderIntent::requireManualReview);
        submissionLedger(intent.getId(), "SubmissionAttempt", attempt.getId(),
                "ManualReviewRequired", "ManualReviewRequired", actor,
                "{\"checkNumber\":" + checkNumber + ",\"decision\":\""
                        + ReconciliationDecision.MANUAL_REVIEW_REQUIRED + "\"}",
                result.checkedAt());
    }

    private BrokerOrder brokerOrderOrNullOnConflict(
            SubmissionAttempt attempt,
            OrderIntent intent,
            String brokerOrderId,
            String brokerReturnedClientOrderId,
            BrokerOrderStatus brokerStatus
    ) {
        if (!attempt.getClientOrderId().equals(brokerReturnedClientOrderId)) {
            return null;
        }
        var existingBrokerOrder = brokerOrderRepository.findByBrokerAccountIdAndBrokerOrderId(
                attempt.getBrokerAccountId(), brokerOrderId);
        if (existingBrokerOrder.isPresent()) {
            var existing = existingBrokerOrder.get();
            if (!existing.getOrderIntentId().equals(intent.getId())
                    || !existing.getClientOrderId().equals(attempt.getClientOrderId())) {
                return null;
            }
            existing.updateProjection(brokerStatus);
            return brokerOrderRepository.saveAndFlush(existing);
        }
        return brokerOrderRepository.saveAndFlush(BrokerOrder.confirmed(
                UUID.randomUUID(),
                intent.getId(),
                intent.getBrokerAccountId(),
                brokerOrderId,
                brokerReturnedClientOrderId,
                brokerStatus));
    }

    private void applyBrokerOutcome(
            OrderIntent intent,
            BrokerOrderStatus brokerStatus,
            BigDecimal filled,
            String actor,
            Instant occurredAt
    ) {
        if (filled.compareTo(BigDecimal.ZERO) < 0
                || filled.compareTo(intent.getQuantity()) > 0
                || (brokerStatus == BrokerOrderStatus.FILLED
                && filled.compareTo(intent.getQuantity()) != 0)) {
            requireManualReviewAfterBrokerOutcome(intent, actor, occurredAt);
            return;
        }
        if (isOpen(brokerStatus)) {
            transitionIntent(intent, actor, occurredAt, null, OrderIntent::activate);
            return;
        }
        transitionIntent(intent, actor, occurredAt, null, OrderIntent::activate);
        if (brokerStatus == BrokerOrderStatus.FILLED && filled.compareTo(intent.getQuantity()) == 0) {
            transitionIntent(intent, actor, occurredAt, "BROKER_FILLED",
                    i -> i.terminate(OrderIntentStatus.COMPLETED, "BROKER_FILLED", occurredAt, filled));
        } else if (filled.compareTo(BigDecimal.ZERO) == 0) {
            transitionIntent(intent, actor, occurredAt, "BROKER_CLOSED_WITH_ZERO_FILL",
                    i -> i.terminate(OrderIntentStatus.CANCELED, "BROKER_CLOSED_WITH_ZERO_FILL",
                            occurredAt, BigDecimal.ZERO));
        } else {
            transitionIntent(intent, actor, occurredAt, "BROKER_CLOSED_PARTIAL_FILL",
                    i -> i.terminate(OrderIntentStatus.PARTIALLY_COMPLETED, "BROKER_CLOSED_PARTIAL_FILL",
                            occurredAt, filled));
        }
    }

    private void requireManualReviewAfterBrokerOutcome(OrderIntent intent, String actor, Instant occurredAt) {
        if (intent.getStatus() == OrderIntentStatus.SUBMISSION_PENDING) {
            transitionIntent(intent, actor, occurredAt, null, OrderIntent::activate);
        }
        transitionIntent(intent, actor, occurredAt, null, OrderIntent::requireManualReview);
    }

    private static boolean isOpen(BrokerOrderStatus status) {
        return status == BrokerOrderStatus.PENDING
                || status == BrokerOrderStatus.PARTIALLY_FILLED
                || status == BrokerOrderStatus.CANCELING
                || status == BrokerOrderStatus.REPLACING;
    }

    private static boolean hasBrokerEvidence(ReconciliationResult result) {
        return result.brokerStatus() != null || result.cumulativeFilledQuantity().compareTo(BigDecimal.ZERO) != 0;
    }

    private static void requireCheckedAfterUnknown(SubmissionAttempt attempt, Instant checkedAt) {
        if (attempt.getStatus() != SubmissionAttemptStatus.UNKNOWN) {
            throw new IllegalStateException("reconciliation requires UNKNOWN attempt");
        }
        if (!checkedAt.isAfter(attempt.getFinishedAt())) {
            throw new IllegalArgumentException("checkedAt must be after UNKNOWN finishedAt");
        }
    }

    private ReconciliationCheck check(
            SubmissionAttempt attempt,
            ReconciliationResult result,
            UUID matchedBrokerOrderId,
            ReconciliationDecision decision,
            int checkNumber
    ) {
        return ReconciliationCheck.record(
                UUID.randomUUID(),
                attempt.getId(),
                attempt.getOrderIntentId(),
                checkNumber,
                result.openOrdersComplete(),
                result.closedOrdersComplete(),
                result.closedWindowStart(),
                result.closedWindowEnd(),
                result.allPagesRead(),
                result.resultHash(),
                matchedBrokerOrderId,
                decision,
                result.checkedAt());
    }

    private void insertExecutionSnapshot(UUID brokerOrderId, BigDecimal filledQuantity, Instant capturedAt) {
        jdbcTemplate.update("""
                INSERT INTO execution_snapshots (id, broker_order_id, filled_quantity, captured_at)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), brokerOrderId, filledQuantity,
                OffsetDateTime.ofInstant(capturedAt, ZoneOffset.UTC));
    }

    private void transitionIntent(
            OrderIntent intent,
            String actor,
            Instant occurredAt,
            String terminalReason,
            IntentCommand command
    ) {
        var fromStatus = intent.getStatus();
        command.apply(intent);
        orderIntentRepository.saveAndFlush(intent);
        intentAuditLogRepository.save(OrderIntentAuditLog.statusChanged(
                intent.getId(), fromStatus, intent.getStatus(), actor, terminalReason, occurredAt));
        intentOutboxEventRepository.saveAndFlush(OrderIntentOutboxEvent.statusChanged(
                intent.getId(), fromStatus, intent.getStatus(), actor, terminalReason, occurredAt));
    }

    private void submissionLedger(
            UUID orderIntentId,
            String aggregateType,
            UUID aggregateId,
            String action,
            String eventType,
            String actor,
            String payload,
            Instant occurredAt
    ) {
        submissionAuditLogRepository.save(new OrderSubmissionAuditLog(
                UUID.randomUUID(), orderIntentId, aggregateType, aggregateId, action, actor, payload, occurredAt));
        submissionOutboxEventRepository.saveAndFlush(new OrderSubmissionOutboxEvent(
                UUID.randomUUID(), orderIntentId, aggregateType, aggregateId, eventType, actor, payload, occurredAt));
    }

    private OrderIntent intent(UUID orderIntentId) {
        return orderIntentRepository.findById(orderIntentId)
                .orElseThrow(() -> new IllegalArgumentException("order intent not found: " + orderIntentId));
    }

    private SubmissionAttempt attempt(UUID attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("submission attempt not found: " + attemptId));
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }

    @FunctionalInterface
    private interface IntentCommand {
        void apply(OrderIntent intent);
    }

    public record ReconciliationResult(
            boolean openOrdersComplete,
            boolean closedOrdersComplete,
            Instant closedWindowStart,
            Instant closedWindowEnd,
            boolean allPagesRead,
            String resultHash,
            String brokerOrderId,
            String brokerReturnedClientOrderId,
            BrokerOrderStatus brokerStatus,
            BigDecimal cumulativeFilledQuantity,
            Instant checkedAt
    ) {
        public ReconciliationResult {
            if (resultHash == null || resultHash.isBlank()) {
                throw new IllegalArgumentException("resultHash is required");
            }
            if (cumulativeFilledQuantity == null) {
                throw new IllegalArgumentException("cumulativeFilledQuantity is required");
            }
            if (checkedAt == null) {
                throw new IllegalArgumentException("checkedAt is required");
            }
            if (brokerOrderId != null) {
                if (brokerReturnedClientOrderId == null || brokerReturnedClientOrderId.isBlank()) {
                    throw new IllegalArgumentException(
                            "brokerReturnedClientOrderId is required when brokerOrderId is present");
                }
                if (brokerStatus == null) {
                    throw new IllegalArgumentException("brokerStatus is required when brokerOrderId is present");
                }
            }
        }
    }

    public record BrokerConfirmation(
            String brokerOrderId,
            String brokerReturnedClientOrderId,
            BrokerOrderStatus brokerStatus,
            BigDecimal cumulativeFilledQuantity,
            Instant capturedAt
    ) {
        public BrokerConfirmation {
            if (brokerOrderId == null || brokerOrderId.isBlank()) {
                throw new IllegalArgumentException("brokerOrderId is required");
            }
            if (brokerReturnedClientOrderId == null || brokerReturnedClientOrderId.isBlank()) {
                throw new IllegalArgumentException("brokerReturnedClientOrderId is required");
            }
            if (brokerStatus == null) {
                throw new IllegalArgumentException("brokerStatus is required");
            }
            if (cumulativeFilledQuantity == null) {
                throw new IllegalArgumentException("cumulativeFilledQuantity is required");
            }
            if (capturedAt == null) {
                throw new IllegalArgumentException("capturedAt is required");
            }
        }
    }
}
