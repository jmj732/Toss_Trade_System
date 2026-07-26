package com.jmj.trade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@DynamicUpdate
@Table(name = "submission_attempts")
public class SubmissionAttempt {

    private static final Pattern CLIENT_ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,36}");
    private static final long IDEMPOTENCY_WINDOW_SECONDS = 600;

    @Id
    private UUID id;

    @Column(name = "order_intent_id", nullable = false)
    private UUID orderIntentId;

    @Column(name = "broker_account_id", nullable = false)
    private UUID brokerAccountId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "internal_idempotency_key", nullable = false, length = 128)
    private String internalIdempotencyKey;

    @Column(name = "client_order_id", nullable = false, length = 36)
    private String clientOrderId;

    @Column(name = "request_body_hash", nullable = false, length = 128)
    private String requestBodyHash;

    @Column(name = "retry_of_attempt_id")
    private UUID retryOfAttemptId;

    @Column(name = "confirmed_broker_order_id")
    private UUID confirmedBrokerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SubmissionAttemptStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dispatch_evidence", columnDefinition = "jsonb")
    private DispatchEvidence dispatchEvidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "idempotency_expires_at", nullable = false)
    private Instant idempotencyExpiresAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "last_reconciliation_check_number", nullable = false)
    private int lastReconciliationCheckNumber;

    @Version
    @Column(nullable = false)
    private long version;

    protected SubmissionAttempt() {
    }

    private SubmissionAttempt(
            UUID id,
            UUID orderIntentId,
            UUID brokerAccountId,
            int attemptNumber,
            String internalIdempotencyKey,
            String clientOrderId,
            String requestBodyHash,
            UUID retryOfAttemptId,
            Instant createdAt,
            Instant idempotencyExpiresAt
    ) {
        requireId(id, "id");
        requireId(orderIntentId, "orderIntentId");
        requireId(brokerAccountId, "brokerAccountId");
        requireText(internalIdempotencyKey, "internalIdempotencyKey");
        requireClientOrderId(clientOrderId);
        requireText(requestBodyHash, "requestBodyHash");
        requireInstant(createdAt, "createdAt");
        requireInstant(idempotencyExpiresAt, "idempotencyExpiresAt");
        if (!createdAt.isBefore(idempotencyExpiresAt)) {
            throw new IllegalArgumentException("createdAt must be before idempotencyExpiresAt");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }

        this.id = id;
        this.orderIntentId = orderIntentId;
        this.brokerAccountId = brokerAccountId;
        this.attemptNumber = attemptNumber;
        this.internalIdempotencyKey = internalIdempotencyKey;
        this.clientOrderId = clientOrderId;
        this.requestBodyHash = requestBodyHash;
        this.retryOfAttemptId = retryOfAttemptId;
        this.createdAt = createdAt;
        this.idempotencyExpiresAt = idempotencyExpiresAt;
        this.status = SubmissionAttemptStatus.CREATED;
    }

    public static SubmissionAttempt initial(
            UUID id,
            UUID orderIntentId,
            UUID brokerAccountId,
            String clientOrderId,
            String requestBodyHash,
            String internalIdempotencyKey,
            Instant createdAt
    ) {
        return new SubmissionAttempt(
                id,
                orderIntentId,
                brokerAccountId,
                1,
                internalIdempotencyKey,
                clientOrderId,
                requestBodyHash,
                null,
                createdAt,
                requireInstant(createdAt, "createdAt").plusSeconds(IDEMPOTENCY_WINDOW_SECONDS));
    }

    public static SubmissionAttempt retry(
            UUID id,
            SubmissionAttempt parent,
            String internalIdempotencyKey,
            Instant createdAt,
            ReconciliationDecision latestDecision
    ) {
        if (parent == null) {
            throw new IllegalArgumentException("parent is required");
        }
        requireInstant(createdAt, "createdAt");
        if (!createdAt.isBefore(parent.idempotencyExpiresAt)) {
            throw new IllegalStateException("retry is outside idempotency window");
        }
        if (parent.finishedAt == null || createdAt.isBefore(parent.finishedAt)) {
            throw new IllegalStateException("retry createdAt must be at or after parent finishedAt");
        }
        if (latestDecision != ReconciliationDecision.RETRY_SAME_KEY_ALLOWED) {
            throw new IllegalStateException("retry requires latest reconciliation decision RETRY_SAME_KEY_ALLOWED");
        }
        if (parent.status != SubmissionAttemptStatus.RECONCILED_NO_MATCH) {
            throw new IllegalStateException("retry requires retry-allowed reconciliation");
        }

        return new SubmissionAttempt(
                id,
                parent.orderIntentId,
                parent.brokerAccountId,
                parent.attemptNumber + 1,
                internalIdempotencyKey,
                parent.clientOrderId,
                parent.requestBodyHash,
                parent.id,
                createdAt,
                parent.idempotencyExpiresAt);
    }

    public void startDispatch(Instant startedAt, DispatchEvidence evidence) {
        requireTransition(SubmissionAttemptStatus.CREATED, SubmissionAttemptStatus.DISPATCHING);
        requireNotBefore(requireInstant(startedAt, "startedAt"), createdAt, "startedAt");
        this.startedAt = startedAt;
        this.dispatchEvidence = evidence;
        this.status = SubmissionAttemptStatus.DISPATCHING;
    }

    public void markUnknown(Instant finishedAt, DispatchEvidence evidence) {
        finishDispatch(SubmissionAttemptStatus.UNKNOWN, finishedAt, evidence, null);
    }

    public void acknowledge(Instant finishedAt, UUID confirmedBrokerOrderId, DispatchEvidence evidence) {
        requireId(confirmedBrokerOrderId, "confirmedBrokerOrderId");
        if (status != SubmissionAttemptStatus.DISPATCHING && status != SubmissionAttemptStatus.RECONCILING) {
            throw new IllegalStateException("invalid submission attempt transition: " + status
                    + " -> " + SubmissionAttemptStatus.ACKNOWLEDGED);
        }
        requireNotTerminal();
        requireAllocatedCheckWhenReconciling();
        requireInstant(finishedAt, "finishedAt");
        requireFinishedAtInOrder(finishedAt);
        this.confirmedBrokerOrderId = confirmedBrokerOrderId;
        this.finishedAt = finishedAt;
        this.dispatchEvidence = evidence;
        this.status = SubmissionAttemptStatus.ACKNOWLEDGED;
    }

    public void reject(Instant finishedAt, DispatchEvidence evidence) {
        finishDispatch(SubmissionAttemptStatus.BROKER_REJECTED, finishedAt, evidence, null);
    }

    public void startReconciliation() {
        requireTransition(SubmissionAttemptStatus.UNKNOWN, SubmissionAttemptStatus.RECONCILING);
        this.status = SubmissionAttemptStatus.RECONCILING;
    }

    public int allocateNextReconciliationCheckNumber() {
        requireNotTerminal();
        if (status != SubmissionAttemptStatus.RECONCILING) {
            throw new IllegalStateException("reconciliation check requires RECONCILING attempt");
        }
        lastReconciliationCheckNumber++;
        return lastReconciliationCheckNumber;
    }

    public void markNoMatch(Instant finishedAt) {
        finishReconciliation(SubmissionAttemptStatus.RECONCILED_NO_MATCH, finishedAt);
    }

    public void markReconciliationFailed(Instant finishedAt) {
        finishReconciliation(SubmissionAttemptStatus.RECONCILIATION_FAILED, finishedAt);
    }

    private void finishDispatch(
            SubmissionAttemptStatus nextStatus,
            Instant finishedAt,
            DispatchEvidence evidence,
            UUID confirmedBrokerOrderId
    ) {
        requireTransition(SubmissionAttemptStatus.DISPATCHING, nextStatus);
        requireInstant(finishedAt, "finishedAt");
        requireFinishedAtInOrder(finishedAt);
        if (nextStatus == SubmissionAttemptStatus.ACKNOWLEDGED) {
            requireId(confirmedBrokerOrderId, "confirmedBrokerOrderId");
        }
        this.confirmedBrokerOrderId = confirmedBrokerOrderId;
        this.finishedAt = finishedAt;
        this.dispatchEvidence = evidence;
        this.status = nextStatus;
    }

    private void finishReconciliation(SubmissionAttemptStatus nextStatus, Instant finishedAt) {
        requireTransition(SubmissionAttemptStatus.RECONCILING, nextStatus);
        requireAllocatedReconciliationCheck();
        requireInstant(finishedAt, "finishedAt");
        requireFinishedAtInOrder(finishedAt);
        this.finishedAt = finishedAt;
        this.status = nextStatus;
    }

    private void requireFinishedAtInOrder(Instant nextFinishedAt) {
        if (startedAt == null) {
            throw new IllegalStateException("startedAt is required before finish");
        }
        requireNotBefore(nextFinishedAt, startedAt, "finishedAt");
        if (finishedAt != null) {
            if (status == SubmissionAttemptStatus.RECONCILING && !nextFinishedAt.isAfter(finishedAt)) {
                throw new IllegalArgumentException("finishedAt is out of order");
            }
            requireNotBefore(nextFinishedAt, finishedAt, "finishedAt");
        }
    }

    private void requireTransition(SubmissionAttemptStatus expected, SubmissionAttemptStatus next) {
        requireNotTerminal();
        if (status != expected) {
            throw new IllegalStateException("invalid submission attempt transition: " + status + " -> " + next);
        }
    }

    private void requireNotTerminal() {
        if (isTerminal(status)) {
            throw new IllegalStateException("terminal submission attempt is immutable");
        }
    }

    private void requireAllocatedCheckWhenReconciling() {
        if (status == SubmissionAttemptStatus.RECONCILING) {
            requireAllocatedReconciliationCheck();
        }
    }

    private void requireAllocatedReconciliationCheck() {
        if (lastReconciliationCheckNumber < 1) {
            throw new IllegalStateException("reconciliation check is required before completion");
        }
    }

    private static boolean isTerminal(SubmissionAttemptStatus status) {
        return status == SubmissionAttemptStatus.ACKNOWLEDGED
                || status == SubmissionAttemptStatus.BROKER_REJECTED
                || status == SubmissionAttemptStatus.RECONCILED_NO_MATCH
                || status == SubmissionAttemptStatus.RECONCILIATION_FAILED;
    }

    private static UUID requireId(UUID value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static Instant requireInstant(Instant value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requireClientOrderId(String value) {
        if (value == null || !CLIENT_ORDER_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("clientOrderId must match [A-Za-z0-9_-]{1,36}");
        }
    }

    private static void requireNotBefore(Instant value, Instant floor, String fieldName) {
        if (value.isBefore(floor)) {
            throw new IllegalArgumentException(fieldName + " is out of order");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderIntentId() {
        return orderIntentId;
    }

    public UUID getBrokerAccountId() {
        return brokerAccountId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getInternalIdempotencyKey() {
        return internalIdempotencyKey;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getRequestBodyHash() {
        return requestBodyHash;
    }

    public UUID getRetryOfAttemptId() {
        return retryOfAttemptId;
    }

    public UUID getConfirmedBrokerOrderId() {
        return confirmedBrokerOrderId;
    }

    public SubmissionAttemptStatus getStatus() {
        return status;
    }

    public DispatchEvidence getDispatchEvidence() {
        return dispatchEvidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getIdempotencyExpiresAt() {
        return idempotencyExpiresAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getLastReconciliationCheckNumber() {
        return lastReconciliationCheckNumber;
    }

    public long getVersion() {
        return version;
    }
}
