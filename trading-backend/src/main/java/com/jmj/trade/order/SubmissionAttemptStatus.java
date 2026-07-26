package com.jmj.trade.order;

public enum SubmissionAttemptStatus {
    CREATED,
    DISPATCHING,
    ACKNOWLEDGED,
    BROKER_REJECTED,
    UNKNOWN,
    RECONCILING,
    RECONCILED_NO_MATCH,
    RECONCILIATION_FAILED
}
