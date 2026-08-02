package com.jmj.trade.broker;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 주문 정정 요청(SPEC:558 {@code POST /api/v1/orders/{orderId}/modify}).
 *
 * <p><b>미국 주식은 가격 변경만 지원한다.</b> 수량 변경({@link #changesQuantity()})은 계약 위반이며
 * {@link BrokerOrderPort#modifyOrder} 의 공유 정책에서 <b>어느 어댑터에서도 동일하게</b> 거부된다.
 */
public record BrokerOrderModification(
        String brokerOrderId,
        BigDecimal newLimitPrice,
        BigDecimal newQuantity,
        BrokerOrderType orderType) {

    public BrokerOrderModification(String brokerOrderId, BigDecimal newLimitPrice, BigDecimal newQuantity) {
        this(brokerOrderId, newLimitPrice, newQuantity, null);
    }

    public BrokerOrderModification {
        brokerOrderId = BrokerPreconditions.nonBlank(brokerOrderId, "brokerOrderId");
        if (newLimitPrice != null && newLimitPrice.signum() <= 0) {
            throw new IllegalArgumentException("newLimitPrice must be positive");
        }
        if (newQuantity != null && newQuantity.signum() <= 0) {
            throw new IllegalArgumentException("newQuantity must be positive");
        }
        if (newLimitPrice == null && newQuantity == null) {
            throw new IllegalArgumentException("modification must change price or quantity");
        }
    }

    /** 가격만 정정. */
    public static BrokerOrderModification reprice(String brokerOrderId, BigDecimal newLimitPrice) {
        return new BrokerOrderModification(brokerOrderId, Objects.requireNonNull(newLimitPrice, "newLimitPrice"), null,
                BrokerOrderType.LIMIT);
    }

    /** 수량 정정 시도(계약 위반; 거부됨을 테스트로 고정하기 위한 명시적 생성자). */
    public static BrokerOrderModification changeQuantity(String brokerOrderId, BigDecimal newQuantity) {
        return new BrokerOrderModification(brokerOrderId, null, Objects.requireNonNull(newQuantity, "newQuantity"), null);
    }

    public boolean changesQuantity() {
        return newQuantity != null;
    }
}
