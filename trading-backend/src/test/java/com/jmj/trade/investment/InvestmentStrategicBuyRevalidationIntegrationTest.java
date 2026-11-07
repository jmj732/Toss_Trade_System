package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentStrategicBuyRevalidationIntegrationTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-07T16:00:00Z");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000099");

    private JdbcTemplate jdbc;
    private InvestmentContextService context;
    private InvestmentStrategicBuyRevalidationService service;
    private UUID userId;
    private UUID apiKeyId;
    private UUID decisionId;
    private UUID thesisRevisionId;
    private Instant mandateUpdatedAt;

    @BeforeEach
    void setUp() {
        freshMigratedSchema();
        var dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        userId = UUID.randomUUID();
        apiKeyId = UUID.randomUUID();
        decisionId = UUID.randomUUID();
        thesisRevisionId = UUID.randomUUID();
        mandateUpdatedAt = NOW.minusSeconds(3600);
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, CONNECTION, userId, new byte[32], new byte[12], timestamp(NOW), timestamp(NOW));
        jdbc.update("""
                INSERT INTO connector_api_keys (
                    id, user_id, connection_id, key_hash, key_prefix, scope, status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, 'ckey_test0000', 'connector:review', 'ACTIVE', ?, ?)
                """, apiKeyId, userId, CONNECTION, apiKeyId.toString().replace("-", ""),
                timestamp(NOW.minusSeconds(60)), timestamp(NOW.plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session, horizon,
                    alpha_thesis, invalidation, next_review_trigger, confidence, risk_policy_check, created_at,
                    investment_horizon
                ) VALUES (?, ?, ?, 'LUNR', 'ADD', 100, 'LIVE_REGULAR', '6-18M', 'Thesis', 'Invalidation',
                          'Review trigger', 0.8, '{}'::jsonb, ?, 'STRATEGIC')
                """, decisionId, userId, timestamp(NOW.minusSeconds(120)), timestamp(NOW.minusSeconds(120)));
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, invalidation_status, updated_at
                ) VALUES (?, 'LUNR', 'Long-term growth thesis', 'CONFIRMED', ?)
                """, userId, timestamp(mandateUpdatedAt));
        jdbc.update("""
                INSERT INTO investment_thesis_revisions (
                    id, user_id, ticker, revision, new_status, thesis_snapshot, actor_type,
                    actor_user_id, recorded_at
                ) VALUES (?, ?, 'LUNR', 3, 'CONFIRMED', '{}'::jsonb, 'USER_SESSION', ?, ?)
                """, thesisRevisionId, userId, userId, timestamp(mandateUpdatedAt));

        context = mock(InvestmentContextService.class);
        when(context.positionMandates(userId)).thenReturn(List.of(new InvestmentContextService.PositionMandateView(
                "LUNR", "STRATEGIC", "REVIEW_TRIGGER", "USER_CONFIRMED", LocalDate.parse("2026-10-27"),
                mandateUpdatedAt)));
        when(context.currentThesis(userId, "LUNR")).thenReturn(new InvestmentContextService.ThesisView(
                "LUNR", "Long-term growth thesis", "Upside", "Expectations gap", "Fundamental invalidation",
                "Revision invalidation", "Price review trigger", BigDecimal.valueOf(80), "CONFIRMED",
                "Expansion", "Exit condition", "STRATEGIC", mandateUpdatedAt, thesisRevisionId, 3L,
                "USER_SESSION", null, null, null));
        when(context.investmentBuyRiskGate(userId, "LUNR", null, null)).thenReturn(
                new InvestmentBuyRiskGate.Decision("REVIEW_REQUIRED", List.of("STRATEGIC_MARKET_REVIEW"),
                        "STRATEGIC", "OK", "IMPAIRED", "TRIGGER_BREACHED", null, NOW));
        when(context.priceFacts(userId, "LUNR")).thenReturn(new InvestmentContextService.PriceFacts(
                "OK", NOW.minusSeconds(30), BigDecimal.valueOf(100), null, null, "TOSS", "LIVE_REGULAR",
                null, null, null, null, null, null, null, null, null, null));
        service = new InvestmentStrategicBuyRevalidationService(jdbc, new ObjectMapper(), context,
                new DataSourceTransactionManager(dataSource), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesCallerClaimAsUntrustedAndRejectsItAtSubmission() {
        var input = input(UUID.randomUUID(), assessment("INTACT", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR"));

        var saved = service.record(userId, apiKeyId, input);
        var replay = service.record(userId, apiKeyId, input);
        var count = jdbc.queryForObject("""
                SELECT count(*) FROM investment_strategic_buy_revalidations
                 WHERE user_id = ? AND decision_id = ?
                """, Integer.class, userId, decisionId);

        assertThat(saved.verdict()).isEqualTo("UNTRUSTED");
        assertThat(saved.reviewOrigin()).isEqualTo("CALLER_ASSERTED");
        assertThat(saved.decisionId()).isEqualTo(decisionId);
        assertThat(saved.thesisRevisionId()).isEqualTo(thesisRevisionId);
        assertThat(saved.investmentHorizon()).isEqualTo("STRATEGIC");
        assertThat(replay.reviewId()).isEqualTo(saved.reviewId());
        assertThat(count).isEqualTo(1);

        var submission = service.validateForSubmit(userId, decisionId, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), null);
        assertThat(submission.allowed()).isFalse();
        assertThat(submission.reason()).isEqualTo("STRATEGIC_REVALIDATION_UNTRUSTED");
    }

    @Test
    void callerAssessmentIsStoredAsUntrustedEvenWhenItFails() {
        var input = input(UUID.randomUUID(), assessment("IMPAIRED", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR"));

        var saved = service.record(userId, apiKeyId, input);

        assertThat(saved.verdict()).isEqualTo("UNTRUSTED");
        assertThat(saved.assessment().path("thesisHealth").path("status").asText()).isEqualTo("IMPAIRED");
    }

    @Test
    void chatGptReviewRequiresServerRunAndCanBeConsumedOnce() {
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        var completion = new InvestmentStrategicBuyRevalidationService.RunCompletion(
                run.runId(), run.challenge(), NOW.minusSeconds(300), "ChatGPT reviewed the current thesis.",
                sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK"),
                assessment("INTACT", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR"),
                BigDecimal.valueOf(90), BigDecimal.valueOf(110), BigDecimal.valueOf(5));

        var saved = service.completeChatGptRun(userId, CONNECTION, apiKeyId, completion);
        var submission = service.validateForSubmit(userId, decisionId, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                new InvestmentBuyRiskGate.Decision("REVIEW_REQUIRED", List.of("STRATEGIC_MARKET_REVIEW"),
                        "STRATEGIC", "OK", "IMPAIRED", "TRIGGER_BREACHED", null, NOW));

        assertThat(saved.reviewId()).isEqualTo(run.runId());
        assertThat(saved.runId()).isEqualTo(run.runId());
        assertThat(saved.reviewOrigin()).isEqualTo("CHATGPT_MCP");
        assertThat(saved.provider()).isEqualTo("ChatGPT");
        assertThat(saved.verdict()).isEqualTo("PASS");
        assertThat(submission.allowed()).isTrue();
        assertThatThrownBy(() -> service.completeChatGptRun(userId, CONNECTION, apiKeyId, completion))
                .isInstanceOf(InvestmentException.class)
                .extracting(error -> ((InvestmentException) error).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);
    }

    @Test
    void rejectsChatGptReviewWithWrongRunChallenge() {
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        var completion = new InvestmentStrategicBuyRevalidationService.RunCompletion(
                run.runId(), "wrong-challenge", NOW.minusSeconds(300), "Reviewed.",
                sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK"),
                assessment("INTACT", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR"),
                BigDecimal.valueOf(90), BigDecimal.valueOf(110), BigDecimal.valueOf(5));

        assertThatThrownBy(() -> service.completeChatGptRun(userId, CONNECTION, apiKeyId, completion))
                .isInstanceOf(InvestmentException.class)
                .extracting(error -> ((InvestmentException) error).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);
    }

    @Test
    void expiredChatGptRunCannotBeCompleted() {
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        var completion = completion(run, sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK"),
                assessment("INTACT", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR"));
        var expiredService = new InvestmentStrategicBuyRevalidationService(jdbc, new ObjectMapper(), context,
                new DataSourceTransactionManager(pooledTestDataSource()),
                Clock.fixed(NOW.plusSeconds(16 * 60L), ZoneOffset.UTC));

        assertThatThrownBy(() -> expiredService.completeChatGptRun(userId, CONNECTION, apiKeyId, completion))
                .isInstanceOf(InvestmentException.class)
                .extracting(error -> ((InvestmentException) error).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);
    }

    @Test
    void changedThesisRevisionInvalidatesOpenChatGptRun() {
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        var changedRevision = UUID.randomUUID();
        when(context.currentThesis(userId, "LUNR")).thenReturn(
                new InvestmentContextService.ThesisView("LUNR", "Changed thesis", "Upside", "Expectations gap",
                        "Fundamental invalidation", "Revision invalidation", "Price review trigger",
                        BigDecimal.valueOf(80), "CONFIRMED", "Expansion", "Exit condition", "STRATEGIC",
                        mandateUpdatedAt, changedRevision, 4L, "USER_SESSION", null, null, null));

        assertThatThrownBy(() -> service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                completion(run, sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK"),
                        assessment("INTACT", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR"))))
                .isInstanceOf(InvestmentException.class)
                .extracting(error -> ((InvestmentException) error).code())
                .isEqualTo(InvestmentException.Code.INVALID_INPUT);
    }

    @Test
    void missingEvidenceTypeCannotCreatePassingChatGptReview() {
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);

        assertThatThrownBy(() -> service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                completion(run, sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS"),
                        assessment("INTACT", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR"))))
                .isInstanceOf(InvestmentException.class)
                .extracting(error -> ((InvestmentException) error).code())
                .isEqualTo(InvestmentException.Code.INVALID_INPUT);
    }

    @Test
    void notEvaluableChatGptAssessmentIsStoredAsBlocked() {
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);

        var saved = service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                completion(run, sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK"),
                        assessment("NOT_EVALUABLE", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR")));

        assertThat(saved.verdict()).isEqualTo("BLOCKED");
        var validation = service.validateForSubmit(userId, decisionId, "LUNR", BigDecimal.ONE,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                new InvestmentBuyRiskGate.Decision("REVIEW_REQUIRED", List.of("STRATEGIC_MARKET_REVIEW"),
                        "STRATEGIC", "OK", "IMPAIRED", "TRIGGER_BREACHED", null, NOW));
        assertThat(validation.allowed()).isFalse();
        assertThat(validation.reason()).isEqualTo("STRATEGIC_REVALIDATION_NOT_PASSED");
    }

    @Test
    void differentBodyCannotReuseReviewId() {
        var input = input(UUID.randomUUID(), assessment("INTACT", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR"));
        service.record(userId, apiKeyId, input);
        var changed = new InvestmentStrategicBuyRevalidationService.RevalidationInput(input.reviewId(),
                input.decisionId(), input.ticker(), input.thesisRevisionId(), input.sourceAsOf(), input.expiresAt(),
                input.provider(), input.model(), "Changed rationale", input.sources(), input.assessment(),
                input.minPrice(), input.maxPrice(), input.maxQuantity());

        assertThatThrownBy(() -> service.record(userId, apiKeyId, changed))
                .isInstanceOf(InvestmentException.class)
                .extracting(error -> ((InvestmentException) error).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);
    }

    private InvestmentStrategicBuyRevalidationService.RevalidationInput input(
            UUID reviewId, InvestmentStrategicBuyRevalidationService.Assessment assessment) {
        var sources = sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK");
        return new InvestmentStrategicBuyRevalidationService.RevalidationInput(reviewId, decisionId, "LUNR",
                thesisRevisionId, NOW.minusSeconds(300), NOW.plusSeconds(600), "provider", "model",
                "Thesis, expectations, and forward risk were reviewed.", sources, assessment,
                BigDecimal.valueOf(90), BigDecimal.valueOf(110), BigDecimal.valueOf(5));
    }

    private InvestmentStrategicBuyRevalidationService.RunCompletion completion(
            InvestmentStrategicBuyRevalidationService.ReviewRun run,
            List<InvestmentStrategicBuyRevalidationService.EvidenceSource> sources,
            InvestmentStrategicBuyRevalidationService.Assessment assessment) {
        return new InvestmentStrategicBuyRevalidationService.RunCompletion(
                run.runId(), run.challenge(), NOW.minusSeconds(300), "ChatGPT reviewed the current thesis.",
                sources, assessment, BigDecimal.valueOf(90), BigDecimal.valueOf(110), BigDecimal.valueOf(5));
    }

    private static List<InvestmentStrategicBuyRevalidationService.EvidenceSource> sources(String... types) {
        return java.util.Arrays.stream(types).map(type -> new InvestmentStrategicBuyRevalidationService.EvidenceSource(
                "https://example.com/" + type.toLowerCase(), NOW.minusSeconds(600), type)).toList();
    }

    private static InvestmentStrategicBuyRevalidationService.Assessment assessment(
            String thesis, String fundamentals, String expectations, String riskReward,
            String hardRisk, String invalidation) {
        return new InvestmentStrategicBuyRevalidationService.Assessment(
                criterion(thesis), criterion(fundamentals), criterion(expectations), criterion(riskReward),
                criterion(hardRisk), criterion(invalidation));
    }

    private static InvestmentStrategicBuyRevalidationService.Criterion criterion(String status) {
        return new InvestmentStrategicBuyRevalidationService.Criterion(status, "Reviewed evidence supports " + status);
    }

    private static java.time.OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
