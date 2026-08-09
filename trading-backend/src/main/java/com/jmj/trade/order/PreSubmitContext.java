package com.jmj.trade.order;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.Currency;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 주문 제출 직전(REVALIDATING) 재검증 컨텍스트(플랜 원장 B3; SPEC:873-890).
 *
 * <p>승인 시점 스냅샷이 아니라, 제출 직전에 다시 읽은 최신 지속 상태({@code portfolio})와 주문
 * 원장 상태({@code sameSymbolOpenOrderExists})를 담는다. 각 {@link PreSubmitRevalidationCheck}
 * 가 이 컨텍스트만 보고 판정하므로, 미구현 항목(2=E1, 4=B1, 10=E4, 11=F1, 12=C6)은 필요한
 * 필드를 추가하고 체크 하나를 목록에 더 끼우는 것으로 확장한다 — 구조를 다시 뒤집지 않는다.
 */
record PreSubmitContext(
        UUID userId,
        UUID connectionId,
        UUID intentUserId,
        UUID intentConnectionId,
        OrderSide side,
        String symbol,
        BigDecimal quantity,
        Currency tradingCurrency,
        BigDecimal orderAmount,
        PortfolioReadService.PortfolioView portfolio,
        boolean sameSymbolOpenOrderExists
) {
}
