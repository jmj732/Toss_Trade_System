package com.jmj.trade.broker;

/**
 * 브로커 주문의 수명주기 상태와 그 {@link BrokerOrderGroup} 분류.
 *
 * <p>값 이름은 {@code order} 모듈의 {@code BrokerOrderStatus} 와 1:1로 맞춰(구현이
 * {@code valueOf(name())} 로 무손실 매핑) 두되, broker 포트가 order 모듈에 의존하지 않도록
 * broker 모듈에 독립적으로 둔다. {@code OPEN}/{@code CLOSED} 분류 기준은 여기 한 곳에만
 * 있으므로 어떤 어댑터든 분류 결과가 동일하다.
 */
public enum BrokerOrderLifecycle {
    PENDING(BrokerOrderGroup.OPEN),
    PARTIALLY_FILLED(BrokerOrderGroup.OPEN),
    CANCELING(BrokerOrderGroup.OPEN),
    REPLACING(BrokerOrderGroup.OPEN),
    FILLED(BrokerOrderGroup.CLOSED),
    CANCELED(BrokerOrderGroup.CLOSED),
    REJECTED(BrokerOrderGroup.CLOSED),
    CANCEL_REJECTED(BrokerOrderGroup.CLOSED),
    REPLACE_REJECTED(BrokerOrderGroup.CLOSED),
    REPLACED(BrokerOrderGroup.CLOSED);

    private final BrokerOrderGroup group;

    BrokerOrderLifecycle(BrokerOrderGroup group) {
        this.group = group;
    }

    public BrokerOrderGroup group() {
        return group;
    }
}
