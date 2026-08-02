package com.jmj.trade.broker;

/**
 * 브로커 주문 방향. {@code order} 모듈의 {@code OrderSide} 와 값은 같지만, broker 포트가 order
 * 모듈에 의존하지 않도록(모듈 경계) broker 모듈에 독립적으로 둔 계약용 열거형이다.
 */
public enum BrokerOrderSide {
    BUY,
    SELL
}
