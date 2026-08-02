package com.jmj.trade.order;

import java.util.Optional;

/**
 * 재검증 항목 10: 전체 신규 주문 중지 kill switch (플랜 원장 E4; SPEC:886).
 *
 * <p>제출 직전(FINAL) 관문에서 매 제출마다 원장의 최신 상태를 다시 읽는다 — 승인 시점 판정을
 * 재사용하지 않는다. GLOBAL/USER/ACCOUNT 중 하나라도 engaged 이면 차단 사유를 반환해 브로커
 * 호출을 막는다.
 *
 * <p>fail-closed: 상태를 읽지 못하면(조회 예외) 통과가 아니라 차단한다. kill switch 는 안전 장치이므로
 * "확인할 수 없으면 보내지 않는다".
 */
public final class KillSwitchRevalidationCheck implements PreSubmitRevalidationCheck {

    private final KillSwitchStateReader ledger;

    public KillSwitchRevalidationCheck(KillSwitchStateReader ledger) {
        this.ledger = ledger;
    }

    @Override
    public Optional<PreTradeRiskEngine.Reason> evaluate(PreSubmitContext context) {
        boolean engaged;
        try {
            engaged = ledger.anyEngaged(context.userId(), context.connectionId());
        } catch (RuntimeException exception) {
            // 상태를 읽을 수 없다 → 차단(fail-closed). 조회 실패를 통과로 처리하지 않는다.
            return Optional.of(PreTradeRiskEngine.Reason.KILL_SWITCH_STATE_UNAVAILABLE);
        }
        return engaged
                ? Optional.of(PreTradeRiskEngine.Reason.KILL_SWITCH_ENGAGED)
                : Optional.empty();
    }
}
