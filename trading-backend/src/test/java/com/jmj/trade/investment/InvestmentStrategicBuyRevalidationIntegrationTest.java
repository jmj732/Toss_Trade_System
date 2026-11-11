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

    @Test
    void tossOnlyExternalReviewStoresStructuredEvidenceAndPassesWhenEveryCheckHolds() {
        enableExternalPolicy();
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);

        var saved = service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                externalCompletion(run, validEvidence(), passAssessment(), BigDecimal.valueOf(5)));

        assertThat(run.evidenceMode()).isEqualTo("EXTERNAL_CITED");
        assertThat(run.expiresAt()).isBeforeOrEqualTo(NOW.plusSeconds(15 * 60L));
        assertThat(saved.verdict()).isEqualTo("PASS");
        assertThat(saved.evidenceMode()).isEqualTo("EXTERNAL_CITED");
        assertThat(saved.structuredEvidence().path("fundamental").path("accessionNumber").asText())
                .isEqualTo("0000320193-26-000100");
        assertThat(saved.verdictReasons().size()).isZero();
    }

    @Test
    void externalRunRequiresStructuredEvidenceAndProvidersRunRejectsIt() {
        enableExternalPolicy();
        var external = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        assertInvalid(() -> service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                completion(external, sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK"),
                        passAssessment())));

        resetToProvidersPolicy();
        var providers = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        assertInvalid(() -> service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                externalCompletion(providers, validEvidence(), passAssessment(), BigDecimal.valueOf(5))));
    }

    @Test
    void eachStructuredEvidenceRuleRejectsTheRequestWithoutStoringAPass() {
        enableExternalPolicy();
        var cases = new java.util.LinkedHashMap<String, java.util.function.UnaryOperator<StructuredEvidenceMutation>>();
        cases.put("unsupported filing form", e -> e.withFundamental(f -> f.form("8-K")));
        cases.put("malformed accession number", e -> e.withFundamental(f -> f.accession("0000320193-26-1")));
        cases.put("non-SEC host", e -> e.withFundamental(f -> f.url("https://sec.gov.example.com/a/000032019326000100/x")));
        cases.put("SEC path without accession", e -> e.withFundamental(f -> f.url("https://www.sec.gov/Archives/other")));
        cases.put("period end after filing", e -> e.withFundamental(f -> f.periodEnd(LocalDate.parse("2026-11-06"))));
        cases.put("filing in the future", e -> e.withFundamental(f -> f.filedAt(NOW.plusSeconds(60))));
        cases.put("stale filing", e -> e.withFundamental(f -> f.filedAt(NOW.minusSeconds(211L * 86400))));
        cases.put("non-positive shares", e -> e.withFundamental(f -> f.shares(BigDecimal.ZERO)));
        cases.put("horizon not after now", e -> e.withConsensus(c -> c.horizon(LocalDate.parse("2026-11-07"))));
        cases.put("zero analysts", e -> e.withConsensus(c -> c.analysts(0)));
        cases.put("non-USD consensus", e -> e.withConsensus(c -> c.currency("EUR")));
        cases.put("stale consensus", e -> e.withConsensus(c -> c.asOf(NOW.minusSeconds(11L * 86400))));
        cases.put("consensus host outside allow-list", e -> e.withConsensus(c -> c.url("https://nasdaq.com.evil.io/x")));
        cases.put("look-alike consensus host", e -> e.withConsensus(c -> c.url("https://evilnasdaq.com/x")));
        cases.put("revision baseline too short", e -> e.withRevision(r -> r.baseline(NOW.minusSeconds(30L * 86400))));
        cases.put("revision baseline too long", e -> e.withRevision(r -> r.baseline(NOW.minusSeconds(120L * 86400))));
        cases.put("revision host outside allow-list", e -> e.withRevision(r -> r.url("https://example.com/r")));
        for (var entry : cases.entrySet()) {
            var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
            var completion = externalCompletion(run, entry.getValue().apply(validEvidence()), passAssessment(),
                    BigDecimal.valueOf(5));
            assertInvalid(() -> service.completeChatGptRun(userId, CONNECTION, apiKeyId, completion));
            assertThat(countReviewsFor(run.runId())).as(entry.getKey()).isZero();
        }
    }

    @Test
    void revisionContradictingAssessmentIsStoredAsBlocked() {
        enableExternalPolicy();
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        var evidence = validEvidence().withRevision(r -> r.eps90(BigDecimal.valueOf(-2)));

        var saved = service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                externalCompletion(run, evidence, passAssessment(), BigDecimal.valueOf(5)));

        assertThat(saved.verdict()).isEqualTo("BLOCKED");
        assertThat(saved.verdictReasons().toString()).contains("REVISION_CONTRADICTS_ASSESSMENT");
    }

    @Test
    void shareCountMismatchOrMissingTossValueIsStoredAsBlocked() {
        enableExternalPolicy();
        var mismatchRun = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        when(context.currentTossStockHazards(userId, "LUNR")).thenReturn(new InvestmentBuyRiskGate.TossStockHazards(
                "CLEAR", List.of(), BigDecimal.valueOf(2_000_000_000L)));
        var mismatch = service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                externalCompletion(mismatchRun, validEvidence(), passAssessment(), BigDecimal.valueOf(5)));

        var missingRun = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        when(context.currentTossStockHazards(userId, "LUNR")).thenReturn(
                InvestmentBuyRiskGate.TossStockHazards.unavailable());
        var missing = service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                externalCompletion(missingRun, validEvidence(), passAssessment(), BigDecimal.valueOf(5)));

        assertThat(mismatch.verdict()).isEqualTo("BLOCKED");
        assertThat(mismatch.verdictReasons().toString()).contains("EVIDENCE_IDENTITY_MISMATCH");
        assertThat(missing.verdict()).isEqualTo("BLOCKED");
        assertThat(missing.verdictReasons().toString()).contains("EVIDENCE_IDENTITY_MISMATCH");
    }

    @Test
    void externalReviewCannotStartWhenGateIsBlockedOrEvidenceModeDoesNotMatchPolicy() {
        enableExternalPolicy();
        when(context.investmentBuyRiskGate(userId, "LUNR", null, null)).thenReturn(
                new InvestmentBuyRiskGate.Decision("BLOCKED", List.of("TOSS_STOCK_HAZARD_UNAVAILABLE"),
                        "STRATEGIC", "OK", "INTACT", "CLEAR", null, NOW, "EXTERNAL_CITED"));
        assertInvalid(() -> service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId));

        when(context.investmentBuyRiskGate(userId, "LUNR", null, null)).thenReturn(
                new InvestmentBuyRiskGate.Decision("REVIEW_REQUIRED", List.of("STRATEGIC_MARKET_REVIEW"),
                        "STRATEGIC", "OK", "IMPAIRED", "TRIGGER_BREACHED", null, NOW));
        assertInvalid(() -> service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId));
    }

    @Test
    void policyFlipAfterRunStartBlocksCompletion() {
        enableExternalPolicy();
        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);
        resetToProvidersPolicy();

        assertInvalid(() -> service.completeChatGptRun(userId, CONNECTION, apiKeyId,
                externalCompletion(run, validEvidence(), passAssessment(), BigDecimal.valueOf(5))));
    }

    @Test
    void externalReviewTtlIsCappedAtConfiguredMaximum() {
        enableExternalPolicy();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "externalMaxTtl",
                java.time.Duration.ofMinutes(5));

        var run = service.beginChatGptRun(userId, CONNECTION, apiKeyId, decisionId);

        assertThat(run.expiresAt()).isEqualTo(NOW.plusSeconds(5 * 60L));
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

    private void enableExternalPolicy() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fundamentalSourcePolicy", "TOSS_ONLY");
        when(context.investmentBuyRiskGate(userId, "LUNR", null, null)).thenReturn(
                new InvestmentBuyRiskGate.Decision("REVIEW_REQUIRED", List.of("FUNDAMENTALS_EXTERNAL_EVIDENCE_REQUIRED"),
                        "STRATEGIC", "OK", "INTACT", "CLEAR", null, NOW, "EXTERNAL_CITED"));
        when(context.currentTossStockHazards(userId, "LUNR")).thenReturn(new InvestmentBuyRiskGate.TossStockHazards(
                "CLEAR", List.of(), BigDecimal.valueOf(1_000_000_000L)));
    }

    private void resetToProvidersPolicy() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fundamentalSourcePolicy", "PROVIDERS");
        when(context.investmentBuyRiskGate(userId, "LUNR", null, null)).thenReturn(
                new InvestmentBuyRiskGate.Decision("REVIEW_REQUIRED", List.of("STRATEGIC_MARKET_REVIEW"),
                        "STRATEGIC", "OK", "IMPAIRED", "TRIGGER_BREACHED", null, NOW));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(InvestmentException.class)
                .extracting(error -> ((InvestmentException) error).code())
                .isEqualTo(InvestmentException.Code.INVALID_INPUT);
    }

    private int countReviewsFor(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM investment_strategic_buy_revalidations WHERE run_id = ?",
                Integer.class, runId);
    }

    private InvestmentStrategicBuyRevalidationService.RunCompletion externalCompletion(
            InvestmentStrategicBuyRevalidationService.ReviewRun run,
            StructuredEvidenceMutation evidence,
            InvestmentStrategicBuyRevalidationService.Assessment assessment, BigDecimal maxQuantity) {
        return new InvestmentStrategicBuyRevalidationService.RunCompletion(
                run.runId(), run.challenge(), NOW.minusSeconds(300), "ChatGPT reviewed cited filings and estimates.",
                sources("THESIS", "FUNDAMENTAL", "REVISION", "EXPECTATIONS", "RISK"), assessment,
                BigDecimal.valueOf(90), BigDecimal.valueOf(110), maxQuantity, evidence.build());
    }

    private static InvestmentStrategicBuyRevalidationService.Assessment passAssessment() {
        return assessment("INTACT", "SUPPORTED", "OPPORTUNITY", "FAVORABLE", "PASS", "CLEAR");
    }

    /** Valid cited evidence for LUNR; each rule test mutates one field. */
    private static StructuredEvidenceMutation validEvidence() {
        return new StructuredEvidenceMutation()
                .withFundamental(f -> f
                        .form("10-Q").accession("0000320193-26-000100")
                        .periodEnd(LocalDate.parse("2026-09-30")).filedAt(NOW.minusSeconds(2 * 86400L))
                        .shares(BigDecimal.valueOf(1_000_000_000L))
                        .url("https://www.sec.gov/Archives/edgar/data/320193/000032019326000100/filing.htm"))
                .withConsensus(c -> c
                        .horizon(LocalDate.parse("2026-12-31")).revenue(BigDecimal.valueOf(1_000_000_000L))
                        .eps(BigDecimal.valueOf(2.5)).analysts(12).currency("USD")
                        .asOf(NOW.minusSeconds(3600)).url("https://www.nasdaq.com/market-activity/stocks/lunr/earnings"))
                .withRevision(r -> r
                        .eps90(BigDecimal.valueOf(4.5)).revenue90(BigDecimal.valueOf(6.0))
                        .asOf(NOW.minusSeconds(3600)).baseline(NOW.minusSeconds(3600 + 90 * 86400L))
                        .url("https://www.zacks.com/stock/quote/LUNR"));
    }

    /** Mutable builder for one structured-evidence payload, so each rule test changes exactly one field. */
    private static final class StructuredEvidenceMutation {
        private final FundamentalBuilder fundamental = new FundamentalBuilder();
        private final ConsensusBuilder consensus = new ConsensusBuilder();
        private final RevisionBuilder revision = new RevisionBuilder();

        StructuredEvidenceMutation withFundamental(java.util.function.UnaryOperator<FundamentalBuilder> change) {
            change.apply(fundamental);
            return this;
        }

        StructuredEvidenceMutation withConsensus(java.util.function.UnaryOperator<ConsensusBuilder> change) {
            change.apply(consensus);
            return this;
        }

        StructuredEvidenceMutation withRevision(java.util.function.UnaryOperator<RevisionBuilder> change) {
            change.apply(revision);
            return this;
        }

        InvestmentStrategicBuyRevalidationService.StructuredEvidence build() {
            return new InvestmentStrategicBuyRevalidationService.StructuredEvidence(fundamental.build(),
                    consensus.build(), revision.build());
        }
    }

    private static final class FundamentalBuilder {
        private String form = "10-Q", accession = "0000320193-26-000100", url;
        private LocalDate periodEnd = LocalDate.parse("2026-09-30");
        private Instant filedAt = NOW.minusSeconds(2 * 86400L);
        private BigDecimal shares = BigDecimal.valueOf(1_000_000_000L);

        FundamentalBuilder form(String value) { form = value; return this; }
        FundamentalBuilder accession(String value) { accession = value; return this; }
        FundamentalBuilder url(String value) { url = value; return this; }
        FundamentalBuilder periodEnd(LocalDate value) { periodEnd = value; return this; }
        FundamentalBuilder filedAt(Instant value) { filedAt = value; return this; }
        FundamentalBuilder shares(BigDecimal value) { shares = value; return this; }

        InvestmentStrategicBuyRevalidationService.FundamentalEvidence build() {
            var link = url == null ? "https://www.sec.gov/Archives/edgar/data/320193/"
                    + accession.replace("-", "") + "/filing.htm" : url;
            return new InvestmentStrategicBuyRevalidationService.FundamentalEvidence(form, accession, periodEnd,
                    filedAt, shares, link);
        }
    }

    private static final class ConsensusBuilder {
        private LocalDate horizon = LocalDate.parse("2026-12-31");
        private BigDecimal revenue = BigDecimal.valueOf(1_000_000_000L), eps = BigDecimal.valueOf(2.5);
        private Integer analysts = 12;
        private String currency = "USD";
        private Instant asOf = NOW.minusSeconds(3600);
        private String url = "https://www.nasdaq.com/market-activity/stocks/lunr/earnings";

        ConsensusBuilder horizon(LocalDate value) { horizon = value; return this; }
        ConsensusBuilder revenue(BigDecimal value) { revenue = value; return this; }
        ConsensusBuilder eps(BigDecimal value) { eps = value; return this; }
        ConsensusBuilder analysts(Integer value) { analysts = value; return this; }
        ConsensusBuilder currency(String value) { currency = value; return this; }
        ConsensusBuilder asOf(Instant value) { asOf = value; return this; }
        ConsensusBuilder url(String value) { url = value; return this; }

        InvestmentStrategicBuyRevalidationService.ConsensusEvidence build() {
            return new InvestmentStrategicBuyRevalidationService.ConsensusEvidence(horizon, revenue, eps, analysts,
                    currency, asOf, url);
        }
    }

    private static final class RevisionBuilder {
        private BigDecimal eps90 = BigDecimal.valueOf(4.5), revenue90 = BigDecimal.valueOf(6.0);
        private Instant asOf = NOW.minusSeconds(3600);
        private Instant baseline = NOW.minusSeconds(3600 + 90 * 86400L);
        private String url = "https://www.zacks.com/stock/quote/LUNR";

        RevisionBuilder eps90(BigDecimal value) { eps90 = value; return this; }
        RevisionBuilder revenue90(BigDecimal value) { revenue90 = value; return this; }
        RevisionBuilder asOf(Instant value) { asOf = value; return this; }
        RevisionBuilder baseline(Instant value) { baseline = value; return this; }
        RevisionBuilder url(String value) { url = value; return this; }

        InvestmentStrategicBuyRevalidationService.RevisionEvidence build() {
            return new InvestmentStrategicBuyRevalidationService.RevisionEvidence(eps90, revenue90, asOf, baseline, url);
        }
    }

    private static java.time.OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
