package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TacticalOverlayContextContractTest {

    @Test
    void keepsLegacyContextAndSecurityConstructorsAndUsesExplicitUnconfiguredDefaults() {
        var security = new InvestmentContextService.SecurityView(
                "AAPL", null, null, null, null, null, null, null, null, null, null, null);
        var context = new InvestmentContextService.ContextView(
                null, List.of(security), List.of(), null, List.of(), null);

        assertThat(security.tacticalOverlay()).isNotNull();
        assertThat(security.tacticalOverlay().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(security.tacticalOverlay().overlayVersion()).isEqualTo("TACTICAL_V1");
        assertThat(security.tacticalOverlay().trendStage()).isNull();
        assertThat(context.tacticalOverlay()).isNotNull();
        assertThat(context.tacticalOverlay().status()).isEqualTo("NOT_CONFIGURED");
        assertThat(context.decisionOverlays()).isEmpty();
    }
}
