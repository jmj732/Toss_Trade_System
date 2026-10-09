package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentRiskValidityIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("432a230c-8f0a-4aa2-9141-d3a395090d1d");
    private static final UUID CONNECTION_ID = UUID.fromString("a0b4732d-65c2-4b49-9c97-530d630e83a2");
    private static final Instant NOW = Instant.parse("2026-10-01T16:00:00Z");
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private ObjectMapper mapper;

    @BeforeEach
    void migrateAndSeed() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        var now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, last_validated_at, created_at, updated_at
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, ?)
                """, CONNECTION_ID, USER_ID, new byte[32], new byte[12], now, now, now);
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, price_risk_trigger, price_risk_trigger_price,
                    invalidation_status, classification, updated_at
                ) VALUES (?, 'AAPL', 'Growth thesis', 'Breaks below support', 80, 'CONFIRMED', 'COMPOUNDER', ?)
                """, USER_ID, now);
    }

    @AfterEach
    void closeTestDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void stalePortfolioDoesNotProduceUsableRiskNumbers() throws Exception {
        var risk = context("STALE", "OK").securities().getFirst().risk();

        assertThat(risk.portfolioWeight()).isNull();
        assertThat(risk.invalidationDownside()).isNull();
        assertThat(risk.plannedLossContribution()).isNull();
        assertThat(risk.status()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(risk.thesisFailureStress()).isNull();
        assertThat(risk.thesisFailureStressStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(risk.top2CorrelatedStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(risk.sizingEligible()).isFalse();
        assertThat(risk.softBudgetStatus()).isEqualTo("DATA_MISSING");
    }

    @Test
    void partialPortfolioDoesNotProduceUsableRiskNumbers() throws Exception {
        var risk = context("PARTIAL", "OK").securities().getFirst().risk();

        assertThat(risk.portfolioWeight()).isNull();
        assertThat(risk.invalidationDownside()).isNull();
        assertThat(risk.plannedLossContribution()).isNull();
        assertThat(risk.status()).isEqualTo(InvestmentDataCalculator.DataStatus.PARTIAL);
        assertThat(risk.sizingEligible()).isFalse();
        assertThat(risk.softBudgetStatus()).isEqualTo("DATA_MISSING");
    }

    @Test
    void conflictedPriceDoesNotProduceUsableRiskNumbers() throws Exception {
        var risk = context("OK", "SOURCE_CONFLICT").securities().getFirst().risk();

        assertThat(risk.portfolioWeight()).isEqualByComparingTo("0.25");
        assertThat(risk.invalidationDownside()).isNull();
        assertThat(risk.plannedLossContribution()).isNull();
        assertThat(risk.status()).isEqualTo(InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT);
        assertThat(risk.thesisFailureStressStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT);
        assertThat(risk.top2CorrelatedStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT);
        assertThat(risk.sizingEligible()).isFalse();
        assertThat(risk.softBudgetStatus()).isEqualTo("DATA_MISSING");
    }

    @Test
    void stalePersistedQuoteIsMarkedStaleWhenContextIsRead() throws Exception {
        var security = context("OK", "OK", Instant.now().minus(Duration.ofHours(2))).securities().getFirst();

        assertThat(security.price().get("status").asText()).isEqualTo("STALE");
        assertThat(security.readiness().get("priceStatus").asText()).isEqualTo("STALE");
        assertThat(security.readiness().get("overallDataStatus").asText()).isEqualTo("STALE");
        assertThat(security.risk().status()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(security.risk().plannedLossContribution()).isNull();
        assertThat(security.risk().sizingEligible()).isFalse();
    }

    @Test
    void stalePersistedConsensusMarksReadinessStaleWhenContextIsRead() throws Exception {
        var security = context("OK", "OK", Instant.now(),
                Instant.now().minus(Duration.ofDays(20))).securities().getFirst();

        assertThat(security.consensus().get("status").asText()).isEqualTo("STALE");
        assertThat(security.readiness().get("overallDataStatus").asText()).isEqualTo("STALE");
    }

    @Test
    void confirmedThesisUsesPortfolioFractionTimesPriceDownside() throws Exception {
        var risk = context("OK", "OK").securities().getFirst().risk();

        assertThat(risk.portfolioWeight()).isEqualByComparingTo("0.25");
        assertThat(risk.invalidationDownside()).isEqualByComparingTo("0.20000000");
        assertThat(risk.plannedLossContribution()).isEqualByComparingTo("0.05000000");
        assertThat(risk.status()).isEqualTo(InvestmentDataCalculator.DataStatus.OK);
        assertThat(risk.top2CorrelatedStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.NOT_APPLICABLE);
        assertThat(risk.sizingEligible()).isTrue();
        assertThat(risk.softBudgetStatus()).isEqualTo("WITHIN_SOFT_BUDGET");
    }

    @Test
    void publishesThreeStateEligibilityWithoutReplacingLegacyBoolean() throws Exception {
        var security = context("OK", "OK").securities().getFirst();
        assertThat(mapper.valueToTree(security).path("sizingEligibility").asText()).isEqualTo("YES");
        assertThat(security.risk().sizingEligible()).isTrue();
        var conditional = new InvestmentContextService.SecurityView("AAPL", null, null,
                null, null, null, null, null, null, null, security.thesis(), null);
        assertThat(mapper.valueToTree(conditional)
                .path("sizingEligibility").asText()).isEqualTo("CONDITIONAL");
        var unknown = new InvestmentContextService.SecurityView("AAPL", null, null,
                null, null, null, null, null, null, null, null, null);
        assertThat(mapper.valueToTree(unknown)
                .path("sizingEligibility").asText()).isEqualTo("NO");
    }

    @Test
    void aiProposedTriggerIsNeverUsedForRiskNumbers() throws Exception {
        jdbc.update("UPDATE investment_thesis_states SET invalidation_status='AI_PROPOSED' "
                + "WHERE user_id=? AND ticker='AAPL'", USER_ID);
        var security = context("OK", "OK").securities().getFirst();
        var risk = security.risk();

        assertThat(risk.invalidationDownside()).isNull();
        assertThat(risk.plannedLossContribution()).isNull();
        assertThat(risk.status()).isEqualTo(InvestmentDataCalculator.DataStatus.UNVERIFIED);
        assertThat(risk.thesisFailureStress()).isNull();
        assertThat(risk.sizingEligible()).isFalse();
        assertThat(mapper.valueToTree(security).path("sizingEligibility").asText()).isEqualTo("NO");
        assertThat(risk.eligibilityReasons()).containsExactly("INVALIDATION_NOT_CONFIRMED", "RISK_BUDGET_UNEVALUATED");
        assertThat(risk.eligibilityReasons().isEmpty()).isEqualTo(risk.sizingEligible());
    }

    @Test
    void aiProposedWithoutTriggerReportsBothMissingApprovalAndMissingPrice() throws Exception {
        jdbc.update("UPDATE investment_thesis_states SET invalidation_status='AI_PROPOSED', "
                + "price_risk_trigger_price=NULL WHERE user_id=? AND ticker='AAPL'", USER_ID);
        var risk = context("OK", "OK").securities().getFirst().risk();

        assertThat(risk.status()).isEqualTo(InvestmentDataCalculator.DataStatus.NOT_CONFIGURED);
        assertThat(risk.invalidationDownside()).isNull();
        assertThat(risk.eligibilityReasons())
                .containsExactly("INVALIDATION_NOT_CONFIRMED", "INVALIDATION_PRICE_NOT_CONFIGURED", "RISK_BUDGET_UNEVALUATED");
        assertThat(risk.sizingEligible()).isFalse();
    }

    @Test
    void confirmedButBreachedTriggerYieldsNoDownsideAndFlagsBreach() throws Exception {
        jdbc.update("UPDATE investment_thesis_states SET price_risk_trigger_price=120 "
                + "WHERE user_id=? AND ticker='AAPL'", USER_ID);
        var risk = context("OK", "OK").securities().getFirst().risk();

        assertThat(risk.invalidationDownside()).isNull();
        assertThat(risk.plannedLossContribution()).isNull();
        assertThat(risk.eligibilityReasons()).containsExactly("INVALIDATION_PRICE_BREACHED", "RISK_BUDGET_UNEVALUATED");
        assertThat(risk.sizingEligible()).isFalse();
    }

    @Test
    void confirmedThesisWithStalePortfolioReportsPortfolioStaleReason() throws Exception {
        var risk = context("STALE", "OK").securities().getFirst().risk();

        assertThat(risk.eligibilityReasons()).contains("PORTFOLIO_STALE");
        assertThat(risk.sizingEligible()).isFalse();
    }

    @Test
    void confirmedHappyPathHasNoEligibilityReasons() throws Exception {
        var risk = context("OK", "OK").securities().getFirst().risk();

        assertThat(risk.sizingEligible()).isTrue();
        assertThat(risk.eligibilityReasons()).isEmpty();
        assertThat(risk.eligibilityReasons().isEmpty()).isEqualTo(risk.sizingEligible());
    }

    @Test
    void putThesisProposalRejectsConfirmedStatus() throws Exception {
        jdbc.update("DELETE FROM investment_thesis_states WHERE user_id=? AND ticker='AAPL'", USER_ID);
        var service = service("OK", "OK", Instant.now(), Instant.now());
        var input = new InvestmentContextService.ThesisInput(
                "Caller supplied thesis", null, null, null, null, "Breaks below support",
                new BigDecimal("80"), "CONFIRMED", null, null, "COMPOUNDER");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.putThesisProposal(USER_ID, "AAPL", input, null))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.INVALID_INPUT);
    }

    private InvestmentContextService.ContextView context(String portfolioStatus, String priceStatus) throws Exception {
        return context(portfolioStatus, priceStatus, Instant.now());
    }

    private InvestmentContextService.ContextView context(
            String portfolioStatus, String priceStatus, Instant priceAsOf) throws Exception {
        return context(portfolioStatus, priceStatus, priceAsOf, Instant.now());
    }

    private InvestmentContextService.ContextView context(
            String portfolioStatus, String priceStatus, Instant priceAsOf, Instant consensusAsOf) throws Exception {
        return service(portfolioStatus, priceStatus, priceAsOf, consensusAsOf).context(USER_ID);
    }

    private InvestmentContextService service(
            String portfolioStatus, String priceStatus, Instant priceAsOf, Instant consensusAsOf) throws Exception {
        var now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        var snapshot = new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), NOW,
                "STALE".equals(portfolioStatus),
                "STALE".equals(portfolioStatus) ? "SNAPSHOT_TOO_OLD" : null,
                "PARTIAL".equals(portfolioStatus),
                "PARTIAL".equals(portfolioStatus) ? List.of("BUYING_POWER_USD") : List.of(),
                List.of(),
                new PortfolioReadService.AccountView("BROKERAGE", "****", Map.of(), Map.of("USD", bd("1000")),
                        Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, NOW),
                List.of(new PortfolioReadService.PositionView("AAPL", "Apple", "US", bd("1"), "USD",
                        bd("90"), bd("100"), bd("250"), bd("250"), bd("250"), bd("0"), bd("0"),
                        bd("0"), bd("0"), bd("0"), bd("0"), bd("0"), bd("0"), bd("1"), NOW)),
                Map.of());
        var portfolios = mock(PortfolioReadService.class);
        when(portfolios.read(USER_ID, CONNECTION_ID)).thenReturn(snapshot);
        var watchlist = mock(MonitoringWatchlistService.class);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        var riskPolicies = mock(RiskPolicyService.class);
        when(riskPolicies.current(USER_ID)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                1, bd("10000"), bd("10000"), bd("100"), BigDecimal.ONE, bd("0.10"), true));
        var payload = mapper.valueToTree(Map.of(
                "asOf", NOW.toString(),
                "price", Map.of("latestPrice", 100, "latestPriceAsOf", priceAsOf.toString(),
                        "session", "LIVE_REGULAR", "status", priceStatus),
                "consensus", Map.of("asOf", consensusAsOf.toString(), "status", "OK")));
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, 'AAPL', ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), USER_ID, now, mapper.writeValueAsString(payload), now);
        var service = new InvestmentContextService(
                jdbc, mapper, new DataSourceTransactionManager(jdbc.getDataSource()),
                new StockDataProviderRegistry(List.of()), mock(ObjectProvider.class), portfolios, watchlist, riskPolicies,
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
        return service;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
