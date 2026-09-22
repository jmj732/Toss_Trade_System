package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 브로커 주문 단건/목록 조회 결과(SPEC:555-556). {@link #group()} 는 어댑터 공통의
 * {@link BrokerOrderLifecycle#group()} 분류를 그대로 노출한다.
 */
public record BrokerOrderView(
        String brokerOrderId,
        String idempotencyKey,
        BrokerOrderSide side,
        BrokerOrderType type,
        String symbol,
        BigDecimal quantity,
        BigDecimal filledQuantity,
        BigDecimal limitPrice,
        Currency currency,
        BrokerOrderLifecycle status,
        Instant filledAt,
        BigDecimal averageFilledPrice,
        BigDecimal commission,
        BigDecimal tax,
        Instant orderedAt) {

    public BrokerOrderView(
            String brokerOrderId,
            String idempotencyKey,
            BrokerOrderSide side,
            BrokerOrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal filledQuantity,
            BigDecimal limitPrice,
            Currency currency,
            BrokerOrderLifecycle status,
            Instant filledAt,
            BigDecimal averageFilledPrice,
            BigDecimal commission,
            BigDecimal tax
    ) {
        this(brokerOrderId, idempotencyKey, side, type, symbol, quantity, filledQuantity,
                limitPrice, currency, status, filledAt, averageFilledPrice, commission, tax, null);
    }

    public BrokerOrderView(
            String brokerOrderId,
            String idempotencyKey,
            BrokerOrderSide side,
            BrokerOrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal filledQuantity,
            BigDecimal limitPrice,
            Currency currency,
            BrokerOrderLifecycle status
    ) {
        this(brokerOrderId, idempotencyKey, side, type, symbol, quantity, filledQuantity,
                limitPrice, currency, status, null, null, null, null, null);
    }

    public BrokerOrderView {
        brokerOrderId = BrokerPreconditions.nonBlank(brokerOrderId, "brokerOrderId");
        idempotencyKey = BrokerPreconditions.nullableNonBlank(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        symbol = BrokerPreconditions.nonBlank(symbol, "symbol");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(filledQuantity, "filledQuantity");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        if (filledQuantity.signum() < 0 || (averageFilledPrice != null && averageFilledPrice.signum() < 0)
                || (commission != null && commission.signum() < 0) || (tax != null && tax.signum() < 0)) {
            throw new IllegalArgumentException("order execution values must be non-negative");
        }
    }

    public BrokerOrderGroup group() {
        return status.group();
    }
}
