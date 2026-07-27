package com.jmj.trade.broker;

public enum BrokerErrorCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMITED,
    STALE_DATA,
    NOT_FOUND,
    VALIDATION,
    BROKER_UNAVAILABLE,
    NETWORK,
    CONTRACT,
    UNKNOWN
}
