package com.jmj.trade.broker;

/**
 * 주문 전송(생성/취소/정정) 요청의 결과 유형.
 *
 * <p>핵심은 {@link #UNKNOWN} 을 {@link #REJECTED} 와 <b>구분</b>하는 것이다. 전송 결과가
 * 확인되지 않은 상태를 실패로 뭉개면 SPEC:1055 의 조정(reconciliation) 절차가 성립하지 않는다
 * (UNKNOWN attempt 를 미접수로 오판하면 안 됨).
 */
public enum BrokerOrderDispatchStatus {

    /** 브로커가 접수를 확인함. 브로커 주문 ID 가 반드시 존재한다. */
    ACCEPTED,

    /** 브로커/계약이 명시적으로 거부함. 주문은 접수되지 않았다(확정적 실패). */
    REJECTED,

    /** 전송 결과 미확정. 실패가 아니며 조정으로 확인해야 한다(SPEC:1055). */
    UNKNOWN,

    /** 이 어댑터가 아직 해당 연산을 구현하지 않음(예: Toss 전송은 E6 에서 구현). */
    UNSUPPORTED
}
