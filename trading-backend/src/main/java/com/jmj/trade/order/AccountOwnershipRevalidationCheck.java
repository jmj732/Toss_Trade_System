package com.jmj.trade.order;

import java.util.Optional;

/**
 * 재검증 항목 1: 승인 사용자와 계좌 소유권(SPEC:877).
 *
 * <p>제출 직전에 intent 가 담고 있는 소유자·연결이 제출 요청의 소유자·연결과 여전히 일치하는지
 * 다시 확인한다(defense-in-depth). 불일치하면 브로커로 보내지 않고 차단한다.
 */
public final class AccountOwnershipRevalidationCheck implements PreSubmitRevalidationCheck {

    @Override
    public Optional<PreTradeRiskEngine.Reason> evaluate(PreSubmitContext context) {
        if (!context.intentUserId().equals(context.userId())
                || !context.intentConnectionId().equals(context.connectionId())) {
            return Optional.of(PreTradeRiskEngine.Reason.ACCOUNT_OWNERSHIP_MISMATCH);
        }
        return Optional.empty();
    }
}
