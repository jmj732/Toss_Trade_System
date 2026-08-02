package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 종목별 매도 가능 수량 스냅샷(플랜 원장 B3; SPEC:881,1077-1078).
 *
 * <p>매도 주문은 제출 직전에 이 값을 다시 읽어 판정한다. 브로커가 매도 가능 수량을 제공하지 않으면
 * {@link Availability#UNKNOWN} 을 유지하며, 이는 0 과 구분된다(SPEC:1078). {@code UNKNOWN} 은
 * "확인 불가" 이므로 재검증에서 통과가 아니라 차단 사유로 쓰인다. 반면 {@code KNOWN} 의 0 은
 * "매도 가능 수량이 실제로 0" 이라는 확정 값이다.
 */
public record SellableQuantitySnapshot(
        BrokerAccountRef account,
        String symbol,
        Availability availability,
        BigDecimal quantity,
        Instant observedAt) {

    public enum Availability {
        KNOWN,
        UNKNOWN
    }

    public SellableQuantitySnapshot {
        Objects.requireNonNull(account, "account");
        symbol = BrokerPreconditions.nonBlank(symbol, "symbol");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(observedAt, "observedAt");
        if (availability == Availability.KNOWN) {
            quantity = BrokerPreconditions.nonNegative(quantity, "quantity");
        } else if (quantity != null) {
            throw new IllegalArgumentException("UNKNOWN sellable quantity must not carry a value");
        }
    }

    public static SellableQuantitySnapshot known(
            BrokerAccountRef account,
            String symbol,
            BigDecimal quantity,
            Instant observedAt
    ) {
        return new SellableQuantitySnapshot(account, symbol, Availability.KNOWN, quantity, observedAt);
    }

    public static SellableQuantitySnapshot unknown(
            BrokerAccountRef account,
            String symbol,
            Instant observedAt
    ) {
        return new SellableQuantitySnapshot(account, symbol, Availability.UNKNOWN, null, observedAt);
    }
}
