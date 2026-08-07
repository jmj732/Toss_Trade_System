package com.jmj.trade.order;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum OrderIntentStatus {
    PROPOSED,
    APPROVED,
    REVALIDATING,
    SUBMISSION_PENDING,
    RECONCILIATION_REQUIRED,
    MANUAL_REVIEW_REQUIRED,
    ACTIVE,
    COMPLETED,
    PARTIALLY_COMPLETED,
    CANCELED,
    REJECTED,
    EXPIRED,
    BLOCKED;

    /**
     * 대시보드 제안 목록에서 "닫힌" 것으로 취급하는 종결 상태(D-03). 이 넷을 제외한 나머지가
     * 기본(OPEN) 필터다. 도메인 {@code isTerminal}(6개)과 달리, 대시보드는 PARTIALLY_COMPLETED /
     * BLOCKED 를 여전히 후속 조치가 필요한 열린 항목으로 노출한다.
     */
    private static final Set<OrderIntentStatus> DASHBOARD_CLOSED =
            Collections.unmodifiableSet(EnumSet.of(COMPLETED, CANCELED, REJECTED, EXPIRED));

    /**
     * 대시보드 기본 필터 집합: 종결 4상태를 제외한 9개(단일 출처). 호출부는 이 집합을 복제하지 않는다.
     */
    private static final Set<OrderIntentStatus> DASHBOARD_OPEN =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(DASHBOARD_CLOSED)));

    public static Set<OrderIntentStatus> dashboardOpenStatuses() {
        return DASHBOARD_OPEN;
    }
}
