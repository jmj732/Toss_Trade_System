package com.jmj.trade.broker;

import java.util.Objects;

/**
 * 주문 전송(생성/취소/정정) 요청에 대한 브로커 응답.
 *
 * <p>{@link BrokerOrderDispatchStatus} 로 성공/실패/미확정/미지원을 <b>타입으로</b> 구분하며
 * 예외로 흘리지 않는다. {@link BrokerOrderDispatchStatus#UNKNOWN} 은 {@code REJECTED} 와 다른
 * 상태로, 실패로 뭉개지 않고 조정 대상으로 남긴다(SPEC:1055).
 */
public record BrokerOrderAck(
        BrokerOrderDispatchStatus status,
        String brokerOrderId,
        String idempotencyKey,
        BrokerErrorCategory reason) {

    public BrokerOrderAck {
        Objects.requireNonNull(status, "status");
        brokerOrderId = BrokerPreconditions.nullableNonBlank(brokerOrderId, "brokerOrderId");
        idempotencyKey = BrokerPreconditions.nullableNonBlank(idempotencyKey, "idempotencyKey");
        if (status == BrokerOrderDispatchStatus.ACCEPTED && brokerOrderId == null) {
            throw new IllegalArgumentException("ACCEPTED ack requires a broker order id");
        }
        if (status == BrokerOrderDispatchStatus.REJECTED && reason == null) {
            throw new IllegalArgumentException("REJECTED ack requires a reason category");
        }
    }

    public static BrokerOrderAck accepted(String brokerOrderId, String idempotencyKey) {
        return new BrokerOrderAck(BrokerOrderDispatchStatus.ACCEPTED, brokerOrderId, idempotencyKey, null);
    }

    public static BrokerOrderAck rejected(BrokerErrorCategory reason, String brokerOrderId, String idempotencyKey) {
        return new BrokerOrderAck(
                BrokerOrderDispatchStatus.REJECTED,
                brokerOrderId,
                idempotencyKey,
                Objects.requireNonNull(reason, "reason"));
    }

    public static BrokerOrderAck unknown(String brokerOrderId, String idempotencyKey) {
        return new BrokerOrderAck(BrokerOrderDispatchStatus.UNKNOWN, brokerOrderId, idempotencyKey, null);
    }

    public static BrokerOrderAck unsupported(String brokerOrderId, String idempotencyKey) {
        return new BrokerOrderAck(BrokerOrderDispatchStatus.UNSUPPORTED, brokerOrderId, idempotencyKey, null);
    }

    public boolean isAccepted() {
        return status == BrokerOrderDispatchStatus.ACCEPTED;
    }

    public boolean isRejected() {
        return status == BrokerOrderDispatchStatus.REJECTED;
    }

    public boolean isUnknown() {
        return status == BrokerOrderDispatchStatus.UNKNOWN;
    }

    public boolean isUnsupported() {
        return status == BrokerOrderDispatchStatus.UNSUPPORTED;
    }
}
