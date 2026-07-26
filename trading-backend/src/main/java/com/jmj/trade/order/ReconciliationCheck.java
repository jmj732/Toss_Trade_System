package com.jmj.trade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_checks")
public class ReconciliationCheck {

    @Id
    private UUID id;

    @Column(name = "submission_attempt_id", nullable = false)
    private UUID submissionAttemptId;

    @Column(name = "order_intent_id", nullable = false)
    private UUID orderIntentId;

    @Column(name = "check_number", nullable = false)
    private int checkNumber;

    @Column(name = "open_orders_complete", nullable = false)
    private boolean openOrdersComplete;

    @Column(name = "closed_orders_complete", nullable = false)
    private boolean closedOrdersComplete;

    @Column(name = "closed_window_start")
    private Instant closedWindowStart;

    @Column(name = "closed_window_end")
    private Instant closedWindowEnd;

    @Column(name = "all_pages_read", nullable = false)
    private boolean allPagesRead;

    @Column(name = "result_hash", nullable = false, length = 128)
    private String resultHash;

    @Column(name = "matched_broker_order_id")
    private UUID matchedBrokerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ReconciliationDecision decision;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected ReconciliationCheck() {
    }

    private ReconciliationCheck(
            UUID id,
            UUID submissionAttemptId,
            UUID orderIntentId,
            int checkNumber,
            boolean openOrdersComplete,
            boolean closedOrdersComplete,
            Instant closedWindowStart,
            Instant closedWindowEnd,
            boolean allPagesRead,
            String resultHash,
            UUID matchedBrokerOrderId,
            ReconciliationDecision decision,
            Instant checkedAt
    ) {
        if (checkNumber < 1) {
            throw new IllegalArgumentException("checkNumber must be positive");
        }
        if (decision == ReconciliationDecision.BROKER_ORDER_FOUND && matchedBrokerOrderId == null) {
            throw new IllegalArgumentException("matchedBrokerOrderId is required when broker order is found");
        }
        if (decision == ReconciliationDecision.RETRY_SAME_KEY_ALLOWED
                && (!openOrdersComplete
                || !closedOrdersComplete
                || !allPagesRead
                || matchedBrokerOrderId != null)) {
            throw new IllegalArgumentException(
                    "RETRY_SAME_KEY_ALLOWED requires complete no-match reconciliation");
        }
        this.id = requireId(id, "id");
        this.submissionAttemptId = requireId(submissionAttemptId, "submissionAttemptId");
        this.orderIntentId = requireId(orderIntentId, "orderIntentId");
        this.checkNumber = checkNumber;
        this.openOrdersComplete = openOrdersComplete;
        this.closedOrdersComplete = closedOrdersComplete;
        this.closedWindowStart = closedWindowStart;
        this.closedWindowEnd = closedWindowEnd;
        this.allPagesRead = allPagesRead;
        this.resultHash = requireText(resultHash, "resultHash");
        this.matchedBrokerOrderId = matchedBrokerOrderId;
        this.decision = requireDecision(decision);
        this.checkedAt = requireInstant(checkedAt, "checkedAt");
    }

    public static ReconciliationCheck record(
            UUID id,
            UUID submissionAttemptId,
            UUID orderIntentId,
            int checkNumber,
            boolean openOrdersComplete,
            boolean closedOrdersComplete,
            Instant closedWindowStart,
            Instant closedWindowEnd,
            boolean allPagesRead,
            String resultHash,
            UUID matchedBrokerOrderId,
            ReconciliationDecision decision,
            Instant checkedAt
    ) {
        return new ReconciliationCheck(
                id,
                submissionAttemptId,
                orderIntentId,
                checkNumber,
                openOrdersComplete,
                closedOrdersComplete,
                closedWindowStart,
                closedWindowEnd,
                allPagesRead,
                resultHash,
                matchedBrokerOrderId,
                decision,
                checkedAt);
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

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static ReconciliationDecision requireDecision(ReconciliationDecision value) {
        if (value == null) {
            throw new IllegalArgumentException("decision is required");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubmissionAttemptId() {
        return submissionAttemptId;
    }

    public UUID getOrderIntentId() {
        return orderIntentId;
    }

    public int getCheckNumber() {
        return checkNumber;
    }

    public boolean isOpenOrdersComplete() {
        return openOrdersComplete;
    }

    public boolean isClosedOrdersComplete() {
        return closedOrdersComplete;
    }

    public Instant getClosedWindowStart() {
        return closedWindowStart;
    }

    public Instant getClosedWindowEnd() {
        return closedWindowEnd;
    }

    public boolean isAllPagesRead() {
        return allPagesRead;
    }

    public String getResultHash() {
        return resultHash;
    }

    public UUID getMatchedBrokerOrderId() {
        return matchedBrokerOrderId;
    }

    public ReconciliationDecision getDecision() {
        return decision;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
