package com.jmj.trade.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIntentTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Instant TERMINAL_AT = Instant.parse("2026-07-27T01:00:00Z");

    @Test
    void commandMethodsFollowSubmissionPath() {
        var intent = proposed("10");

        intent.approve();
        intent.startRevalidation();
        intent.markSubmissionPending();
        intent.activate();

        assertThat(intent.getStatus()).isEqualTo(OrderIntentStatus.ACTIVE);
    }

    @Test
    void commandMethodsRejectInvalidTransitions() {
        var intent = proposed("10");

        assertThatThrownBy(intent::startRevalidation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROPOSED -> REVALIDATING");
    }

    @Test
    void terminalStateRequiresTerminalDataAndCalculatesRemainingQuantity() {
        var intent = active("10");

        intent.terminate(
                OrderIntentStatus.PARTIALLY_COMPLETED,
                "잔여 주문 취소 확인",
                TERMINAL_AT,
                new BigDecimal("4.5"));

        assertThat(intent.getStatus()).isEqualTo(OrderIntentStatus.PARTIALLY_COMPLETED);
        assertThat(intent.getTerminalReason()).isEqualTo("잔여 주문 취소 확인");
        assertThat(intent.getTerminalAt()).isEqualTo(TERMINAL_AT);
        assertThat(intent.getFinalFilledQuantity()).isEqualByComparingTo("4.5");
        assertThat(intent.getRemainingQuantity()).isEqualByComparingTo("5.5");
    }

    @Test
    void terminalStateIsImmutable() {
        var intent = active("10");
        intent.terminate(OrderIntentStatus.CANCELED, "사용자 취소", TERMINAL_AT, BigDecimal.ZERO);

        assertThatThrownBy(intent::activate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void terminalQuantityRulesDependOnStatus() {
        assertThatThrownBy(() -> active("10").terminate(
                OrderIntentStatus.COMPLETED, "완료", TERMINAL_AT, new BigDecimal("9.9")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMPLETED");

        assertThatThrownBy(() -> active("10").terminate(
                OrderIntentStatus.PARTIALLY_COMPLETED, "부분 종료", TERMINAL_AT, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PARTIALLY_COMPLETED");

        assertThatThrownBy(() -> active("10").terminate(
                OrderIntentStatus.CANCELED, "취소", TERMINAL_AT, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANCELED");
    }

    @Test
    void preAcceptanceTerminalStatesAreRejectedAfterActive() {
        var intent = active("10");

        assertThatThrownBy(() -> intent.terminate(
                OrderIntentStatus.REJECTED, "거절", TERMINAL_AT, BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE -> REJECTED");
    }

    @Test
    void terminalDataIsRequired() {
        var intent = proposed("10");

        assertThatThrownBy(() -> intent.terminate(
                OrderIntentStatus.EXPIRED, "", TERMINAL_AT, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminalReason");

        assertThatThrownBy(() -> intent.terminate(
                OrderIntentStatus.EXPIRED, "만료", null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminalAt");

        assertThatThrownBy(() -> intent.terminate(
                OrderIntentStatus.EXPIRED, "만료", TERMINAL_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalFilledQuantity");
    }

    private static OrderIntent active(String quantity) {
        var intent = proposed(quantity);
        intent.approve();
        intent.startRevalidation();
        intent.markSubmissionPending();
        intent.activate();
        return intent;
    }

    private static OrderIntent proposed(String quantity) {
        return OrderIntent.proposed(UUID.randomUUID(), ACCOUNT_ID, new BigDecimal(quantity));
    }
}
