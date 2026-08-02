package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 주문 생성 요청(SPEC:557 {@code POST /api/v1/orders}; LIMIT/MARKET).
 *
 * <p>멱등 키는 요청 본문이 아니라 {@link BrokerOrderPort#placeOrder} 의 필수 인자로 받는다.
 */
public record BrokerOrderRequest(
        BrokerOrderSide side,
        BrokerOrderType type,
        String symbol,
        BigDecimal quantity,
        BigDecimal limitPrice,
        Currency currency) {

    public BrokerOrderRequest {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        symbol = BrokerPreconditions.nonBlank(symbol, "symbol");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        Objects.requireNonNull(currency, "currency");
        if (type == BrokerOrderType.LIMIT) {
            Objects.requireNonNull(limitPrice, "limitPrice is required for LIMIT orders");
            if (limitPrice.signum() <= 0) {
                throw new IllegalArgumentException("limitPrice must be positive");
            }
        } else if (limitPrice != null) {
            throw new IllegalArgumentException("MARKET orders must not carry a limit price");
        }
    }
}
