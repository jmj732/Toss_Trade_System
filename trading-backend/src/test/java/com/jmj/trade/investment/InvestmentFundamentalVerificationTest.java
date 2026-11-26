package com.jmj.trade.investment;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Snapshot-to-gate mapping for fundamentals, consensus and revision, including legacy provider separation. */
class InvestmentFundamentalVerificationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final String TOSS_ONLY = InvestmentBuyRiskGate.POLICY_TOSS_ONLY;
    private static final String PROVIDERS = InvestmentBuyRiskGate.POLICY_PROVIDERS;
    private static final String UNSUPPORTED = InvestmentBuyRiskGate.TOSS_SOURCE_UNSUPPORTED;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyStaleConsensusUnderTossOnlyIsExcludedAndNeverCountsAsEvidence() throws Exception {
        var verification = verify(TOSS_ONLY, """
                {"fundamentals":{"status":"DATA_MISSING"},"revision":{"status":"DATA_MISSING"},
                 "consensus":{"status":"STALE","asOf":"2026-09-01T00:00:00Z","source":"ALPHA_VANTAGE"}}
                """);

        assertThat(verification.status()).isEqualTo("UNAVAILABLE");
        assertThat(verification.reasons()).containsExactly(UNSUPPORTED);
        assertThat(verification.legacyProviderData()).singleElement().satisfies(legacy -> {
            assertThat(legacy.group()).isEqualTo("consensus");
            assertThat(legacy.source()).isEqualTo("ALPHA_VANTAGE");
            assertThat(legacy.asOf()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
            assertThat(legacy.flag()).isEqualTo("LEGACY_PROVIDER_EXCLUDED");
        });
    }

    @Test
    void sameLegacyRowUnderProvidersStaysBlockedAndIsNotExcluded() throws Exception {
        var verification = verify(PROVIDERS, """
                {"fundamentals":{"status":"DATA_MISSING"},"revision":{"status":"DATA_MISSING"},
                 "consensus":{"status":"STALE","asOf":"2026-09-01T00:00:00Z","source":"ALPHA_VANTAGE"}}
                """);

        assertThat(verification.status()).isEqualTo("UNAVAILABLE");
        assertThat(verification.reasons()).contains("CONSENSUS_STALE");
        assertThat(verification.reasons()).doesNotContain(UNSUPPORTED);
        assertThat(verification.legacyProviderData()).isEmpty();
    }

    @Test
    void legacyStaleConsensusUnderTossOnlyGatesToExternalReviewAndSameRowUnderProvidersBlocks() throws Exception {
        var tossOnly = verify(TOSS_ONLY, """
                {"consensus":{"status":"STALE","asOf":"2026-09-01T00:00:00Z","source":"ALPHA_VANTAGE"}}
                """);
        var providers = verify(PROVIDERS, """
                {"consensus":{"status":"STALE","asOf":"2026-09-01T00:00:00Z","source":"ALPHA_VANTAGE"}}
                """);

        var tossDecision = InvestmentBuyRiskGate.evaluate(cleanMarket(), tossOnly, NOW,
                new InvestmentBuyRiskGate.BuyEvidence(TOSS_ONLY, Duration.ofMinutes(15), clearHazards()));
        var providersDecision = InvestmentBuyRiskGate.evaluate(cleanMarket(), providers, NOW,
                new InvestmentBuyRiskGate.BuyEvidence(PROVIDERS, Duration.ofMinutes(15), clearHazards()));

        assertThat(tossDecision.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(tossDecision.evidenceMode()).isEqualTo("EXTERNAL_CITED");
        assertThat(tossDecision.reasons()).containsExactly("FUNDAMENTALS_EXTERNAL_EVIDENCE_REQUIRED");
        assertThat(providersDecision.status()).isEqualTo("BLOCKED");
        assertThat(providersDecision.reasons()).contains("CONSENSUS_STALE");
    }

    @Test
    void tossSourcedStaleConsensusStillBlocksUnderTossOnly() throws Exception {
        var verification = verify(TOSS_ONLY, """
                {"consensus":{"status":"STALE","asOf":"2026-09-01T00:00:00Z","source":"TOSS"}}
                """);

        assertThat(verification.reasons()).contains("CONSENSUS_STALE");
        assertThat(verification.legacyProviderData()).isEmpty();
    }

    @Test
    void freshLegacyFundamentalsNeverBecomeAvailableUnderTossOnly() throws Exception {
        var verification = verify(TOSS_ONLY, """
                {"fundamentals":{"status":"OK","asOf":"2026-11-10T00:00:00Z","source":"SEC"},
                 "revision":{"status":"DATA_MISSING"},
                 "consensus":{"status":"DATA_MISSING"}}
                """);

        assertThat(verification.status()).isEqualTo("UNAVAILABLE");
        assertThat(verification.reasons()).containsExactly(UNSUPPORTED);
        assertThat(verification.legacyProviderData()).singleElement()
                .satisfies(legacy -> assertThat(legacy.group()).isEqualTo("fundamentals"));
    }

    @Test
    void legacyRevisionValueUnderTossOnlyIsExcludedWithoutAnyStoredSource() throws Exception {
        var verification = verify(TOSS_ONLY, """
                {"revision":{"status":"STALE"},"consensus":{"status":"DATA_MISSING"}}
                """);

        assertThat(verification.reasons()).containsExactly(UNSUPPORTED);
        assertThat(verification.legacyProviderData()).singleElement()
                .satisfies(legacy -> assertThat(legacy.group()).isEqualTo("revision"));
    }

    private InvestmentBuyRiskGate.FundamentalVerification verify(String policy, String snapshot) throws Exception {
        var service = new InvestmentContextService(mock(JdbcTemplate.class), mapper,
                mock(PlatformTransactionManager.class), mock(StockDataProviderRegistry.class), null,
                mock(PortfolioReadService.class), mock(MonitoringWatchlistService.class),
                mock(RiskPolicyService.class), Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210),
                Duration.ofDays(10), Clock.fixed(NOW, ZoneOffset.UTC));
        JsonNode node = mapper.readTree(snapshot);
        return service.fundamentalVerification(node, NOW, policy);
    }

    private static com.jmj.trade.investment.tactical.MarketRiskEvaluator.Assessment cleanMarket() {
        return new com.jmj.trade.investment.tactical.MarketRiskEvaluator.Assessment(
                com.jmj.trade.investment.tactical.MarketRiskEvaluator.POLICY_VERSION, "OK", "INTACT",
                "NOT_EVALUABLE", "TOSS_OPENAPI_HAS_NO_FUNDAMENTAL_HEALTH_DATA", "CLEAR", "NO_PRICE_ACTION",
                "STRATEGIC", null, null, java.math.BigDecimal.valueOf(100), NOW,
                java.time.LocalDate.parse("2026-11-10"), "TOSS", null, null, null, null, null, null, null,
                "NOT_EVALUABLE", null, null);
    }

    private static InvestmentBuyRiskGate.TossStockHazards clearHazards() {
        return new InvestmentBuyRiskGate.TossStockHazards("CLEAR", List.of(), null);
    }
}
