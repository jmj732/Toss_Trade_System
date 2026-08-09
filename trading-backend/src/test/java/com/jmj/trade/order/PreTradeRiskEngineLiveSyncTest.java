package com.jmj.trade.order;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreTradeRiskEngineLiveSyncTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final UUID INTENT = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    @Test
    void liveApprovalSynchronizesBeforeConnectionLockAndReadsTheLatestSnapshotAfterLock() {
        var freshReads = mock(FreshPortfolioReadService.class);
        var snapshotReads = mock(PortfolioReadService.class);
        var jdbc = mock(JdbcTemplate.class);
        var intents = mock(OrderIntentRepository.class);
        var transitions = mock(OrderIntentTransitionService.class);
        var policies = mock(RiskPolicyService.class);
        var intent = OrderIntent.proposedLive(
                INTENT, ACCOUNT, USER, CONNECTION, OrderSide.SELL, OrderType.LIMIT,
                "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD);
        var portfolio = new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), Instant.parse("2026-08-09T00:00:00Z"),
                false, null, false, List.of(), List.of(), null, List.of(), Map.of());
        when(freshReads.read(USER, CONNECTION)).thenReturn(portfolio);
        when(snapshotReads.read(USER, CONNECTION)).thenReturn(portfolio);
        when(intents.findOwnedByIdForUpdate(INTENT, USER, CONNECTION))
                .thenReturn(Optional.of(intent));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of(CONNECTION));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any(), any()))
                .thenReturn(Boolean.TRUE);
        when(policies.current(USER)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                1, new BigDecimal("10000000"), new BigDecimal("10000"),
                BigDecimal.TEN, BigDecimal.ONE, true));

        var engine = new PreTradeRiskEngine(
                snapshotReads,
                freshReads,
                mock(PaperTradingBroker.class),
                intents,
                transitions,
                jdbc,
                policies,
                List.<PreSubmitRevalidationCheck>of());

        var decision = engine.approveLive(new PreTradeRiskEngine.ApprovalCommand(
                USER, CONNECTION, INTENT, new BigDecimal("180"), Instant.now(), "test"));

        assertThat(decision.approved()).isTrue();
        var order = inOrder(freshReads, jdbc, snapshotReads);
        order.verify(freshReads).read(USER, CONNECTION);
        order.verify(jdbc).queryForList(anyString(), eq(UUID.class), eq(CONNECTION), eq(USER));
        order.verify(snapshotReads).read(USER, CONNECTION);
        verify(transitions).approve(INTENT, "test");
    }

    @Test
    void liveSubmissionUsesLatestSyncedSellableQuantityForFinalRevalidation() {
        var freshReads = mock(FreshPortfolioReadService.class);
        var snapshotReads = mock(PortfolioReadService.class);
        var jdbc = mock(JdbcTemplate.class);
        var intents = mock(OrderIntentRepository.class);
        var transitions = mock(OrderIntentTransitionService.class);
        var policies = mock(RiskPolicyService.class);
        var intent = OrderIntent.proposedLive(
                INTENT, ACCOUNT, USER, CONNECTION, OrderSide.SELL, OrderType.LIMIT,
                "AAPL", new BigDecimal("2"), new BigDecimal("180"), Currency.USD);
        intent.approve();
        var portfolio = new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), Instant.parse("2026-08-09T00:00:00Z"),
                false, null, false, List.of(), List.of(), null,
                List.of(new PortfolioReadService.PositionView(
                        "AAPL", "Apple", "US", new BigDecimal("2"), "USD",
                        BigDecimal.ONE, new BigDecimal("180"), BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("1"),
                        Instant.parse("2026-08-09T00:00:00Z"))), Map.of());
        when(freshReads.read(USER, CONNECTION)).thenReturn(portfolio);
        when(snapshotReads.read(USER, CONNECTION)).thenReturn(portfolio);
        when(intents.findOwnedByIdForUpdate(INTENT, USER, CONNECTION))
                .thenReturn(Optional.of(intent));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of(CONNECTION));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any()))
                .thenReturn(Boolean.TRUE);
        when(policies.current(USER)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                1, new BigDecimal("10000000"), new BigDecimal("10000"),
                BigDecimal.TEN, BigDecimal.ONE, true));

        var engine = new PreTradeRiskEngine(
                snapshotReads,
                freshReads,
                mock(PaperTradingBroker.class),
                intents,
                transitions,
                jdbc,
                policies,
                List.of(new SellableQuantityRevalidationCheck()));

        var submission = engine.submitLive(
                USER, CONNECTION, INTENT, new BigDecimal("180"), Instant.now(), "test");

        assertThat(submission.decision().approved()).isFalse();
        assertThat(submission.decision().reasons())
                .containsExactly(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_INSUFFICIENT);
        verify(transitions).terminate(
                eq(INTENT), eq(OrderIntentStatus.BLOCKED),
                eq(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_INSUFFICIENT.name()),
                any(), eq(BigDecimal.ZERO), eq("test"));
        verify(freshReads).read(USER, CONNECTION);
        verify(snapshotReads).read(USER, CONNECTION);
    }
}
