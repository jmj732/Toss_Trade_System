package com.jmj.trade.order;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 재검증 항목 5(매도측): 종목별 매도 가능 수량(SPEC:881,1077-1078).
 *
 * <p>매도 주문은 제출 직전에 다시 읽은 매도 가능 수량으로 판정한다. 매도 가능 수량이 UNKNOWN
 * (보유 포지션이 없거나 브로커가 값을 제공하지 않음)이면 0 으로 간주하지 않고 차단한다 — 확인할
 * 수 없으면 보내지 않는다. 확정된 매도 가능 수량이 주문 수량보다 적으면 차단한다.
 */
public final class SellableQuantityRevalidationCheck implements PreSubmitRevalidationCheck {

    @Override
    public Optional<PreTradeRiskEngine.Reason> evaluate(PreSubmitContext context) {
        if (context.side() != OrderSide.SELL) {
            return Optional.empty();
        }
        var currency = context.tradingCurrency().name();
        var known = BigDecimal.ZERO;
        var sawPosition = false;
        var anyUnknown = false;
        for (var position : context.portfolio().positions()) {
            if (!position.symbol().equals(context.symbol()) || !position.currency().equals(currency)) {
                continue;
            }
            sawPosition = true;
            if (position.sellableQuantity() == null) {
                anyUnknown = true;
            } else {
                known = known.add(position.sellableQuantity());
            }
        }
        if (!sawPosition || anyUnknown) {
            return Optional.of(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_UNKNOWN);
        }
        if (known.compareTo(context.quantity()) < 0) {
            return Optional.of(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_INSUFFICIENT);
        }
        return Optional.empty();
    }
}
