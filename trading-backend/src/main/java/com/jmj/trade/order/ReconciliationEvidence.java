package com.jmj.trade.order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * OPEN 과 CLOSED 두 그룹의 조회 결과를 합쳐 조정 판정 근거로 만드는 값 객체 (플랜 원장 E5).
 *
 * <p>이 타입이 급소(SPEC:1055)를 구조로 막는다. {@link #of(ReconciliationGroupOutcome,
 * ReconciliationGroupOutcome)} 는 <b>OPEN 과 CLOSED 두 결과를 모두</b> 인자로 요구하므로, CLOSED
 * 조회 없이는 애초에 증거를 만들 수 없다. 그리고 {@link #toResult} 가 재시도 판정의 전제
 * ({@code closedOrdersComplete})를 CLOSED 그룹이 {@code resolved} 인 경우에만 참으로 둔다. 따라서
 * "OPEN 만 조회한 상태" 로는 {@link ReconciliationDecision#RETRY_SAME_KEY_ALLOWED} 에 도달할 수
 * 없다 — CLOSED 를 확정적으로 조회해 없음을 확인해야만 재시도가 가능하다.
 *
 * <p>한쪽 그룹에서라도 주문을 찾으면(OPEN 이든 CLOSED 든) 브로커 주문 연결로 가고 재전송하지
 * 않는다. 어느 그룹이든 {@link ReconciliationGroupOutcome.Unavailable} 이면 재시도가 아니라
 * 수동 검토로 귀결된다(도메인 {@code OrderSubmissionService.recordReconciliation} 이 불완전 조회를
 * {@code MANUAL_REVIEW_REQUIRED} 로 처리).
 */
public final class ReconciliationEvidence {

    private final ReconciliationGroupOutcome open;
    private final ReconciliationGroupOutcome closed;

    private ReconciliationEvidence(ReconciliationGroupOutcome open, ReconciliationGroupOutcome closed) {
        this.open = Objects.requireNonNull(open, "open");
        this.closed = Objects.requireNonNull(closed, "closed");
    }

    /**
     * OPEN 결과와 CLOSED 결과를 모두 받아 증거를 만든다. CLOSED 인자가 필수이므로 OPEN 만으로는
     * 재시도 판정 근거를 구성할 수 없다.
     */
    public static ReconciliationEvidence of(ReconciliationGroupOutcome open, ReconciliationGroupOutcome closed) {
        return new ReconciliationEvidence(open, closed);
    }

    public ReconciliationGroupOutcome open() {
        return open;
    }

    public ReconciliationGroupOutcome closed() {
        return closed;
    }

    /** 두 그룹 모두 확정적으로 조회됐는가(찾음/확실히 없음). 하나라도 미확정이면 {@code false}. */
    public boolean bothResolved() {
        return open.resolved() && closed.resolved();
    }

    private ReconciliationGroupOutcome.Matched match() {
        if (open instanceof ReconciliationGroupOutcome.Matched matched) {
            return matched;
        }
        if (closed instanceof ReconciliationGroupOutcome.Matched matched) {
            return matched;
        }
        return null;
    }

    /**
     * 도메인 {@code OrderSubmissionService.recordReconciliation} 에 넘길 판정 입력을 만든다.
     *
     * <ul>
     *   <li>한 그룹에서라도 찾음 → {@code brokerOrderId} 세팅 → 브로커 주문 연결(재전송 없음).</li>
     *   <li>둘 다 없음(확실히) → {@code openOrdersComplete=closedOrdersComplete=allPagesRead=true}
     *       → 도메인이 멱등 창 내이면 재시도 허용, 아니면 수동 검토.</li>
     *   <li>한쪽이라도 미확정 → 해당 {@code *Complete=false} → 도메인이 수동 검토로 강제.</li>
     * </ul>
     */
    public OrderSubmissionService.ReconciliationResult toResult(String clientOrderId, Instant checkedAt) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId is required");
        }
        Objects.requireNonNull(checkedAt, "checkedAt");
        var resultHash = hash(clientOrderId + "|" + open.getClass().getSimpleName()
                + "|" + closed.getClass().getSimpleName() + "|" + checkedAt);
        var matched = match();
        if (matched != null) {
            return new OrderSubmissionService.ReconciliationResult(
                    open.resolved(),
                    closed.resolved(),
                    closed.resolved() ? checkedAt.minusSeconds(1) : null,
                    closed.resolved() ? checkedAt : null,
                    bothResolved(),
                    resultHash,
                    matched.brokerOrderId(),
                    matched.brokerReturnedClientOrderId(),
                    matched.status(),
                    matched.filledQuantity(),
                    checkedAt);
        }
        return new OrderSubmissionService.ReconciliationResult(
                open.resolved(),
                closed.resolved(),
                closed.resolved() ? checkedAt.minusSeconds(1) : null,
                closed.resolved() ? checkedAt : null,
                bothResolved(),
                resultHash,
                null,
                null,
                null,
                BigDecimal.ZERO,
                checkedAt);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
