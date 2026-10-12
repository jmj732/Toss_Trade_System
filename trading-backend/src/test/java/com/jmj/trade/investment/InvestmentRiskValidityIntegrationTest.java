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
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentRiskValidityIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("432a230c-8f0a-4aa2-9141-d3a395090d1d");
    private static final UUID CONNECTION_ID = UUID.fromString("a0b4732d-65c2-4b49-9c97-530d630e83a2");
    private static final Instant NOW = Instant.parse("2026-10-01T16:00:00Z");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private ObjectMapper mapper;
    private int snapshotSequence;

    @BeforeEach
    void migrateAndSeed() {
        snapshotSequence = 0;
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
        insertUserConfirmedRevision("AAPL", bd("80"));
    }

    @Test
    void preflightRecalculatesHeldPurchaseRiskAtServerMarkWithoutChangingPortfolioEquity() throws Exception {
        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.10"));
        var before = context.context(USER_ID);
        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));

        assertThat(result.buyEligible()).as("blockers=%s cash=%s", result.blockers(), result.cash()).isTrue();
        assertThat(result.blockers()).isEmpty();
        assertThat(result.currentRisk().get("AAPL").portfolioWeight()).isEqualByComparingTo("0.25");
        assertThat(result.proFormaRisk().get("AAPL").portfolioWeight()).isEqualByComparingTo("0.35");
        assertThat(result.proFormaRisk().get("AAPL").plannedLossContribution()).isEqualByComparingTo("0.07");
        assertThat(result.proFormaRisk().get("AAPL").thesisFailureStress()).isEqualByComparingTo("0.07");
        assertThat(result.cash().availableBefore()).isEqualByComparingTo("750");
        assertThat(result.cash().purchaseCost()).isEqualByComparingTo("100");
        assertThat(result.cash().availableAfter()).isEqualByComparingTo("650");
        assertThat(result.proFormaRisk().get("AAPL").riskMark().value()).isEqualByComparingTo("100");
        assertThat(context.context(USER_ID)).isEqualTo(before);
    }

    @Test
    void preflightAllowsNewRegisteredTickerWhenOnlyProFormaWeightMakesItEligible() throws Exception {
        insertConfirmedThesis("GOOGL");
        insertCurrentPrice("GOOGL");
        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.10"));

        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("GOOGL", bd("1")));

        assertThat(result.currentRisk().get("GOOGL").portfolioWeight()).isNull();
        assertThat(result.currentRisk().get("GOOGL").eligibilityReasons()).contains("POSITION_WEIGHT_MISSING");
        assertThat(result.proFormaRisk().get("GOOGL").portfolioWeight()).isEqualByComparingTo("0.10");
        assertThat(result.proFormaRisk().get("GOOGL").sizingEligible()).isTrue();
        assertThat(result.buyEligible()).as("blockers=%s cash=%s", result.blockers(), result.cash()).isTrue();
    }

    @Test
    void preflightKeepsMissingHeldInvalidationRiskMissingAndBlocksNewBuy() throws Exception {
        insertConfirmedThesis("GOOGL");
        insertCurrentPrice("GOOGL");
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, invalidation_status, classification, updated_at
                ) VALUES (?, 'MSFT', 'Missing trigger test', 'CONFIRMED', 'COMPOUNDER', ?)
                """, USER_ID, OffsetDateTime.now(ZoneOffset.UTC));
        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.10"),
                List.of(heldPosition("AAPL"), heldPosition("MSFT")), Map.of());

        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("GOOGL", bd("1")));

        assertThat(result.currentRisk().get("MSFT").plannedLossContribution()).isNull();
        assertThat(result.currentRisk().get("MSFT").eligibilityReasons())
                .contains("INVALIDATION_PRICE_NOT_CONFIGURED");
        assertThat(result.currentRisk().get("MSFT").thesisFailureStress()).isNull();
        assertThat(result.buyEligible()).isFalse();
        assertThat(result.blockers()).contains("HELD_POSITION_RISK_INCOMPLETE");
    }

    @Test
    void preflightBlocksInsufficientCashAndInvalidQuantity() throws Exception {
        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.10"));
        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("8")));

        assertThat(result.buyEligible()).isFalse();
        assertThat(result.blockers()).contains("INSUFFICIENT_CASH");
        assertThat(result.cash().availableAfter()).isEqualByComparingTo("-50");
        assertThatThrownBy(() -> new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", BigDecimal.ZERO)))
                .isInstanceOf(InvestmentException.class);
        assertThatThrownBy(() -> new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", new BigDecimal("1E+1000000000"))))
                .isInstanceOf(InvestmentException.class);
        assertThatThrownBy(() -> new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", new BigDecimal("1E-1000000000"))))
                .isInstanceOf(InvestmentException.class);
    }

    @Test
    void preflightBlocksStaleAndPartialBrokerPortfolios() throws Exception {
        for (var status : List.of("STALE", "PARTIAL")) {
            var context = service(status, "OK", Instant.now(), Instant.now(), bd("0.10"));

            var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                    new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));

            assertThat(result.buyEligible()).isFalse();
            assertThat(result.blockers()).contains("PORTFOLIO_RISK_UNAVAILABLE");
            assertThat(result.portfolio().status()).isEqualTo("BLOCKED");
        }
    }

    @Test
    void preflightBlocksWhenProFormaLossCrossesSoftBudgetButCurrentRiskPasses() throws Exception {
        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.06"));

        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));

        assertThat(result.currentRisk().get("AAPL").softBudgetStatus()).isEqualTo("WITHIN_SOFT_BUDGET");
        assertThat(result.proFormaRisk().get("AAPL").thesisFailureStress()).isEqualByComparingTo("0.07");
        assertThat(result.proFormaRisk().get("AAPL").softBudgetStatus()).isEqualTo("OVER_SOFT_BUDGET");
        assertThat(result.blockers()).contains("PROFORMA_SOFT_BUDGET_EXCEEDED");
        assertThat(result.buyEligible()).isFalse();
    }

    @Test
    void preflightChecksConcentrationOnProFormaWeights() throws Exception {
        var context = service("OK", "OK", Instant.now(), Instant.now(),
                new RiskPolicyService.RiskPolicySnapshot(1, bd("10000"), bd("10000"), bd("100"),
                        bd("0.30"), bd("0.10"), true), List.of(heldPosition("AAPL")), Map.of());

        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));

        assertThat(result.currentRisk().get("AAPL").portfolioWeight()).isEqualByComparingTo("0.25");
        assertThat(result.proFormaRisk().get("AAPL").portfolioWeight()).isEqualByComparingTo("0.35");
        assertThat(result.policy().concentrationWithinLimit()).isFalse();
        assertThat(result.blockers()).contains("MAX_CONCENTRATION_EXCEEDED");
        assertThat(result.buyEligible()).isFalse();
    }

    @Test
    void preflightAppliesMaxQuantityAndMaxOrderAmountPolicy() throws Exception {
        var context = service("OK", "OK", Instant.now(), Instant.now(),
                new RiskPolicyService.RiskPolicySnapshot(1, bd("50"), bd("50"), bd("0.5"),
                        BigDecimal.ONE, bd("0.10"), true), List.of(heldPosition("AAPL")), Map.of());

        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));

        assertThat(result.blockers()).contains("MAX_QUANTITY_EXCEEDED", "MAX_ORDER_AMOUNT_EXCEEDED");
        assertThat(result.policy().orderAmountWithinLimit()).isFalse();
        assertThat(result.buyEligible()).isFalse();
    }

    @Test
    void preflightRejectsStaleRiskMarkAndAcceptsVerifiedRegularCloseFallback() throws Exception {
        var staleContext = service("OK", "STALE", Instant.now().minus(Duration.ofHours(2)), Instant.now(),
                bd("0.10"));
        var stale = new InvestmentRiskPreflightService(staleContext, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));
        assertThat(stale.blockers()).contains("RISK_MARK_UNAVAILABLE");
        assertThat(stale.buyEligible()).isFalse();

        var price = closedGapPrice(true);
        var regularCloseContext = service("OK", "STALE", Instant.parse((String) price.get("latestPriceAsOf")),
                Instant.now(), bd("0.10"), price);
        var regularClose = new InvestmentRiskPreflightService(regularCloseContext, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));
        assertThat(regularClose.proFormaRisk().get("AAPL").riskMark().basis())
                .isEqualTo(InvestmentRiskMarkSelector.REGULAR_CLOSE_BASIS);
        assertThat(regularClose.buyEligible())
                .as("blockers=%s cash=%s", regularClose.blockers(), regularClose.cash()).isTrue();
    }

    @Test
    void preflightRequiresExplicitCurrencyForUnheldTicker() throws Exception {
        insertConfirmedThesis("GOOGL");
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var payload = mapper.createObjectNode();
        payload.put("asOf", now.toString());
        payload.set("price", mapper.valueToTree(Map.of(
                "latestPrice", 100, "latestPriceAsOf", now.toString(),
                "session", "LIVE_REGULAR", "status", "OK", "source", "TOSS")));
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, 'GOOGL', ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), USER_ID, now, mapper.writeValueAsString(payload), now);
        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.10"));

        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("GOOGL", bd("1")));

        assertThat(result.blockers()).contains("CURRENCY_MISSING");
        assertThat(result.buyEligible()).isFalse();
    }

    @Test
    void preflightRequiresAiReceiptToMatchTheCurrentApprovedRevision() throws Exception {
        var proposalRevisionId = jdbc.queryForObject("""
                SELECT id FROM investment_thesis_revisions
                 WHERE user_id = ? AND ticker = 'AAPL' AND revision = 1
                """, UUID.class, USER_ID);
        var eventId = UUID.randomUUID();
        var approvedRevisionId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_thesis_ai_verification_events (
                    verification_event_id, user_id, ticker, proposal_revision_id, verification_run_id,
                    provider, model, verdict, source_as_of, source_urls, rationale, counterevidence,
                    selected_trigger_price, outcome, reason_code, policy_version, policy_snapshot,
                    evaluation_snapshot, authenticated_actor_user_id, body_hash, created_at
                ) VALUES (?, ?, 'AAPL', ?, 'verify-run', 'test-provider', 'test-model', 'PASS', ?,
                          '[]'::jsonb, 'supported', 'reviewed', 80, 'AUTO_APPROVED', 'AUTO_APPROVED',
                          'test-v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?)
                """, eventId, USER_ID, proposalRevisionId, now, USER_ID, "0".repeat(64), now);
        jdbc.update("""
                INSERT INTO investment_thesis_revisions (
                    id, user_id, ticker, revision, previous_status, new_status, previous_trigger_price,
                    new_trigger_price, thesis_snapshot, actor_type, actor_user_id, reason, recorded_at,
                    source_as_of, policy_version, verification_event_id
                ) VALUES (?, ?, 'AAPL', 2, 'CONFIRMED', 'CONFIRMED', 80, 80, '{}'::jsonb,
                          'AI_POLICY', ?, 'AUTO_APPROVED', ?, ?, 'test-v1', ?)
                """, approvedRevisionId, USER_ID, USER_ID, now, now, eventId);

        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.10"));
        var verification = mock(InvestmentThesisVerificationService.class);
        var preflight = new InvestmentRiskPreflightService(context, jdbc, verification,
                new DataSourceTransactionManager(dataSource));
        when(verification.verificationReceipt(USER_ID, "AAPL", eventId)).thenReturn(null);

        var missingReceipt = preflight.preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));
        assertThat(missingReceipt.thesis().receiptRequired()).isTrue();
        assertThat(missingReceipt.thesis().receiptMatchesCurrentRevision()).isFalse();
        assertThat(missingReceipt.blockers()).contains("AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED");

        when(verification.verificationReceipt(USER_ID, "AAPL", eventId)).thenReturn(
                new InvestmentThesisVerificationService.VerificationReceipt(eventId, proposalRevisionId,
                        "AUTO_APPROVED", "AUTO_APPROVED", "test-v1", UUID.randomUUID(), 2L));
        var mismatchedReceipt = preflight.preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));
        assertThat(mismatchedReceipt.thesis().receiptMatchesCurrentRevision()).isFalse();
        assertThat(mismatchedReceipt.blockers()).contains("AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED");

        when(verification.verificationReceipt(USER_ID, "AAPL", eventId)).thenReturn(
                new InvestmentThesisVerificationService.VerificationReceipt(eventId, proposalRevisionId,
                        "AUTO_APPROVED", "AUTO_APPROVED", "test-v1", approvedRevisionId, 2L));
        var matchingReceipt = preflight.preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("AAPL", bd("1")));
        assertThat(matchingReceipt.thesis().receiptMatchesCurrentRevision()).isTrue();
        assertThat(matchingReceipt.blockers()).doesNotContain("AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED");
    }

    @Test
    void preflightReturnsBlockedViewForUnknownTickerAndEmptyPortfolio() throws Exception {
        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.10"), List.of(), Map.of());

        var result = new InvestmentRiskPreflightService(context, jdbc).preflight(USER_ID,
                new InvestmentRiskPreflightService.PreflightInput("ZZZZ", bd("1")));

        assertThat(result.buyEligible()).isFalse();
        assertThat(result.blockers()).contains("THESIS_MISSING");
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
        assertThat(security.risk().riskMark().status()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
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
    void confirmedGooglRemainsConditionalWhenAnotherHeldPositionHasNoInvalidationTrigger() throws Exception {
        var now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.update("DELETE FROM investment_thesis_states WHERE user_id = ? AND ticker = 'AAPL'", USER_ID);
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, price_risk_trigger, price_risk_trigger_price,
                    invalidation_status, classification, updated_at
                ) VALUES (?, 'GOOGL', 'Growth thesis', 'Breaks below support', 80,
                          'CONFIRMED', 'COMPOUNDER', ?)
                """, USER_ID, now);

        var context = service("OK", "OK", Instant.now(), Instant.now(), bd("0.10"),
                List.of(heldPosition("GOOGL"), heldPosition("MSFT")), Map.of()).context(USER_ID);
        var googl = context.securities().stream().filter(security -> security.ticker().equals("GOOGL"))
                .findFirst().orElseThrow();
        var msft = context.securities().stream().filter(security -> security.ticker().equals("MSFT"))
                .findFirst().orElseThrow();

        assertThat(googl.risk().status()).isEqualTo(InvestmentDataCalculator.DataStatus.OK);
        assertThat(googl.risk().plannedLossContribution()).isNotNull();
        assertThat(googl.risk().thesisFailureStress()).isNull();
        assertThat(googl.risk().softBudgetStatus()).isEqualTo("DATA_MISSING");
        assertThat(googl.risk().sizingEligible()).isFalse();
        assertThat(mapper.valueToTree(googl).path("sizingEligibility").asText()).isEqualTo("CONDITIONAL");
        assertThat(msft.risk().status()).isEqualTo(InvestmentDataCalculator.DataStatus.NOT_CONFIGURED);
        assertThat(msft.risk().plannedLossContribution()).isNull();
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
    void declaredClosedGapUsesVerifiedCloseForRiskWithoutChangingStaleQuote() throws Exception {
        var price = closedGapPrice(true);
        var context = service("OK", "STALE", Instant.parse((String) price.get("latestPriceAsOf")), Instant.now(),
                bd("0.10"), price).context(USER_ID);
        var security = context.securities().getFirst();

        assertThat(security.price().path("status").asText()).isEqualTo("STALE");
        assertThat(security.price().path("latestPrice").decimalValue()).isEqualByComparingTo("101");
        assertThat(security.risk().riskMark().status()).isEqualTo(InvestmentDataCalculator.DataStatus.OK);
        assertThat(security.risk().riskMark().value()).isEqualByComparingTo("100");
        assertThat(security.risk().riskMark().basis())
                .isEqualTo(InvestmentRiskMarkSelector.REGULAR_CLOSE_BASIS);
        assertThat(security.risk().riskMark().asOfBasis())
                .isEqualTo(InvestmentRiskMarkSelector.PROVIDER_SESSION_LABEL_BASIS);
        assertThat(security.risk().invalidationDownside()).isEqualByComparingTo("0.20000000");
        assertThat(security.risk().plannedLossContribution()).isEqualByComparingTo("0.05000000");
    }

    @Test
    void reopenedOrUndeclaredCalendarGapDoesNotUseRegularClose() throws Exception {
        var reopenedPrice = closedGapPrice(true);
        reopenedPrice.put("nextDeclaredIntervalStartsAt", Instant.now().minusSeconds(60).toString());
        var reopened = securityWithPrice(reopenedPrice);

        assertThat(reopened.price().path("status").asText()).isEqualTo("STALE");
        assertThat(reopened.risk().riskMark().status()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(reopened.risk().riskMark().value()).isNull();
        assertThat(reopened.risk().invalidationDownside()).isNull();

        var missingCalendarPrice = closedGapPrice(false);
        var missingCalendar = securityWithPrice(missingCalendarPrice);

        assertThat(missingCalendar.risk().riskMark().status())
                .isEqualTo(InvestmentDataCalculator.DataStatus.UNVERIFIED);
        assertThat(missingCalendar.risk().riskMark().value()).isNull();
        assertThat(missingCalendar.risk().invalidationDownside()).isNull();
    }

    @Test
    void absentPolicyEnabledIsRejectedWhileExplicitFalseDisablesPolicy() throws Exception {
        var verification = new InvestmentThesisVerificationService(jdbc,
                new DataSourceTransactionManager(dataSource), service("OK", "OK", Instant.now(), Instant.now()),
                mapper, Clock.fixed(Instant.now(), ZoneOffset.UTC), false, false, "", "test-v1",
                Duration.ofHours(24), 2, bd("0.25"));
        var omittedEnabled = mapper.readValue("{}", InvestmentThesisVerificationService.PolicyInput.class);

        assertThat(omittedEnabled.enabled()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> verification.updatePolicy(USER_ID, omittedEnabled))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.INVALID_INPUT);

        var disabled = verification.updatePolicy(USER_ID,
                new InvestmentThesisVerificationService.PolicyInput(false, null));
        assertThat(disabled.enabled()).isFalse();
        assertThat(verification.policy(USER_ID).enabled()).isFalse();
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
        return service(portfolioStatus, priceStatus, priceAsOf, consensusAsOf, bd("0.10")).context(USER_ID);
    }

    private InvestmentContextService.ContextView context(
            String portfolioStatus, String priceStatus, Instant priceAsOf, Instant consensusAsOf, BigDecimal softRiskBudget) throws Exception {
        return service(portfolioStatus, priceStatus, priceAsOf, consensusAsOf, softRiskBudget).context(USER_ID);
    }

    private InvestmentContextService service(
            String portfolioStatus, String priceStatus, Instant priceAsOf, Instant consensusAsOf) throws Exception {
        return service(portfolioStatus, priceStatus, priceAsOf, consensusAsOf, bd("0.10"));
    }

    private InvestmentContextService service(
            String portfolioStatus, String priceStatus, Instant priceAsOf, Instant consensusAsOf, BigDecimal softRiskBudget) throws Exception {
        return service(portfolioStatus, priceStatus, priceAsOf, consensusAsOf, softRiskBudget, Map.of());
    }

    private InvestmentContextService service(
            String portfolioStatus, String priceStatus, Instant priceAsOf, Instant consensusAsOf,
            BigDecimal softRiskBudget, Map<String, Object> priceOverrides) throws Exception {
        return service(portfolioStatus, priceStatus, priceAsOf, consensusAsOf, softRiskBudget,
                List.of(heldPosition("AAPL")), Map.of("AAPL", priceOverrides));
    }

    private InvestmentContextService service(
            String portfolioStatus, String priceStatus, Instant priceAsOf, Instant consensusAsOf,
            BigDecimal softRiskBudget, List<PortfolioReadService.PositionView> positions,
            Map<String, Map<String, Object>> priceOverridesByTicker) throws Exception {
        return service(portfolioStatus, priceStatus, priceAsOf, consensusAsOf,
                new RiskPolicyService.RiskPolicySnapshot(1, bd("10000"), bd("10000"), bd("100"),
                        BigDecimal.ONE, softRiskBudget, true), positions, priceOverridesByTicker);
    }

    private InvestmentContextService service(
            String portfolioStatus, String priceStatus, Instant priceAsOf, Instant consensusAsOf,
            RiskPolicyService.RiskPolicySnapshot riskPolicy,
            List<PortfolioReadService.PositionView> positions,
            Map<String, Map<String, Object>> priceOverridesByTicker) throws Exception {
        var now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        var snapshotAsOf = OffsetDateTime.ofInstant(NOW.plusMillis(snapshotSequence++), ZoneOffset.UTC);
        var snapshot = new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), NOW,
                "STALE".equals(portfolioStatus),
                "STALE".equals(portfolioStatus) ? "SNAPSHOT_TOO_OLD" : null,
                "PARTIAL".equals(portfolioStatus),
                "PARTIAL".equals(portfolioStatus) ? List.of("BUYING_POWER_USD") : List.of(),
                List.of(),
                new PortfolioReadService.AccountView("BROKERAGE", "****", Map.of(), Map.of("USD", bd("1000")),
                        Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, NOW),
                positions,
                Map.of());
        var portfolios = mock(PortfolioReadService.class);
        var reportedCash = bd("1000").subtract(positions.stream()
                .map(PortfolioReadService.PositionView::marketValueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        snapshot = new PortfolioReadService.PortfolioView(snapshot.syncRunId(), snapshot.completedAt(),
                snapshot.stale(), snapshot.staleReason(), snapshot.partial(), snapshot.missingSections(),
                snapshot.unknownFields(), snapshot.account(), snapshot.positions(),
                Map.of("USD", new PortfolioReadService.BuyingPowerView(reportedCash, Instant.now().minusSeconds(1))));
        when(portfolios.read(USER_ID, CONNECTION_ID)).thenReturn(snapshot);
        when(portfolios.snapshotMaxAge()).thenReturn(Duration.ofMinutes(15));
        var watchlist = mock(MonitoringWatchlistService.class);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        var riskPolicies = mock(RiskPolicyService.class);
        when(riskPolicies.current(USER_ID)).thenReturn(riskPolicy);
        for (var position : positions) {
            var price = new LinkedHashMap<String, Object>();
            price.put("latestPrice", 100);
            price.put("latestPriceAsOf", priceAsOf.toString());
            price.put("session", "LIVE_REGULAR");
            price.put("status", priceStatus);
            price.put("source", "TOSS");
            price.putAll(priceOverridesByTicker.getOrDefault(position.symbol(), Map.of()));
            var payload = mapper.valueToTree(Map.of(
                    "asOf", NOW.toString(),
                    "price", price,
                    "consensus", Map.of("asOf", consensusAsOf.toString(), "status", "OK")));
            jdbc.update("""
                    INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """, UUID.randomUUID(), USER_ID, position.symbol(), snapshotAsOf,
                    mapper.writeValueAsString(payload), now);
        }
        var service = new InvestmentContextService(
                jdbc, mapper, new DataSourceTransactionManager(jdbc.getDataSource()),
                new StockDataProviderRegistry(List.of()), mock(ObjectProvider.class), portfolios, watchlist, riskPolicies,
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
        return service;
    }

    private static PortfolioReadService.PositionView heldPosition(String ticker) {
        return new PortfolioReadService.PositionView(ticker, ticker, "US", bd("1"), "USD",
                bd("90"), bd("100"), bd("250"), bd("250"), bd("250"), bd("0"), bd("0"),
                bd("0"), bd("0"), bd("0"), bd("0"), bd("0"), bd("0"), bd("1"), NOW);
    }

    private void insertConfirmedThesis(String ticker) {
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, price_risk_trigger, price_risk_trigger_price,
                    invalidation_status, classification, updated_at
                ) VALUES (?, ?, 'Growth thesis', 'Breaks below support', 80, 'CONFIRMED', 'COMPOUNDER', ?)
                """, USER_ID, ticker, OffsetDateTime.now(ZoneOffset.UTC));
        insertUserConfirmedRevision(ticker, bd("80"));
    }

    private void insertUserConfirmedRevision(String ticker, BigDecimal triggerPrice) {
        jdbc.update("""
                INSERT INTO investment_thesis_revisions (
                    id, user_id, ticker, revision, previous_status, new_status, previous_trigger_price,
                    new_trigger_price, thesis_snapshot, actor_type, actor_user_id, reason, recorded_at
                ) VALUES (?, ?, ?, 1, 'NOT_REVIEWED', 'CONFIRMED', NULL, ?, '{}'::jsonb,
                          'USER_SESSION', ?, 'USER_CONFIRMED', ?)
                """, UUID.randomUUID(), USER_ID, ticker, triggerPrice, USER_ID,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void insertCurrentPrice(String ticker) throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var payload = mapper.createObjectNode();
        payload.put("asOf", now.toString());
        payload.set("price", mapper.valueToTree(Map.of(
                "latestPrice", 100,
                "latestPriceAsOf", now.toString(),
                "session", "LIVE_REGULAR",
                "status", "OK",
                "source", "TOSS",
                "currency", "USD")));
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), USER_ID, ticker, now, mapper.writeValueAsString(payload), now);
    }

    private InvestmentContextService.SecurityView securityWithPrice(Map<String, Object> price) throws Exception {
        return service("OK", "STALE", Instant.parse((String) price.get("latestPriceAsOf")), Instant.now(),
                bd("0.10"), price).context(USER_ID).securities().getFirst();
    }

    private static Map<String, Object> closedGapPrice(boolean includeCalendar) {
        var now = Instant.now();
        var quoteAsOf = now.minus(Duration.ofHours(3));
        var sessionDate = quoteAsOf.atZone(NEW_YORK).toLocalDate().minusDays(1);
        while (sessionDate.getDayOfWeek() == DayOfWeek.SATURDAY
                || sessionDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            sessionDate = sessionDate.minusDays(1);
        }
        var closeAsOf = sessionDate.atTime(16, 0).atZone(NEW_YORK).toInstant();
        var price = new LinkedHashMap<String, Object>();
        price.put("latestPrice", 101);
        price.put("latestPriceAsOf", quoteAsOf.toString());
        price.put("session", null);
        price.put("status", "STALE");
        price.put("source", "TOSS");
        price.put("sessionReason", InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON);
        price.put("regularClose", 100);
        price.put("regularCloseAsOf", closeAsOf.toString());
        price.put("regularCloseSessionDate", sessionDate.toString());
        price.put("lastCompletedSessionDate", sessionDate.toString());
        price.put("regularCloseValidUntil", now.plus(Duration.ofDays(2)).toString());
        if (includeCalendar) {
            price.put("nextDeclaredIntervalStartsAt", now.plus(Duration.ofDays(1)).toString());
        }
        return price;
    }

    @Test
    void riskBudgetExceededMakesSizingIneligible() throws Exception {
        // Budget 0.01 with stress 0.05 → over budget
        var risk = context("OK", "OK", Instant.now(), Instant.now(), bd("0.01")).securities().getFirst().risk();
        assertThat(risk.sizingEligible()).isFalse();
        assertThat(risk.eligibilityReasons()).contains("RISK_BUDGET_EXCEEDED");
        assertThat(risk.softBudgetStatus()).isEqualTo("OVER_SOFT_BUDGET");
    }

    @Test
    void nullRiskBudgetMakesSizingIneligible() throws Exception {
        // Budget null → RISK_BUDGET_NOT_CONFIGURED
        var risk = context("OK", "OK", Instant.now(), Instant.now(), null).securities().getFirst().risk();
        assertThat(risk.sizingEligible()).isFalse();
        assertThat(risk.eligibilityReasons()).contains("RISK_BUDGET_NOT_CONFIGURED");
    }

    @Test
    void withinBudgetKeepsSizingEligible() throws Exception {
        // Budget 0.10 with stress within limit → eligible with empty reasons
        var risk = context("OK", "OK").securities().getFirst().risk();
        assertThat(risk.sizingEligible()).isTrue();
        assertThat(risk.eligibilityReasons()).isEmpty();
        assertThat(risk.softBudgetStatus()).isEqualTo("WITHIN_SOFT_BUDGET");
    }

    private static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
