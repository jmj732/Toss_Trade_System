package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentStrategicBuyRevalidationServiceTest {
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final UUID DECISION = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    private static final UUID REVIEW = UUID.fromString("018f0000-0000-7000-8000-000000000004");
    private static final UUID REVISION = UUID.fromString("018f0000-0000-7000-8000-000000000005");
    private static final Instant NOW = Instant.parse("2026-11-07T16:00:00Z");
    private static final Instant MANDATE_UPDATED = NOW.minusSeconds(3600);

    @Test
    void missingEvidenceCannotBeRecordedAsAReview() {
        var service = service(mock(JdbcTemplate.class), mock(InvestmentContextService.class));
        var input = input(List.of(
                source("THESIS"), source("FUNDAMENTAL"), source("REVISION"), source("EXPECTATIONS")),
                passAssessment(), NOW.plusSeconds(600));

        assertThatThrownBy(() -> service.record(USER, KEY, input))
                .isInstanceOf(InvestmentException.class);
    }

    @Test
    void impairedThesisAssessmentIsNotAPass() {
        var assessment = new InvestmentStrategicBuyRevalidationService.Assessment(
                criterion("IMPAIRED"), criterion("SUPPORTED"), criterion("OPPORTUNITY"),
                criterion("FAVORABLE"), criterion("PASS"), criterion("CLEAR"));

        assertThat(assessment.passes()).isFalse();
    }

    @Test
    void currentEvidenceWithinItsScopeCanAuthorizeOnlyTheReviewedOrder() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var context = mock(InvestmentContextService.class);
        stubCurrent(jdbc, context, NOW.plusSeconds(600), REVISION, "IMPAIRED", "TRIGGER_BREACHED");
        var service = service(jdbc, context);

        var allowed = service.validateForSubmit(USER, DECISION, "LUNR", BigDecimal.valueOf(2),
                BigDecimal.valueOf(100), BigDecimal.valueOf(105), currentGate("IMPAIRED", "TRIGGER_BREACHED"));
        var tooLarge = service.validateForSubmit(USER, DECISION, "LUNR", BigDecimal.valueOf(6),
                BigDecimal.valueOf(100), BigDecimal.valueOf(105), currentGate("IMPAIRED", "TRIGGER_BREACHED"));
        var abovePriceScope = service.validateForSubmit(USER, DECISION, "LUNR", BigDecimal.valueOf(2),
                BigDecimal.valueOf(111), BigDecimal.valueOf(105), currentGate("IMPAIRED", "TRIGGER_BREACHED"));

        assertThat(allowed.allowed()).isTrue();
        assertThat(tooLarge.reason()).isEqualTo("STRATEGIC_APPROVED_QUANTITY_EXCEEDED");
        assertThat(abovePriceScope.reason()).isEqualTo("STRATEGIC_APPROVED_PRICE_RANGE_EXCEEDED");
    }

    @Test
    void expiredReviewCannotAuthorizeSubmission() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var context = mock(InvestmentContextService.class);
        stubCurrent(jdbc, context, NOW.minusSeconds(1), REVISION, "IMPAIRED", "TRIGGER_BREACHED");
        var result = service(jdbc, context).validateForSubmit(USER, DECISION, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), currentGate("IMPAIRED", "TRIGGER_BREACHED"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("STRATEGIC_REVALIDATION_EXPIRED");
    }

    @Test
    void blockedAiReviewCannotAuthorizeSubmission() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var context = mock(InvestmentContextService.class);
        stubCurrent(jdbc, context, NOW.plusSeconds(600), REVISION, "IMPAIRED", "TRIGGER_BREACHED", "BLOCKED");
        var result = service(jdbc, context).validateForSubmit(USER, DECISION, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), currentGate("IMPAIRED", "TRIGGER_BREACHED"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("STRATEGIC_REVALIDATION_NOT_PASSED");
    }

    @Test
    void callerAssertedPassCannotAuthorizeSubmission() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var context = mock(InvestmentContextService.class);
        stubCurrent(jdbc, context, NOW.plusSeconds(600), REVISION, "IMPAIRED", "TRIGGER_BREACHED",
                "PASS", "CALLER_ASSERTED");

        var result = service(jdbc, context).validateForSubmit(USER, DECISION, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), currentGate("IMPAIRED", "TRIGGER_BREACHED"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("STRATEGIC_REVALIDATION_UNTRUSTED");
    }

    @Test
    void thesisOrMarketStateChangeInvalidatesTheReview() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var context = mock(InvestmentContextService.class);
        stubCurrent(jdbc, context, NOW.plusSeconds(600), REVISION, "IMPAIRED", "TRIGGER_BREACHED");
        var service = service(jdbc, context);
        when(context.currentThesis(USER, "LUNR")).thenReturn(thesis(UUID.randomUUID()));
        var changedThesis = service.validateForSubmit(USER, DECISION, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), currentGate("IMPAIRED", "TRIGGER_BREACHED"));
        when(context.currentThesis(USER, "LUNR")).thenReturn(thesis(REVISION));
        var changedMarket = service.validateForSubmit(USER, DECISION, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), currentGate("BREAKDOWN", "TRIGGER_BREACHED"));

        assertThat(changedThesis.reason()).isEqualTo("THESIS_REVISION_CHANGED");
        assertThat(changedMarket.reason()).isEqualTo("STRATEGIC_MARKET_STATE_CHANGED");
    }

    @Test
    void mandateChangeInvalidatesTheReview() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var context = mock(InvestmentContextService.class);
        stubCurrent(jdbc, context, NOW.plusSeconds(600), REVISION, "IMPAIRED", "TRIGGER_BREACHED");
        when(context.positionMandates(USER)).thenReturn(List.of(new InvestmentContextService.PositionMandateView(
                "LUNR", "TACTICAL", "EXIT_EVALUATION", "USER_CONFIRMED", LocalDate.parse("2026-10-27"), NOW)));
        var result = service(jdbc, context).validateForSubmit(USER, DECISION, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), currentGate("IMPAIRED", "TRIGGER_BREACHED"));

        assertThat(result.reason()).isEqualTo("POSITION_MANDATE_CHANGED");
    }

    private static InvestmentStrategicBuyRevalidationService service(JdbcTemplate jdbc,
                                                                      InvestmentContextService context) {
        return new InvestmentStrategicBuyRevalidationService(jdbc, new ObjectMapper(), context,
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void stubCurrent(JdbcTemplate jdbc, InvestmentContextService context, Instant expiresAt,
                                    UUID thesisRevisionId, String marketHealth, String priceRiskState) throws Exception {
        stubCurrent(jdbc, context, expiresAt, thesisRevisionId, marketHealth, priceRiskState, "PASS");
    }

    private static void stubCurrent(JdbcTemplate jdbc, InvestmentContextService context, Instant expiresAt,
                                    UUID thesisRevisionId, String marketHealth, String priceRiskState,
                                    String verdict) throws Exception {
        stubCurrent(jdbc, context, expiresAt, thesisRevisionId, marketHealth, priceRiskState,
                verdict, "SERVER_GENERATED");
    }

    private static void stubCurrent(JdbcTemplate jdbc, InvestmentContextService context, Instant expiresAt,
                                    UUID thesisRevisionId, String marketHealth, String priceRiskState,
                                    String verdict, String reviewOrigin) throws Exception {
        var mandate = new InvestmentContextService.PositionMandateView("LUNR", "STRATEGIC", "REVIEW_TRIGGER",
                "USER_CONFIRMED", LocalDate.parse("2026-10-27"), MANDATE_UPDATED);
        when(context.positionMandates(USER)).thenReturn(List.of(mandate));
        when(context.currentThesis(USER, "LUNR")).thenReturn(thesis(thesisRevisionId));
        when(jdbc.query(contains("FROM investment_strategic_buy_revalidations"),
                any(RowMapper.class), eq(USER), eq(DECISION))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var mapper = (RowMapper<Object>) invocation.getArgument(1);
            var rs = mock(ResultSet.class);
            when(rs.getObject("review_id", UUID.class)).thenReturn(REVIEW);
            when(rs.getObject("user_id", UUID.class)).thenReturn(USER);
            when(rs.getObject("decision_id", UUID.class)).thenReturn(DECISION);
            when(rs.getString("ticker")).thenReturn("LUNR");
            when(rs.getString("investment_horizon")).thenReturn("STRATEGIC");
            when(rs.getObject("mandate_updated_at", OffsetDateTime.class)).thenReturn(MANDATE_UPDATED.atOffset(ZoneOffset.UTC));
            when(rs.getObject("thesis_revision_id", UUID.class)).thenReturn(REVISION);
            when(rs.getString("verdict")).thenReturn(verdict);
            when(rs.getString("review_origin")).thenReturn(reviewOrigin);
            when(rs.getString("provider")).thenReturn("review-provider");
            when(rs.getString("model")).thenReturn("review-model");
            when(rs.getString("rationale")).thenReturn("Thesis remains supported by current evidence.");
            when(rs.getObject("source_as_of", OffsetDateTime.class)).thenReturn(NOW.minusSeconds(60).atOffset(ZoneOffset.UTC));
            when(rs.getString("sources")).thenReturn("[]");
            when(rs.getString("assessment")).thenReturn("{}");
            when(rs.getBigDecimal("reviewed_market_price")).thenReturn(BigDecimal.valueOf(100));
            when(rs.getObject("market_observed_at", OffsetDateTime.class)).thenReturn(NOW.minusSeconds(60).atOffset(ZoneOffset.UTC));
            when(rs.getString("market_health")).thenReturn("IMPAIRED");
            when(rs.getString("price_risk_state")).thenReturn("TRIGGER_BREACHED");
            when(rs.getBigDecimal("min_price")).thenReturn(BigDecimal.valueOf(90));
            when(rs.getBigDecimal("max_price")).thenReturn(BigDecimal.valueOf(110));
            when(rs.getBigDecimal("max_quantity")).thenReturn(BigDecimal.valueOf(5));
            when(rs.getObject("expires_at", OffsetDateTime.class)).thenReturn(expiresAt.atOffset(ZoneOffset.UTC));
            when(rs.getString("body_hash")).thenReturn("hash");
            when(rs.getObject("created_at", OffsetDateTime.class)).thenReturn(NOW.minusSeconds(30).atOffset(ZoneOffset.UTC));
            return List.of(mapper.mapRow(rs, 0));
        });
        when(jdbc.query(contains("SELECT asset, action"), any(RowMapper.class), eq(USER), eq(DECISION)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    var mapper = (RowMapper<Object>) invocation.getArgument(1);
                    var rs = mock(ResultSet.class);
                    when(rs.getString("asset")).thenReturn("LUNR");
                    when(rs.getString("action")).thenReturn("ADD");
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private static InvestmentContextService.ThesisView thesis(UUID revisionId) {
        return new InvestmentContextService.ThesisView("LUNR", "Thesis", "Growth", "Expectations",
                "Invalidation", "Revision invalidation", "Risk trigger", BigDecimal.valueOf(80), "CONFIRMED",
                "Expansion", "Exit", "STRATEGIC", MANDATE_UPDATED, revisionId, 3L, "USER_SESSION",
                null, null, null);
    }

    private static InvestmentBuyRiskGate.Decision currentGate(String marketHealth, String priceRiskState) {
        return new InvestmentBuyRiskGate.Decision("REVIEW_REQUIRED", List.of("REVIEW"), "STRATEGIC", "OK",
                marketHealth, priceRiskState, null, NOW);
    }

    private static InvestmentStrategicBuyRevalidationService.RevalidationInput input(
            List<InvestmentStrategicBuyRevalidationService.EvidenceSource> sources,
            InvestmentStrategicBuyRevalidationService.Assessment assessment, Instant expiresAt) {
        return new InvestmentStrategicBuyRevalidationService.RevalidationInput(REVIEW, DECISION, "LUNR", REVISION,
                NOW.minusSeconds(300), expiresAt, "review-provider", "review-model",
                "Current business, estimate and price evidence remains favorable.", sources, assessment,
                BigDecimal.valueOf(90), BigDecimal.valueOf(110), BigDecimal.valueOf(5));
    }

    private static List<InvestmentStrategicBuyRevalidationService.EvidenceSource> sources(String... types) {
        return java.util.Arrays.stream(types).map(InvestmentStrategicBuyRevalidationServiceTest::source).toList();
    }

    private static InvestmentStrategicBuyRevalidationService.EvidenceSource source(String type) {
        return new InvestmentStrategicBuyRevalidationService.EvidenceSource(
                "https://example.com/" + type.toLowerCase(), NOW.minusSeconds(600), type);
    }

    private static InvestmentStrategicBuyRevalidationService.Assessment passAssessment() {
        return new InvestmentStrategicBuyRevalidationService.Assessment(criterion("INTACT"),
                criterion("SUPPORTED"), criterion("OPPORTUNITY"), criterion("FAVORABLE"),
                criterion("PASS"), criterion("CLEAR"));
    }

    private static InvestmentStrategicBuyRevalidationService.Criterion criterion(String status) {
        return new InvestmentStrategicBuyRevalidationService.Criterion(status, "Evidence supports " + status + ".");
    }
}
