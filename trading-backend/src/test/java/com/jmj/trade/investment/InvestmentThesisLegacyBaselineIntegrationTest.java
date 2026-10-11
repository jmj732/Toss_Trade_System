package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InvestmentThesisLegacyBaselineIntegrationTest extends PostgresIntegrationTest {
    private static final UUID USER = UUID.fromString("11990000-0000-7000-8000-0000000000c1");
    private static final Instant NOW = Instant.parse("2026-10-11T16:00:00Z");
    private static final Instant OLD_UPDATED_AT = Instant.parse("2026-09-20T12:34:56Z");

    @Test
    void seedsUnknownBaselineOnlyForUnrevisionedThesesAndRequiresFreshProposalRunForVerification() throws Exception {
        var source = POSTGRES.getJdbcUrl();
        var username = POSTGRES.getUsername();
        var password = POSTGRES.getPassword();
        var v60 = Flyway.configure().dataSource(source, username, password).cleanDisabled(false)
                .target(MigrationVersion.fromVersion("60")).load();
        v60.clean();
        v60.migrate();

        var seedDataSource = pooledTestDataSource();
        var seedJdbc = new JdbcTemplate(seedDataSource);
        seedJdbc.update("INSERT INTO users(id) VALUES (?)", USER);
        seedState(seedJdbc, "AVT", "AI_PROPOSED", "Legacy caller-supplied thesis", "88.25");
        seedState(seedJdbc, "VST", "CONFIRMED", "Legacy confirmed thesis", "131.75");
        seedState(seedJdbc, "CSTM", "CONFIRMED", "Thesis with existing audit history", "45.50");
        var existingRevisionId = seedExistingRevision(seedJdbc, "CSTM");
        seedDataSource.close();

        var migrationStarted = Instant.now();
        var latest = Flyway.configure().dataSource(source, username, password).cleanDisabled(false).load();
        latest.migrate();
        var migrationFinished = Instant.now();

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("61");
        var dataSource = pooledTestDataSource();
        try {
            var jdbc = new JdbcTemplate(dataSource);
            var state = jdbc.queryForMap("""
                    SELECT core_thesis, price_risk_trigger_price, invalidation_status
                      FROM investment_thesis_states WHERE user_id = ? AND ticker = 'AVT'
                    """, USER);
            assertThat(state.get("core_thesis")).isEqualTo("Legacy caller-supplied thesis");
            assertThat((BigDecimal) state.get("price_risk_trigger_price")).isEqualByComparingTo("88.25");
            assertThat(state.get("invalidation_status")).isEqualTo("AI_PROPOSED");
            var stateUpdatedAt = jdbc.queryForObject("""
                    SELECT updated_at FROM investment_thesis_states WHERE user_id = ? AND ticker = 'AVT'
                    """, OffsetDateTime.class, USER);
            assertThat(stateUpdatedAt.toInstant()).isEqualTo(OLD_UPDATED_AT);

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ? AND ticker = 'AVT'
                    """, Integer.class, USER)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ? AND ticker = 'CSTM'
                    """, Integer.class, USER)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ? AND ticker = 'VST'
                    """, Integer.class, USER)).isEqualTo(1);
            var confirmedBaseline = jdbc.queryForMap("""
                    SELECT new_status, actor_type, actor_user_id, source_as_of
                      FROM investment_thesis_revisions WHERE user_id = ? AND ticker = 'VST'
                    """, USER);
            assertThat(confirmedBaseline.get("new_status")).isEqualTo("CONFIRMED");
            assertThat(confirmedBaseline.get("actor_type")).isEqualTo("LEGACY_BASELINE");
            assertThat(confirmedBaseline.get("actor_user_id")).isNull();
            assertThat(confirmedBaseline.get("source_as_of")).isNull();
            var existingAudit = jdbc.queryForMap("""
                    SELECT id, revision, actor_type, actor_user_id, reason
                      FROM investment_thesis_revisions WHERE user_id = ? AND ticker = 'CSTM'
                    """, USER);
            assertThat(existingAudit.get("id")).isEqualTo(existingRevisionId);
            assertThat(((Number) existingAudit.get("revision")).longValue()).isEqualTo(1L);
            assertThat(existingAudit.get("actor_type")).isEqualTo("USER_SESSION");
            assertThat(existingAudit.get("actor_user_id")).isEqualTo(USER);
            assertThat(existingAudit.get("reason")).isEqualTo("existing audit row");

            var baseline = jdbc.queryForMap("""
                    SELECT revision, previous_status, new_status, previous_trigger_price, new_trigger_price,
                           actor_type, actor_user_id, actor_session_id, source_as_of, reason, recorded_at,
                           policy_version, verification_event_id, asserted_run_id,
                           thesis_snapshot->>'coreThesis' AS snapshot_core_thesis,
                           thesis_snapshot->>'upsideDriver' AS snapshot_upside_driver,
                           thesis_snapshot->>'expectationsGap' AS snapshot_expectations_gap,
                           thesis_snapshot->>'fundamentalInvalidation' AS snapshot_fundamental_invalidation,
                           thesis_snapshot->>'revisionInvalidation' AS snapshot_revision_invalidation,
                           thesis_snapshot->>'priceRiskTrigger' AS snapshot_price_risk_trigger,
                           thesis_snapshot->>'priceRiskTriggerPrice' AS snapshot_price_risk_trigger_price,
                           thesis_snapshot->>'invalidationStatus' AS snapshot_status,
                           thesis_snapshot->>'expandTrigger' AS snapshot_expand_trigger,
                           thesis_snapshot->>'exitOrDiscardTrigger' AS snapshot_exit_trigger,
                           thesis_snapshot->>'classification' AS snapshot_classification,
                           thesis_snapshot->>'updatedAt' AS snapshot_updated_at
                      FROM investment_thesis_revisions WHERE user_id = ? AND ticker = 'AVT'
                    """, USER);
            assertThat(((Number) baseline.get("revision")).longValue()).isEqualTo(1L);
            assertThat(baseline.get("previous_status")).isNull();
            assertThat(baseline.get("new_status")).isEqualTo("AI_PROPOSED");
            assertThat(baseline.get("previous_trigger_price")).isNull();
            assertThat((BigDecimal) baseline.get("new_trigger_price")).isEqualByComparingTo("88.25");
            assertThat(baseline.get("actor_type")).isEqualTo("LEGACY_BASELINE");
            assertThat(baseline.get("actor_user_id")).isNull();
            assertThat(baseline.get("actor_session_id")).isNull();
            assertThat(baseline.get("source_as_of")).isNull();
            assertThat(baseline.get("policy_version")).isNull();
            assertThat(baseline.get("verification_event_id")).isNull();
            assertThat(baseline.get("asserted_run_id")).isNull();
            assertThat(baseline.get("reason").toString()).contains("LEGACY_BASELINE");
            var recordedAt = jdbc.queryForObject("""
                    SELECT recorded_at FROM investment_thesis_revisions WHERE user_id = ? AND ticker = 'AVT'
                    """, OffsetDateTime.class, USER);
            assertThat(recordedAt.toInstant()).isBetween(migrationStarted.minusSeconds(1), migrationFinished.plusSeconds(1));
            assertThat(baseline.get("snapshot_core_thesis")).isEqualTo("Legacy caller-supplied thesis");
            assertThat(baseline.get("snapshot_upside_driver")).isEqualTo("upside");
            assertThat(baseline.get("snapshot_expectations_gap")).isEqualTo("gap");
            assertThat(baseline.get("snapshot_fundamental_invalidation")).isEqualTo("fundamental");
            assertThat(baseline.get("snapshot_revision_invalidation")).isEqualTo("revision");
            assertThat(baseline.get("snapshot_price_risk_trigger")).isEqualTo("price trigger");
            assertThat(new BigDecimal(baseline.get("snapshot_price_risk_trigger_price").toString()))
                    .isEqualByComparingTo("88.25");
            assertThat(baseline.get("snapshot_status")).isEqualTo("AI_PROPOSED");
            assertThat(baseline.get("snapshot_expand_trigger")).isEqualTo("expand");
            assertThat(baseline.get("snapshot_exit_trigger")).isEqualTo("exit");
            assertThat(baseline.get("snapshot_classification")).isEqualTo("CORE");
            assertThat(OffsetDateTime.parse(baseline.get("snapshot_updated_at").toString()).toInstant())
                    .isEqualTo(OLD_UPDATED_AT);

            var transactionManager = new DataSourceTransactionManager(dataSource);
            var context = context(jdbc, transactionManager);
            var verifier = new InvestmentThesisVerificationService(jdbc, transactionManager, context,
                    new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), true, true, USER.toString(),
                    "v1", Duration.ofHours(24), 2, new BigDecimal("0.25"));

            var legacyView = context.currentThesis(USER, "AVT");
            assertThat(legacyView.invalidationStatus()).isEqualTo("AI_PROPOSED");
            assertThat(legacyView.revision()).isEqualTo(1L);
            assertThat(legacyView.approvalActorType()).isEqualTo("LEGACY_BASELINE");
            assertThat(legacyView.assertedRunId()).isNull();
            var verificationContext = verifier.verificationContext(USER, "AVT");
            assertThat(verificationContext.thesis().revisionId()).isEqualTo(legacyView.revisionId());
            assertThat(verificationContext.thesis().approvalActorType()).isEqualTo("LEGACY_BASELINE");

            var legacyAttempt = verifier.verify(USER, null,
                    input(UUID.randomUUID(), legacyView.revisionId(), "legacy-verification-run"));
            assertThat(legacyAttempt.outcome()).isEqualTo("BLOCKED");
            assertThat(legacyAttempt.reasonCode()).isEqualTo("PROPOSAL_RUN_ID_MISSING");
            assertThat(legacyAttempt.approvedThesis()).isNull();

            var freshProposal = context.putThesisProposal(USER, "AVT", thesisInput(), legacyView.updatedAt(),
                    "fresh-proposal-run");
            assertThat(freshProposal.revision()).isEqualTo(2L);
            assertThat(freshProposal.assertedRunId()).isEqualTo("fresh-proposal-run");
            var replayedSameRun = verifier.verify(USER, null,
                    input(UUID.randomUUID(), freshProposal.revisionId(), "fresh-proposal-run"));
            assertThat(replayedSameRun.outcome()).isEqualTo("BLOCKED");
            assertThat(replayedSameRun.reasonCode()).isEqualTo("PROPOSAL_AND_VERIFICATION_RUN_MATCH");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ? AND ticker = 'AVT'
                    """, Integer.class, USER)).isEqualTo(2);
        } finally {
            dataSource.close();
        }
    }

    private static void seedState(JdbcTemplate jdbc, String ticker, String status, String thesis, String trigger) {
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, upside_driver, expectations_gap, fundamental_invalidation,
                    revision_invalidation, price_risk_trigger, price_risk_trigger_price, invalidation_status,
                    expand_trigger, exit_or_discard_trigger, classification, updated_at
                ) VALUES (?, ?, ?, 'upside', 'gap', 'fundamental', 'revision', 'price trigger', ?, ?,
                          'expand', 'exit', 'CORE', ?)
                """, USER, ticker, thesis, new BigDecimal(trigger), status,
                OffsetDateTime.ofInstant(OLD_UPDATED_AT, ZoneOffset.UTC));
    }

    private static UUID seedExistingRevision(JdbcTemplate jdbc, String ticker) {
        var revisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO investment_thesis_revisions (
                    id, user_id, ticker, revision, previous_status, new_status, previous_trigger_price,
                    new_trigger_price, thesis_snapshot, actor_type, actor_user_id, actor_session_id,
                    source_as_of, reason, recorded_at, policy_version, verification_event_id, asserted_run_id
                ) VALUES (?, ?, ?, 1, NULL, 'CONFIRMED', NULL, 45.50, '{}'::jsonb,
                          'USER_SESSION', ?, NULL, NULL, 'existing audit row', ?, NULL, NULL, NULL)
                """, revisionId, USER, ticker, USER, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return revisionId;
    }

    private static InvestmentContextService context(JdbcTemplate jdbc, DataSourceTransactionManager transactionManager) {
        return new InvestmentContextService(jdbc, new ObjectMapper(), transactionManager,
                new StockDataProviderRegistry(List.of()), null, mock(PortfolioReadService.class),
                mock(MonitoringWatchlistService.class), mock(RiskPolicyService.class),
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
    }

    private static InvestmentContextService.ThesisInput thesisInput() {
        return new InvestmentContextService.ThesisInput("Fresh proposal after legacy baseline", null, null,
                null, null, null, null, "AI_PROPOSED", null, null, null);
    }

    private static InvestmentThesisVerificationService.VerificationInput input(
            UUID eventId, UUID revisionId, String verificationRunId) {
        return new InvestmentThesisVerificationService.VerificationInput(eventId, "AVT", revisionId,
                verificationRunId, "external-provider", "external-model", "PASS", NOW.minusSeconds(60),
                List.of("https://example.com/source-a", "https://example.org/source-b"),
                "External verification assertion", "External counterevidence assertion", null);
    }
}
