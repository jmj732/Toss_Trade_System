package com.jmj.trade.order;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 브로커 목록 조회(OPEN 또는 CLOSED 한 그룹)의 결과를 타입으로 구분한다 (플랜 원장 E5).
 *
 * <p>급소는 "찾지 못함" 과 "찾을 수 없었음" 의 혼동이다(SPEC:1055). 조회가 성공했고 우리 주문이
 * 없으면 {@link Absent}(확실히 없음)이고, 조회 자체가 실패·부분·미확정이면 {@link Unavailable}
 * (판정 불가)이다. {@link Unavailable} 은 절대 미접수로 해석되지 않는다 — 이 그룹이 {@code Absent}
 * 로 확정되지 않는 한 {@link ReconciliationEvidence} 는 재시도 판정을 만들 수 없다.
 *
 * <p>{@link Unavailable#reason()} 에는 브로커 응답의 원문 식별자·자격증명을 담지 않는다(SPEC:1151).
 * 그룹 이름 등 비식별 사유만 남긴다.
 */
public sealed interface ReconciliationGroupOutcome
        permits ReconciliationGroupOutcome.Matched,
        ReconciliationGroupOutcome.Absent,
        ReconciliationGroupOutcome.Unavailable {

    /** 조회가 확정적으로 끝났는가(찾음 또는 확실히 없음). 조회 실패/미확정이면 {@code false}. */
    boolean resolved();

    /** 우리 주문을 찾음. 재전송하지 않고 브로커 주문으로 연결한다. */
    record Matched(
            String brokerOrderId,
            String brokerReturnedClientOrderId,
            BrokerOrderStatus status,
            BigDecimal filledQuantity
    ) implements ReconciliationGroupOutcome {

        public Matched {
            if (brokerOrderId == null || brokerOrderId.isBlank()) {
                throw new IllegalArgumentException("brokerOrderId is required");
            }
            if (brokerReturnedClientOrderId == null || brokerReturnedClientOrderId.isBlank()) {
                throw new IllegalArgumentException("brokerReturnedClientOrderId is required");
            }
            Objects.requireNonNull(status, "status");
            if (filledQuantity == null || filledQuantity.signum() < 0) {
                throw new IllegalArgumentException("filledQuantity must not be negative");
            }
        }

        @Override
        public boolean resolved() {
            return true;
        }
    }

    /** 조회 성공 + 우리 주문 없음(확실히 없음). */
    record Absent() implements ReconciliationGroupOutcome {
        @Override
        public boolean resolved() {
            return true;
        }
    }

    /** 조회 실패·부분·미확정. 판정 불가 — 재시도가 아니라 수동 검토로 간다. */
    record Unavailable(String reason) implements ReconciliationGroupOutcome {

        public Unavailable {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason is required");
            }
        }

        @Override
        public boolean resolved() {
            return false;
        }
    }
}
