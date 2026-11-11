package com.jmj.trade.investment;

import com.jmj.trade.investment.tactical.MarketRiskEvaluator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void tossOnlyStrategicWithUnsupportedFundamentalsNeedsExternalEvidenceWhenEverythingElseIsClean() {
        var result = buyGate("STRATEGIC", "OK", "INTACT", "CLEAR", unsupportedFundamentals(),
                InvestmentBuyRiskGate.POLICY_TOSS_ONLY, clearHazards());

        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reasons()).containsExactly("FUNDAMENTALS_EXTERNAL_EVIDENCE_REQUIRED");
        assertThat(result.evidenceMode()).isEqualTo(InvestmentBuyRiskGate.EVIDENCE_EXTERNAL_CITED);
    }

    @Test
    void tossOnlyExternalPathKeepsStrategicMarketReasonsAsReviewSignals() {
        var result = buyGate("STRATEGIC", "OK", "IMPAIRED", "TRIGGER_BREACHED", unsupportedFundamentals(),
                InvestmentBuyRiskGate.POLICY_TOSS_ONLY, clearHazards());

        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reasons()).contains("FUNDAMENTALS_EXTERNAL_EVIDENCE_REQUIRED",
                "APPROVED_PRICE_TRIGGER_REVIEW_REQUIRED", "STRATEGIC_MARKET_RISK_REVIEW_REQUIRED_IMPAIRED");
    }

    @Test
    void unsupportedFundamentalsStayBlockedUnderProvidersPolicy() {
        var result = buyGate("STRATEGIC", "OK", "INTACT", "CLEAR", unsupportedFundamentals(),
                InvestmentBuyRiskGate.POLICY_PROVIDERS, clearHazards());

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).containsExactly(InvestmentBuyRiskGate.TOSS_SOURCE_UNSUPPORTED);
    }

    @Test
    void mixedUnsupportedAndStoredStaleValueStaysBlockedUnderTossOnly() {
        var mixed = new InvestmentBuyRiskGate.FundamentalVerification("UNAVAILABLE",
                List.of(InvestmentBuyRiskGate.TOSS_SOURCE_UNSUPPORTED, "CONSENSUS_STALE"),
                "DATA_MISSING", null, null, "DATA_MISSING", null, NOW.minusSeconds(864000 * 2L));

        var result = buyGate("STRATEGIC", "OK", "INTACT", "CLEAR", mixed,
                InvestmentBuyRiskGate.POLICY_TOSS_ONLY, clearHazards());

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("CONSENSUS_STALE");
    }

    @Test
    void storedFundamentalFailuresBlockStrategicUnderBothPolicies() {
        var failures = List.of("FUNDAMENTALS_STALE", "FUNDAMENTALS_SOURCE_CONFLICT", "FUNDAMENTALS_UNVERIFIED",
                "FUNDAMENTAL_AS_OF_IN_FUTURE", "FUNDAMENTAL_SOURCE_MISSING", "FUNDAMENTAL_AS_OF_MISSING",
                "CONSENSUS_STALE", "REVISION_UNVERIFIED");
        for (var policy : List.of(InvestmentBuyRiskGate.POLICY_PROVIDERS, InvestmentBuyRiskGate.POLICY_TOSS_ONLY)) {
            for (var reason : failures) {
                var verification = new InvestmentBuyRiskGate.FundamentalVerification("UNAVAILABLE", List.of(reason),
                        "STALE", "SEC", NOW, "OK", "CONSENSUS", NOW);

                var result = buyGate("STRATEGIC", "OK", "INTACT", "CLEAR", verification, policy, clearHazards());

                assertThat(result.status()).as(policy + " " + reason).isEqualTo("BLOCKED");
                assertThat(result.reasons()).as(policy + " " + reason).contains(reason);
            }
        }
    }

    @Test
    void tossHazardsBlockBothHorizonsWithTheirReasons() {
        for (var horizon : List.of("TACTICAL", "STRATEGIC")) {
            var hazards = new InvestmentBuyRiskGate.TossStockHazards("BLOCKED",
                    List.of("TOSS_STOCK_WARNING_VI_STATIC"), null);

            var result = buyGate(horizon, "OK", "INTACT", "CLEAR", availableFundamentals(),
                    InvestmentBuyRiskGate.POLICY_TOSS_ONLY, hazards);

            assertThat(result.status()).as(horizon).isEqualTo("BLOCKED");
            assertThat(result.reasons()).as(horizon).containsExactly("TOSS_STOCK_WARNING_VI_STATIC");
        }
    }

    @Test
    void tossHazardFetchFailureBlocksEvenWhenEverythingElseIsClean() {
        for (var horizon : List.of("TACTICAL", "STRATEGIC")) {
            var result = buyGate(horizon, "OK", "INTACT", "CLEAR", availableFundamentals(),
                    InvestmentBuyRiskGate.POLICY_TOSS_ONLY, InvestmentBuyRiskGate.TossStockHazards.unavailable());

            assertThat(result.status()).as(horizon).isEqualTo("BLOCKED");
            assertThat(result.reasons()).as(horizon).contains("TOSS_STOCK_HAZARD_UNAVAILABLE");
        }
    }

    @Test
    void missingHazardInputBlocksTossOnlyBuyAndFailsClosedUnderProviders() {
        var missing = buyGate("STRATEGIC", "OK", "INTACT", "CLEAR", availableFundamentals(),
                InvestmentBuyRiskGate.POLICY_PROVIDERS, null);

        assertThat(missing.status()).isEqualTo("BLOCKED");
        assertThat(missing.reasons()).contains("TOSS_STOCK_HAZARD_UNAVAILABLE");
    }

    @Test
    void quoteSanityFailuresBlockBothHorizons() {
        for (var horizon : List.of("TACTICAL", "STRATEGIC")) {
            var noPrice = buyGate(horizon, "OK", "INTACT", "CLEAR", availableFundamentals(),
                    InvestmentBuyRiskGate.POLICY_PROVIDERS, clearHazards(), null, NOW);
            var futureQuote = buyGate(horizon, "OK", "INTACT", "CLEAR", availableFundamentals(),
                    InvestmentBuyRiskGate.POLICY_PROVIDERS, clearHazards(), BigDecimal.valueOf(100),
                    NOW.plusSeconds(60));
            var staleQuote = buyGate(horizon, "OK", "INTACT", "CLEAR", availableFundamentals(),
                    InvestmentBuyRiskGate.POLICY_PROVIDERS, clearHazards(), BigDecimal.valueOf(100),
                    NOW.minus(Duration.ofHours(1)));

            for (var result : List.of(noPrice, futureQuote, staleQuote)) {
                assertThat(result.status()).as(horizon).isEqualTo("BLOCKED");
                assertThat(result.reasons()).as(horizon).contains("MARKET_DATA_UNVERIFIED");
            }
        }
    }

    @Test
    void cleanProvidersBuyKeepsExistingEligibilityWhenHazardsAreClear() {
        var result = buyGate("STRATEGIC", "OK", "INTACT", "CLEAR", availableFundamentals(),
                InvestmentBuyRiskGate.POLICY_PROVIDERS, clearHazards());

        assertThat(result.status()).isEqualTo("ELIGIBLE");
        assertThat(result.evidenceMode()).isEqualTo(InvestmentBuyRiskGate.POLICY_PROVIDERS);
    }

    @Test
    void sourcePolicyParsingRejectsUnknownValuesAndDefaultsBlank() {
        assertThat(InvestmentBuyRiskGate.parsePolicy(null)).isEqualTo("PROVIDERS");
        assertThat(InvestmentBuyRiskGate.parsePolicy(" ")).isEqualTo("PROVIDERS");
        assertThat(InvestmentBuyRiskGate.parsePolicy("TOSS_ONLY")).isEqualTo("TOSS_ONLY");
        assertThatThrownBy(() -> InvestmentBuyRiskGate.parsePolicy("toss_only"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InvestmentBuyRiskGate.Decision buyGate(String horizon, String dataStatus, String health,
                                                           String priceRiskState,
                                                           InvestmentBuyRiskGate.FundamentalVerification fundamentals,
                                                           String policy,
                                                           InvestmentBuyRiskGate.TossStockHazards hazards) {
        return buyGate(horizon, dataStatus, health, priceRiskState, fundamentals, policy, hazards,
                BigDecimal.valueOf(100), NOW);
    }

    private static InvestmentBuyRiskGate.Decision buyGate(String horizon, String dataStatus, String health,
                                                           String priceRiskState,
                                                           InvestmentBuyRiskGate.FundamentalVerification fundamentals,
                                                           String policy,
                                                           InvestmentBuyRiskGate.TossStockHazards hazards,
                                                           BigDecimal price, Instant priceAsOf) {
        var market = new MarketRiskEvaluator.Assessment(MarketRiskEvaluator.POLICY_VERSION, dataStatus, health,
                "NOT_EVALUABLE", "TOSS_OPENAPI_HAS_NO_FUNDAMENTAL_HEALTH_DATA", priceRiskState,
                "NO_PRICE_ACTION", horizon, null, null, price, priceAsOf,
                java.time.LocalDate.parse("2026-11-06"), "TOSS", null, null, null, null, null,
                null, null, "NOT_EVALUABLE", null, null);
        return InvestmentBuyRiskGate.evaluate(market, fundamentals, NOW,
                new InvestmentBuyRiskGate.BuyEvidence(policy, Duration.ofMinutes(15), hazards));
    }

    private static InvestmentBuyRiskGate.TossStockHazards clearHazards() {
        return new InvestmentBuyRiskGate.TossStockHazards("CLEAR", List.of(), BigDecimal.valueOf(1000));
    }

    private static InvestmentBuyRiskGate.FundamentalVerification unsupportedFundamentals() {
        return new InvestmentBuyRiskGate.FundamentalVerification("UNAVAILABLE",
                List.of(InvestmentBuyRiskGate.TOSS_SOURCE_UNSUPPORTED), "DATA_MISSING", null, null,
                "DATA_MISSING", null, null);
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
