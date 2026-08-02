package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderView;

import java.util.List;
import java.util.Objects;

/**
 * {@link BrokerOrderPort#getOrders} 로 한 그룹을 조회해 타입 결과로 매핑하는 프로덕션 프로브
 * (플랜 원장 E5).
 *
 * <ul>
 *   <li>조회가 예외를 던지면 {@link ReconciliationGroupOutcome.Unavailable} — 절대 미접수(빈 결과)로
 *       삼키지 않는다.</li>
 *   <li>조회 성공 + 우리 {@code clientOrderId} 와 일치하는 주문이 있으면
 *       {@link ReconciliationGroupOutcome.Matched} — 재전송하지 않고 연결한다.</li>
 *   <li>조회 성공 + 일치 없음이면 {@link ReconciliationGroupOutcome.Absent}(확실히 없음).</li>
 * </ul>
 *
 * <p>실패 사유에는 그룹 이름 같은 비식별 값만 담고 브로커 원문 식별자·자격증명은 담지 않는다
 * (SPEC:1151).
 */
public final class BrokerOrderPortReconciliationProbe implements ReconciliationBrokerProbe {

    private final BrokerOrderPort brokerOrderPort;

    public BrokerOrderPortReconciliationProbe(BrokerOrderPort brokerOrderPort) {
        this.brokerOrderPort = Objects.requireNonNull(brokerOrderPort, "brokerOrderPort");
    }

    @Override
    public ReconciliationGroupOutcome probe(BrokerAccountRef account, BrokerOrderGroup group, String clientOrderId) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(group, "group");
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId is required");
        }
        List<BrokerOrderView> views;
        try {
            views = brokerOrderPort.getOrders(account, group).value();
        } catch (RuntimeException exception) {
            // 조회 실패·미확정 → 판정 불가. 빈 결과로 삼켜 미접수로 오판하지 않는다(SPEC:1055).
            return new ReconciliationGroupOutcome.Unavailable(group.name() + " query unavailable");
        }
        if (views.stream().anyMatch(view -> view.idempotencyKey() == null)) {
            // Toss OpenAPI 1.2.5 order list/detail responses do not echo clientOrderId. A complete
            // list without that field cannot prove absence, so never turn it into retry permission.
            return new ReconciliationGroupOutcome.Unavailable(group.name() + " query lacks client order ids");
        }
        for (var view : views) {
            if (clientOrderId.equals(view.idempotencyKey())) {
                return new ReconciliationGroupOutcome.Matched(
                        view.brokerOrderId(),
                        clientOrderId,
                        BrokerOrderStatus.valueOf(view.status().name()),
                        view.filledQuantity());
            }
        }
        return new ReconciliationGroupOutcome.Absent();
    }
}
