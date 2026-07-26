package com.jmj.trade.order;

public enum ReconciliationDecision {
    BROKER_ORDER_FOUND,
    RETRY_SAME_KEY_ALLOWED,
    MANUAL_REVIEW_REQUIRED
}
