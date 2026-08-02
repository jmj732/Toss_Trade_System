package com.jmj.trade.broker;

/**
 * 주문 목록 조회 그룹(SPEC:555 {@code OPEN}/{@code CLOSED}). 분류 기준은 어댑터가 아니라
 * {@link BrokerOrderLifecycle#group()} 한 곳에서 정의돼 모든 구현이 동일하게 분류한다.
 */
public enum BrokerOrderGroup {
    OPEN,
    CLOSED
}
