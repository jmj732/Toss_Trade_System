package com.jmj.trade.order;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationCheckTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-07-27T01:00:20Z");

    @Test
    void retrySameKeyAllowedRequiresCompleteNoMatchSnapshot() {
        var check = recordRetryAllowed(true, true, true, null);

        assertThat(check.getDecision()).isEqualTo(ReconciliationDecision.RETRY_SAME_KEY_ALLOWED);
        assertThat(check.getMatchedBrokerOrderId()).isNull();
    }

    @Test
    void retrySameKeyAllowedRejectsIncompleteOrMatchedSnapshot() {
        assertThatThrownBy(() -> recordRetryAllowed(false, true, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete no-match");
        assertThatThrownBy(() -> recordRetryAllowed(true, false, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete no-match");
        assertThatThrownBy(() -> recordRetryAllowed(true, true, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete no-match");
        assertThatThrownBy(() -> recordRetryAllowed(true, true, true, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete no-match");
    }

    private static ReconciliationCheck recordRetryAllowed(
            boolean openOrdersComplete,
            boolean closedOrdersComplete,
            boolean allPagesRead,
            UUID matchedBrokerOrderId
    ) {
        return ReconciliationCheck.record(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                openOrdersComplete,
                closedOrdersComplete,
                CHECKED_AT.minusSeconds(60),
                CHECKED_AT,
                allPagesRead,
                "result-hash-1",
                matchedBrokerOrderId,
                ReconciliationDecision.RETRY_SAME_KEY_ALLOWED,
                CHECKED_AT);
    }
}
