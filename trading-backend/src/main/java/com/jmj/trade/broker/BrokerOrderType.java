package com.jmj.trade.broker;

/**
 * 브로커 주문 유형(SPEC:557 LIMIT/MARKET). {@code order} 모듈의 {@code OrderType} 와 값은 같지만
 * broker 포트가 order 모듈에 의존하지 않도록 broker 모듈에 독립적으로 둔 계약용 열거형이다.
 */
public enum BrokerOrderType {
    MARKET,
    LIMIT
}
