package com.jmj.trade.order;

import java.util.Optional;

/**
 * 주문 제출 직전 재검증 관문 하나(플랜 원장 B3; SPEC:875-889).
 *
 * <p>{@link PreTradeRiskEngine} 의 FINAL 단계가 등록된 체크를 순서대로 실행하고, 하나라도
 * 차단 사유를 반환하면 브로커를 호출하지 않고 intent 를 {@code BLOCKED} 로 전환한다. 이 인터페이스가
 * "확장 가능한 하나의 자리" 다 — 새 재검증 항목은 여기 구현체를 하나 추가해 배선 목록에 끼운다.
 *
 * <p>fail-closed: 확인할 수 없으면(예: 매도 가능 수량 UNKNOWN) 통과가 아니라 차단 사유를 반환한다.
 */
public interface PreSubmitRevalidationCheck {

    /** 통과면 {@link Optional#empty()}, 차단이면 구체적 사유를 담아 반환한다. */
    Optional<PreTradeRiskEngine.Reason> evaluate(PreSubmitContext context);
}
