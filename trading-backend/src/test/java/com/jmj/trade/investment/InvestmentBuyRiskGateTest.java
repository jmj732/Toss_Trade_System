package com.jmj.trade.investment;

import com.jmj.trade.investment.tactical.MarketRiskEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentBuyRiskGateTest {

    private static final Instant NOW = Instant.parse("2026-11-07T15:00:00Z");

    @Test
    void tacticalMarketDeteriorationBlocksBuy() {
        var result = gate("TACTICAL", "OK", "IMPAIRED", "CLEAR", availableFundamentals());

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("TACTICAL_MARKET_RISK_IMPAIRED");
    }

    @Test
    void strategicMarketDeteriorationRequiresReviewWhenFundamentalsAreAvailable() {
        var result = gate("STRATEGIC", "OK", "IMPAIRED", "CLEAR", availableFundamentals());

        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reasons()).contains("STRATEGIC_MARKET_RISK_REVIEW_REQUIRED_IMPAIRED");
    }

    @Test
    void strategicDataGapBlocksInsteadOfCallingThesisHealthy() {
        var result = gate("STRATEGIC", "OK", "INTACT", "CLEAR", unavailableFundamentals());

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("FUNDAMENTALS_STALE");
        assertThat(result.fundamentalVerification().status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void strategicTriggerBreachIsReviewSignalNotAutomaticExitOrBuyBlock() {
        var result = gate("STRATEGIC", "OK", "INTACT", "TRIGGER_BREACHED", availableFundamentals());

        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reasons()).contains("APPROVED_PRICE_TRIGGER_REVIEW_REQUIRED");
    }

    @Test
    void staleMarketDataBlocksEveryKnownHorizon() {
        var result = gate("STRATEGIC", "STALE", "NOT_EVALUABLE", "CLEAR", availableFundamentals());

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("MARKET_DATA_STALE");
    }

    @Test
    void unknownHorizonRequiresReviewWithoutAssumingTacticalOrStrategic() {
        var result = gate("UNKNOWN", "OK", "INTACT", "CLEAR", availableFundamentals());

        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reasons()).containsExactly("INVESTMENT_HORIZON_UNKNOWN");
    }

    @Test
    void unknownHorizonWithUnconfiguredMarketDataRequiresReviewAndShowsGap() {
        var result = gate("UNKNOWN", "NOT_CONFIGURED", "NOT_EVALUABLE", "CLEAR", availableFundamentals());

        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reasons()).contains("INVESTMENT_HORIZON_UNKNOWN", "MARKET_DATA_NOT_CONFIGURED");
    }

    @Test
    void unknownHorizonWithStaleMarketDataIsBlocked() {
        var result = gate("UNKNOWN", "STALE", "NOT_EVALUABLE", "CLEAR", availableFundamentals());

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("INVESTMENT_HORIZON_UNKNOWN", "MARKET_DATA_STALE");
    }

    private static InvestmentBuyRiskGate.Decision gate(String horizon, String dataStatus, String health,
                                                        String priceRiskState,
                                                        InvestmentBuyRiskGate.FundamentalVerification fundamentals) {
        var market = new MarketRiskEvaluator.Assessment(MarketRiskEvaluator.POLICY_VERSION, dataStatus, health,
                "NOT_EVALUABLE", "TOSS_OPENAPI_HAS_NO_FUNDAMENTAL_HEALTH_DATA", priceRiskState,
                "NO_PRICE_ACTION", horizon, null, null, null, NOW,
                java.time.LocalDate.parse("2026-11-06"), "TOSS", null, null, null, null, null,
                null, null, "NOT_EVALUABLE", null, null);
        return InvestmentBuyRiskGate.evaluate(market, fundamentals, NOW);
    }

    private static InvestmentBuyRiskGate.FundamentalVerification availableFundamentals() {
        return new InvestmentBuyRiskGate.FundamentalVerification("AVAILABLE", List.of(), "OK", "SEC", NOW,
                "OK", "CONSENSUS", NOW);
    }

    private static InvestmentBuyRiskGate.FundamentalVerification unavailableFundamentals() {
        return new InvestmentBuyRiskGate.FundamentalVerification("UNAVAILABLE", List.of("FUNDAMENTALS_STALE"),
                "STALE", "SEC", NOW, "OK", "CONSENSUS", NOW);
    }
}
