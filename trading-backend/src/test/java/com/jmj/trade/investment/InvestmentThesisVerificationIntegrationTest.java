package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InvestmentThesisVerificationIntegrationTest extends PostgresIntegrationTest {
    private static final UUID USER = UUID.fromString("11990000-0000-7000-8000-0000000000b1");
    private static final UUID OTHER_USER = UUID.fromString("11990000-0000-7000-8000-0000000000b2");
    private static final UUID API_KEY = UUID.fromString("22990000-0000-7000-8000-0000000000b1");
    private static final UUID OTHER_API_KEY = UUID.fromString("22990000-0000-7000-8000-0000000000b2");
    private static final Instant NOW = Instant.parse("2026-10-11T16:00:00Z");
    private static final Instant SOURCE_AS_OF = Instant.parse("2026-10-11T15:00:00Z");
    private static final String TICKER = "AAPL";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private InvestmentContextService context;

    @BeforeEach
    void setUp() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.update("INSERT INTO users(id) VALUES (?), (?)", USER, OTHER_USER);
        context = new InvestmentContextService(jdbc, mapper, transactionManager,
                new StockDataProviderRegistry(List.of()), null, mock(PortfolioReadService.class),
                mock(MonitoringWatchlistService.class), mock(RiskPolicyService.class),
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
        ReflectionTestUtils.setField(context, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void closeTestDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void passingVerificationCommitsDeferredProvenanceAndReplaysNormalizedTrigger() throws Exception {
        var verifier = verifier();
        var proposal = propose(USER, "proposal-run-17");
        insertUsableQuote(USER);
        verifier.updatePolicy(USER, new InvestmentThesisVerificationService.PolicyInput(true, null));
        var eventId = UUID.randomUUID();

        var first = verifier.verify(USER, API_KEY, input(eventId, proposal.revisionId(),
                "verification-run-18", "PASS", new BigDecimal("90")));
        var replay = verifier.verify(USER, API_KEY, input(eventId, proposal.revisionId(),
                "verification-run-18", "PASS", new BigDecimal("90.0")));
        assertThatThrownBy(() -> verifier.verify(USER, API_KEY, input(eventId, proposal.revisionId(),
                "verification-run-18", "PASS", new BigDecimal("91"))))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);

        assertThat(first.outcome()).isEqualTo("AUTO_APPROVED");
        assertThat(first.reasonCode()).isEqualTo("AUTO_APPROVAL_POLICY_PASSED");
        assertThat(first.approvedThesis()).isNotNull();
        assertThat(first.approvedThesis().invalidationStatus()).isEqualTo("CONFIRMED");
        assertThat(first.approvedThesis().priceRiskTriggerPrice()).isEqualByComparingTo("90");
        assertThat(first.authenticatedActorUserId()).isEqualTo(USER);
        assertThat(first.authenticatedActorKeyId()).isEqualTo(API_KEY);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_ai_verification_events", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ?", Integer.class, USER))
                .isEqualTo(2);
        var event = jdbc.queryForMap("""
                SELECT proposal_revision_id, proposal_run_id, verification_run_id, selected_trigger_price,
                       authenticated_actor_user_id, authenticated_actor_key_id, outcome
                  FROM investment_thesis_ai_verification_events WHERE verification_event_id = ?
                """, eventId);
        assertThat(event.get("proposal_revision_id")).isEqualTo(proposal.revisionId());
        assertThat(event.get("proposal_run_id")).isEqualTo("proposal-run-17");
        assertThat(event.get("verification_run_id")).isEqualTo("verification-run-18");
        assertThat((BigDecimal) event.get("selected_trigger_price")).isEqualByComparingTo("90");
        assertThat(event.get("authenticated_actor_user_id")).isEqualTo(USER);
        assertThat(event.get("authenticated_actor_key_id")).isEqualTo(API_KEY);
        assertThat(event.get("outcome")).isEqualTo("AUTO_APPROVED");
        var revision = jdbc.queryForMap("""
                SELECT actor_type, policy_version, verification_event_id, source_as_of
                  FROM investment_thesis_revisions WHERE user_id = ? AND ticker = ? AND revision = 2
                """, USER, TICKER);
        assertThat(revision.get("actor_type")).isEqualTo("AI_POLICY");
        assertThat(revision.get("policy_version")).isEqualTo("v1");
        assertThat(revision.get("verification_event_id")).isEqualTo(eventId);
        assertThat(((Timestamp) revision.get("source_as_of")).toInstant()).isEqualTo(SOURCE_AS_OF);

        assertAppendOnly("investment_thesis_ai_verification_events", "verification_event_id", eventId);
        assertAppendOnly("investment_thesis_ai_policy_revisions", "user_id", USER);
    }

    @Test
    void deploymentPolicyIsSheetOwnerScopedAndStoredPolicyOverridesWithRevisionCas() throws Exception {
        var verifier = verifier();
        var proposal = propose(USER, "policy-scope-proposal");
        insertUsableQuote(USER);

        assertThat(verifier.policy(USER).enabled()).isTrue();
        assertThat(verifier.policy(USER).source()).isEqualTo("DEPLOYMENT");
        assertThat(verifier.policy(OTHER_USER).enabled()).isFalse();
        assertThat(verifier.policy(OTHER_USER).source()).isEqualTo("DEFAULT_DISABLED");

        var enabled = verifier.updatePolicy(USER,
                new InvestmentThesisVerificationService.PolicyInput(true, null));
        var disabled = verifier.updatePolicy(USER,
                new InvestmentThesisVerificationService.PolicyInput(false, enabled.revisionId()));

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.source()).isEqualTo("USER");
        assertThat(verifier.policy(USER).enabled()).isFalse();
        assertThatThrownBy(() -> verifier.updatePolicy(USER,
                new InvestmentThesisVerificationService.PolicyInput(true, enabled.revisionId())))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);
        assertThat(verifier.policy(USER).revisionId()).isEqualTo(disabled.revisionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_ai_policy_revisions", Integer.class))
                .isEqualTo(2);
        var pass = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "policy-scope-verification", "PASS", new BigDecimal("90")));
        assertBlocked(pass, "AI_POLICY_DISABLED");
    }

    @Test
    void staleAndInsufficientEvidenceAndUnsafeTriggersPersistBlockedResults() throws Exception {
        var verifier = verifier();
        var proposal = propose(USER, "proposal-run-gates");
        insertUsableQuote(USER);
        verifier.updatePolicy(USER, new InvestmentThesisVerificationService.PolicyInput(true, null));

        var stale = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verify-stale", "PASS", new BigDecimal("90"), SOURCE_AS_OF.minus(Duration.ofDays(2)),
                List.of("https://example.com/source-a", "https://example.org/source-b")));
        var future = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verify-future", "PASS", new BigDecimal("90"), NOW.plusSeconds(1),
                List.of("https://example.com/source-a", "https://example.org/source-b")));
        var insufficient = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verify-one-source", "PASS", new BigDecimal("90"), SOURCE_AS_OF,
                List.of("https://example.com/source-a")));
        var tooFar = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verify-too-far", "PASS", new BigDecimal("60")));
        var abovePrice = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verify-above-price", "PASS", new BigDecimal("101")));

        assertBlocked(stale, "VERIFICATION_EVIDENCE_STALE");
        assertBlocked(future, "VERIFICATION_EVIDENCE_STALE");
        assertBlocked(insufficient, "VERIFICATION_SOURCES_INSUFFICIENT");
        assertBlocked(tooFar, "TRIGGER_DISTANCE_EXCEEDS_POLICY");
        assertBlocked(abovePrice, "TRIGGER_NOT_BELOW_TRUSTED_PRICE");
        assertThat(jdbc.queryForObject("SELECT invalidation_status FROM investment_thesis_states WHERE user_id = ? AND ticker = ?",
                String.class, USER, TICKER)).isEqualTo("AI_PROPOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_ai_verification_events", Integer.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ?", Integer.class, USER))
                .isEqualTo(1);
    }

    @Test
    void missingTriggerBlocksPassButRejectWithNullTriggerIsPersistedAsRejected() throws Exception {
        var verifier = verifier();
        var proposal = propose(USER, "proposal-run-21");
        insertUsableQuote(USER);
        verifier.updatePolicy(USER, new InvestmentThesisVerificationService.PolicyInput(true, null));

        var blocked = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verification-run-22", "PASS", null));
        var rejected = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verification-run-23", "REJECT", null));

        assertThat(blocked.outcome()).isEqualTo("BLOCKED");
        assertThat(blocked.reasonCode()).isEqualTo("MISSING_TRIGGER_PRICE");
        assertThat(blocked.approvedThesis()).isNull();
        assertThat(rejected.outcome()).isEqualTo("REJECTED");
        assertThat(rejected.reasonCode()).isEqualTo("EXTERNAL_VERIFICATION_REJECTED");
        assertThat(rejected.approvedThesis()).isNull();
        assertThat(jdbc.queryForObject("SELECT invalidation_status FROM investment_thesis_states WHERE user_id = ? AND ticker = ?",
                String.class, USER, TICKER)).isEqualTo("AI_PROPOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ?", Integer.class, USER))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_ai_verification_events", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void deploymentDefaultDisabledPersistsBlockedPass() throws Exception {
        var verifier = verifier(false);
        var proposal = propose(USER, "proposal-run-31");
        insertUsableQuote(USER);

        var result = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verification-run-32", "PASS", new BigDecimal("90")));

        assertThat(verifier.policy(USER).enabled()).isFalse();
        assertThat(verifier.policy(USER).source()).isEqualTo("DEFAULT_DISABLED");
        assertThat(result.outcome()).isEqualTo("BLOCKED");
        assertThat(result.reasonCode()).isEqualTo("AI_POLICY_DISABLED");
        assertThat(jdbc.queryForObject("SELECT invalidation_status FROM investment_thesis_states WHERE user_id = ? AND ticker = ?",
                String.class, USER, TICKER)).isEqualTo("AI_PROPOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_ai_policy_revisions", Integer.class)).isZero();
    }

    @Test
    void staleRevisionAndReusedProposalRunAreBlockedAgainstTheirOwnRevisionProvenance() throws Exception {
        var verifier = verifier();
        var original = propose(USER, "proposal-run-old");
        var current = context.putThesisProposal(USER, TICKER, thesisInput("AI_PROPOSED"), original.updatedAt(),
                "proposal-run-current");
        insertUsableQuote(USER);
        verifier.updatePolicy(USER, new InvestmentThesisVerificationService.PolicyInput(true, null));

        var stale = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), original.revisionId(),
                "verification-run-new", "PASS", new BigDecimal("90")));
        var sameRun = verifier.verify(USER, API_KEY, input(UUID.randomUUID(), current.revisionId(),
                "proposal-run-current", "PASS", new BigDecimal("90")));

        assertThat(stale.outcome()).isEqualTo("BLOCKED");
        assertThat(stale.reasonCode()).isEqualTo("STALE_PROPOSAL_REVISION");
        assertThat(sameRun.outcome()).isEqualTo("BLOCKED");
        assertThat(sameRun.reasonCode()).isEqualTo("PROPOSAL_AND_VERIFICATION_RUN_MATCH");
        assertThat(jdbc.queryForObject("""
                SELECT proposal_run_id FROM investment_thesis_ai_verification_events
                 WHERE verification_event_id = ?
                """, String.class, stale.verificationEventId())).isEqualTo("proposal-run-old");
        assertThat(jdbc.queryForObject("""
                SELECT proposal_run_id FROM investment_thesis_ai_verification_events
                 WHERE verification_event_id = ?
                """, String.class, sameRun.verificationEventId())).isEqualTo("proposal-run-current");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ?", Integer.class, USER))
                .isEqualTo(2);
    }

    @Test
    void verificationEventAndProposalRevisionAreOwnerScoped() {
        var verifier = verifier();
        var userProposal = propose(USER, "user-proposal-run");
        var otherProposal = context.putThesisProposal(OTHER_USER, TICKER, thesisInput("AI_PROPOSED"), null,
                "other-proposal-run");
        var userEventId = UUID.randomUUID();
        var userEvent = verifier.verify(USER, API_KEY, input(userEventId, userProposal.revisionId(),
                "user-verification-run", "REJECT", null));

        assertThat(userEvent.outcome()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> verifier.verify(OTHER_USER, OTHER_API_KEY,
                input(userEventId, otherProposal.revisionId(), "other-verification-run", "REJECT", null)))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.CONFLICT);
        assertThatThrownBy(() -> verifier.verify(OTHER_USER, OTHER_API_KEY,
                input(UUID.randomUUID(), userProposal.revisionId(), "other-verification-run", "REJECT", null)))
                .isInstanceOf(InvestmentException.class)
                .extracting(exception -> ((InvestmentException) exception).code())
                .isEqualTo(InvestmentException.Code.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_ai_verification_events", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedVerificationEventInsertRollsBackConfirmedThesisRevision() throws Exception {
        var verifier = verifier();
        var proposal = propose(USER, "proposal-run-41");
        insertUsableQuote(USER);
        verifier.updatePolicy(USER, new InvestmentThesisVerificationService.PolicyInput(true, null));
        jdbc.execute("""
                CREATE FUNCTION reject_test_verification_event() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test verification event insert failure'; END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_test_verification_event_before_insert
                BEFORE INSERT ON investment_thesis_ai_verification_events
                FOR EACH ROW EXECUTE FUNCTION reject_test_verification_event()
                """);

        assertThatThrownBy(() -> verifier.verify(USER, API_KEY, input(UUID.randomUUID(), proposal.revisionId(),
                "verification-run-42", "PASS", new BigDecimal("90"))))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT invalidation_status FROM investment_thesis_states WHERE user_id = ? AND ticker = ?",
                String.class, USER, TICKER)).isEqualTo("AI_PROPOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ?", Integer.class, USER))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_ai_verification_events", Integer.class)).isZero();
    }

    private InvestmentThesisVerificationService verifier() {
        return verifier(true);
    }

    private InvestmentThesisVerificationService verifier(boolean deploymentPolicyEnabled) {
        return new InvestmentThesisVerificationService(jdbc, transactionManager, context, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), deploymentPolicyEnabled, true, USER.toString(),
                "v1", Duration.ofHours(24), 2, new BigDecimal("0.25"));
    }

    private InvestmentContextService.ThesisView propose(UUID userId, String proposalRunId) {
        return context.putThesisProposal(userId, TICKER, thesisInput("AI_PROPOSED"), null, proposalRunId);
    }

    private void insertUsableQuote(UUID userId) throws Exception {
        var payload = mapper.createObjectNode().put("asOf", NOW.toString());
        payload.putObject("price")
                .put("latestPrice", 100)
                .put("latestPriceAsOf", NOW.minusSeconds(30).toString())
                .put("source", "TOSS")
                .put("session", "LIVE_REGULAR")
                .put("status", "OK");
        var timestamp = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), userId, TICKER, timestamp, mapper.writeValueAsString(payload), timestamp);
    }

    private static InvestmentContextService.ThesisInput thesisInput(String status) {
        return new InvestmentContextService.ThesisInput("Caller supplied proposal", null, null,
                null, null, null, null, status, null, null, null);
    }

    private static InvestmentThesisVerificationService.VerificationInput input(
            UUID eventId, UUID revisionId, String verificationRunId, String verdict, BigDecimal selectedTriggerPrice) {
        return input(eventId, revisionId, verificationRunId, verdict, selectedTriggerPrice, SOURCE_AS_OF,
                List.of("https://example.com/source-a", "https://example.org/source-b"));
    }

    private static InvestmentThesisVerificationService.VerificationInput input(
            UUID eventId, UUID revisionId, String verificationRunId, String verdict, BigDecimal selectedTriggerPrice,
            Instant sourceAsOf, List<String> sourceUrls) {
        return new InvestmentThesisVerificationService.VerificationInput(eventId, TICKER, revisionId,
                verificationRunId, "external-provider", "external-model", verdict, sourceAsOf, sourceUrls,
                "Caller supplied rationale", "Caller supplied counterevidence", selectedTriggerPrice);
    }

    private static void assertBlocked(InvestmentThesisVerificationService.VerificationResult result, String reason) {
        assertThat(result.outcome()).isEqualTo("BLOCKED");
        assertThat(result.reasonCode()).isEqualTo(reason);
        assertThat(result.approvedThesis()).isNull();
    }

    private void assertAppendOnly(String table, String keyColumn, UUID key) {
        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET created_at = created_at WHERE "
                + keyColumn + " = ?", key))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table + " WHERE " + keyColumn + " = ?", key))
                .hasMessageContaining("append-only");
    }
}
