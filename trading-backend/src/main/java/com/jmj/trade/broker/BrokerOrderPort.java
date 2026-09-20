package com.jmj.trade.broker;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 브로커 주문 전송 포트(플랜 원장 B4). SPEC:555-559 의 토스 주문 API 형태를 내부 계약으로
 * 정규화한 것으로, 읽기 전용 {@link BrokerAdapter} 와 짝을 이루는 쓰기 측 포트다.
 *
 * <p>읽기 포트({@link BrokerAdapter})와 물리적으로 분리된 이유:
 * <ul>
 *   <li>읽기 어댑터(시세·계좌)와 주문 실행 어댑터는 서로 다른 역할이라, 소비자마다 하나의
 *       {@code BrokerAdapter} 빈을 주입받는 기존 배선과 충돌 없이 끼울 자리를 만든다.</li>
 *   <li>{@code BrokerContractTest.brokerAdapterExposesOnlyReadOnlyMethods} 가 의도적으로
 *       고정한 "{@code BrokerAdapter} 는 읽기 전용" 계약과 기존 테스트 더블들을 보존한다.</li>
 * </ul>
 *
 * <p>모든 결과는 {@link BrokerResponse} 로 감싸고, 성공/실패/미확정/미지원을 타입으로 구분하며
 * 예외로 흘리지 않는다. 특히 전송 미확정({@link BrokerOrderDispatchStatus#UNKNOWN})은 실패와
 * 구분되어야 하며(SPEC:1055), 미구현 어댑터는 {@link BrokerOrderDispatchStatus#UNSUPPORTED}
 * 를 반환할 뿐 조용히 성공하지 않는다.
 *
 * <p>계좌 범위 외부 주문 ID 중복은 {@code broker_orders} 의
 * {@code UNIQUE(broker_account_id, broker_order_id)}(SPEC:525) 가 막는다. 포트는 이 제약과
 * 모순되는 계약을 두지 않는다.
 */
public interface BrokerOrderPort {

    /**
     * 주문 생성(SPEC:557; LIMIT/MARKET). 멱등 키를 필수 인자로 받는다. 같은 멱등 키로 재호출해도
     * 브로커 주문이 중복 생성되지 않아야 한다.
     */
    BrokerResponse<BrokerOrderAck> placeOrder(BrokerAccountRef account, BrokerOrderRequest request, String idempotencyKey);

    /** 주문 취소(SPEC:559). 브로커 주문 ID 기준. */
    BrokerResponse<BrokerOrderAck> cancelOrder(BrokerAccountRef account, String brokerOrderId);

    /** 주문 단건 조회(SPEC:556). 브로커 주문 ID 기준. */
    BrokerResponse<BrokerOrderView> getOrder(BrokerAccountRef account, String brokerOrderId);

    /** 주문 목록 조회(SPEC:555). {@link BrokerOrderGroup#OPEN}/{@link BrokerOrderGroup#CLOSED} 그룹. */
    BrokerResponse<List<BrokerOrderView>> getOrders(BrokerAccountRef account, BrokerOrderGroup group);

    /**
     * 주문 정정(SPEC:558). <b>미국 주식은 가격 변경만 지원</b>한다.
     *
     * <p>수량 정정 시도는 어댑터별 정책 차이가 생기지 않도록 이 default 메서드(공유 코드)에서
     * 계약 위반으로 일괄 거부한다. 가격 정정은 아직 어떤 어댑터도 실제로 구현하지 않았으므로
     * {@code UNSUPPORTED} 를 반환한다(E6 에서 실거래 어댑터가 override).
     */
    default BrokerResponse<BrokerOrderAck> modifyOrder(BrokerAccountRef account, BrokerOrderModification modification) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(modification, "modification");
        if (modification.changesQuantity()) {
            return ack(BrokerOrderAck.rejected(BrokerErrorCategory.CONTRACT, modification.brokerOrderId(), null));
        }
        return ack(BrokerOrderAck.unsupported(modification.brokerOrderId(), null));
    }

    /** 브로커 왕복 없이 만든 타입 응답을 담는 로컬 메타데이터. */
    static BrokerCallMetadata localMetadata() {
        return new BrokerCallMetadata(null, Instant.now(), Optional.empty());
    }

    /** 로컬 메타데이터로 감싼 주문 전송 응답. */
    static BrokerResponse<BrokerOrderAck> ack(BrokerOrderAck ack) {
        return new BrokerResponse<>(ack, localMetadata());
    }

    /** 미지원 전송을 나타내는 타입 응답(조용히 성공하지 않음). */
    static BrokerResponse<BrokerOrderAck> unsupported(String brokerOrderId, String idempotencyKey) {
        return ack(BrokerOrderAck.unsupported(brokerOrderId, idempotencyKey));
    }
}
