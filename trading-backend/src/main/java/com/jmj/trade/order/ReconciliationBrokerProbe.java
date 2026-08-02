package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerOrderGroup;

/**
 * UNKNOWN 조정 시 브로커 한 그룹(OPEN 또는 CLOSED)을 조회해 타입 결과를 만드는 심(seam)
 * (플랜 원장 E5). 조회 실패·미확정은 반드시 {@link ReconciliationGroupOutcome.Unavailable} 로
 * 표현하고 예외로 흘리거나 빈 결과로 삼키지 않는다 — "찾을 수 없었음" 을 "찾지 못함" 으로
 * 오판하지 않기 위해서다(SPEC:1055).
 */
public interface ReconciliationBrokerProbe {

    ReconciliationGroupOutcome probe(BrokerAccountRef account, BrokerOrderGroup group, String clientOrderId);
}
