package com.jmj.trade.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReconciliationEvidence} 단위 테스트 (플랜 원장 E5).
 *
 * <p>핵심은 급소(SPEC:1055)를 타입/구조로 막았음을 보이는 것이다: CLOSED 결과 없이는 증거를 만들
 * 수 없고, {@code closedOrdersComplete} 는 CLOSED 그룹이 확정 조회된 경우에만 참이 된다. 따라서
 * OPEN 만으로는 재시도 전제({@code openOrdersComplete && closedOrdersComplete && allPagesRead})를
 * 만족시킬 수 없다.
 */
class ReconciliationEvidenceTest {

    private static final String CLIENT = "client-1";
    private static final Instant CHECKED_AT = Instant.parse("2026-07-27T01:10:00Z");

    @Test
    void bothAbsentYieldsCompleteNoMatchThatAllowsRetry() {
        var result = ReconciliationEvidence.of(
                        new ReconciliationGroupOutcome.Absent(),
                        new ReconciliationGroupOutcome.Absent())
                .toResult(CLIENT, CHECKED_AT);

        assertThat(result.openOrdersComplete()).isTrue();
        assertThat(result.closedOrdersComplete()).isTrue();
        assertThat(result.allPagesRead()).isTrue();
        assertThat(result.brokerOrderId()).isNull();
    }

    @Test
    void closedUnavailableCannotFakeClosedCompleteSoRetryIsUnreachable() {
        var result = ReconciliationEvidence.of(
                        new ReconciliationGroupOutcome.Absent(),
                        new ReconciliationGroupOutcome.Unavailable("CLOSED query unavailable"))
                .toResult(CLIENT, CHECKED_AT);

        // CLOSED 를 확정 조회하지 못하면 closedOrdersComplete 는 결코 참이 될 수 없다 → 도메인이 수동 검토.
        assertThat(result.openOrdersComplete()).isTrue();
        assertThat(result.closedOrdersComplete()).isFalse();
        assertThat(result.allPagesRead()).isFalse();
        assertThat(result.brokerOrderId()).isNull();
    }

    @Test
    void openUnavailableAlsoBlocksCompleteNoMatch() {
        var result = ReconciliationEvidence.of(
                        new ReconciliationGroupOutcome.Unavailable("OPEN query unavailable"),
                        new ReconciliationGroupOutcome.Absent())
                .toResult(CLIENT, CHECKED_AT);

        assertThat(result.openOrdersComplete()).isFalse();
        assertThat(result.allPagesRead()).isFalse();
    }

    @Test
    void matchInClosedGroupLinksBrokerOrderInsteadOfResending() {
        var result = ReconciliationEvidence.of(
                        new ReconciliationGroupOutcome.Absent(),
                        new ReconciliationGroupOutcome.Matched(
                                "broker-1", CLIENT, BrokerOrderStatus.FILLED, new BigDecimal("10")))
                .toResult(CLIENT, CHECKED_AT);

        assertThat(result.brokerOrderId()).isEqualTo("broker-1");
        assertThat(result.brokerReturnedClientOrderId()).isEqualTo(CLIENT);
        assertThat(result.brokerStatus()).isEqualTo(BrokerOrderStatus.FILLED);
        assertThat(result.cumulativeFilledQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void matchInOpenGroupLinksBrokerOrder() {
        var result = ReconciliationEvidence.of(
                        new ReconciliationGroupOutcome.Matched(
                                "broker-2", CLIENT, BrokerOrderStatus.PENDING, BigDecimal.ZERO),
                        new ReconciliationGroupOutcome.Absent())
                .toResult(CLIENT, CHECKED_AT);

        assertThat(result.brokerOrderId()).isEqualTo("broker-2");
        assertThat(result.brokerStatus()).isEqualTo(BrokerOrderStatus.PENDING);
    }

    @Test
    void bothResolvedReflectsResolvedStateOfEachGroup() {
        assertThat(ReconciliationEvidence.of(
                new ReconciliationGroupOutcome.Absent(),
                new ReconciliationGroupOutcome.Absent()).bothResolved()).isTrue();
        assertThat(ReconciliationEvidence.of(
                new ReconciliationGroupOutcome.Absent(),
                new ReconciliationGroupOutcome.Unavailable("x")).bothResolved()).isFalse();
    }
}
