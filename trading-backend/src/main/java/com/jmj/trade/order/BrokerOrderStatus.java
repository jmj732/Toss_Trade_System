package com.jmj.trade.order;

public enum BrokerOrderStatus {
    PENDING,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED,
    CANCEL_REJECTED,
    REPLACE_REJECTED,
    REPLACED,
    CANCELING,
    REPLACING
}
