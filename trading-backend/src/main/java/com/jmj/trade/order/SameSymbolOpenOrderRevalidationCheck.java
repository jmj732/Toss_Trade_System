package com.jmj.trade.order;

import java.util.Optional;

/**
 * 재검증 항목 6: 동일 종목 OPEN 주문(SPEC:882).
 *
 * <p>같은 계좌·종목에 아직 열려 있는(제출됐거나 활성인) 주문이 있으면, 중복·상충 주문을 막기 위해
 * 제출 직전에 차단한다. OPEN 여부 판정은 {@link PreTradeRiskEngine} 가 주문 원장을 다시 읽어
 * 컨텍스트에 채운다.
 */
final class SameSymbolOpenOrderRevalidationCheck implements PreSubmitRevalidationCheck {

    @Override
    public Optional<PreTradeRiskEngine.Reason> evaluate(PreSubmitContext context) {
        if (context.sameSymbolOpenOrderExists()) {
            return Optional.of(PreTradeRiskEngine.Reason.OPEN_ORDER_EXISTS);
        }
        return Optional.empty();
    }
}
