package com.jmj.trade.order;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KillSwitchRevalidationCheckTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void engagedStateBlocksSubmission() {
        var check = new KillSwitchRevalidationCheck((userId, accountId) -> true);
        assertThat(check.evaluate(context()))
                .contains(PreTradeRiskEngine.Reason.KILL_SWITCH_ENGAGED);
    }

    @Test
    void disengagedStatePasses() {
        var check = new KillSwitchRevalidationCheck((userId, accountId) -> false);
        assertThat(check.evaluate(context())).isEmpty();
    }

    @Test
    void stateReadFailureBlocksFailClosed() {
        // 상태를 읽지 못하면 통과가 아니라 차단이어야 한다(fail-closed).
        var check = new KillSwitchRevalidationCheck((userId, accountId) -> {
            throw new IllegalStateException("kill switch state unavailable");
        });
        assertThat(check.evaluate(context()))
                .contains(PreTradeRiskEngine.Reason.KILL_SWITCH_STATE_UNAVAILABLE);
    }

    @Test
    void probeReceivesSubmitterUserAndAccount() {
        var seen = new UUID[2];
        var check = new KillSwitchRevalidationCheck((userId, accountId) -> {
            seen[0] = userId;
            seen[1] = accountId;
            return false;
        });
        check.evaluate(context());
        assertThat(seen[0]).isEqualTo(USER);
        assertThat(seen[1]).isEqualTo(CONNECTION);
    }

    private PreSubmitContext context() {
        return new PreSubmitContext(
                USER, CONNECTION, USER, CONNECTION,
                OrderSide.BUY, "AAPL", BigDecimal.ONE, Currency.USD,
                new BigDecimal("100"), portfolio(), false);
    }

    private PortfolioReadService.PortfolioView portfolio() {
        return new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), T0, false, null, false, List.of(), List.of(),
                null, List.of(), Map.of());
    }
}
