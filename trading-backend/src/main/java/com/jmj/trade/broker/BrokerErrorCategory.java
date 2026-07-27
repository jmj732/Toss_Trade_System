package com.jmj.trade.broker;

public enum BrokerErrorCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    INVALID_REQUEST,
    RATE_LIMITED,
    STALE_DATA,
    NOT_FOUND,
    VALIDATION,
    BROKER_UNAVAILABLE,
    NETWORK,
    TEMPORARY,
    CONTRACT,
    UNKNOWN
}
