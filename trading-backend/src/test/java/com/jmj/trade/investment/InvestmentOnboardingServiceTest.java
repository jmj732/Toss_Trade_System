package com.jmj.trade.investment;

import com.jmj.trade.monitoring.MonitoringWatchlistService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InvestmentOnboardingServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void unknownTickerReturnsMissingReadinessWithoutCandidateReads() {
        var watchlist = mock(MonitoringWatchlistService.class);
        var context = mock(InvestmentContextService.class);
        var candidates = mock(ThesisCandidateGenerator.class);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        when(context.context(USER_ID)).thenReturn(new InvestmentContextService.ContextView(
                null, List.of(), List.of(), null, List.of(),
                new InvestmentContextService.PipelineView("NEVER_RUN", null, null, null)));

        var service = new InvestmentOnboardingService(watchlist, context, candidates);
        var result = service.readiness(USER_ID, "newt");

        assertThat(result.ticker()).isEqualTo("NEWT");
        assertThat(result.registered()).isFalse();
        assertThat(result.status()).isEqualTo("DATA_MISSING");
        assertThat(result.dataStatuses()).containsValues("DATA_MISSING");
        assertThat(result.dataAsOf()).isEmpty();
        assertThat(result.thesis().eligibilityReasons()).isEmpty();
        assertThat(result.blockers()).contains("SECURITY_DATA_NOT_CAPTURED");
        verify(candidates, never()).candidates(USER_ID, "NEWT");
    }

    @Test
    void heldTickerUsesStoredReadinessAndKeepsReceiptSeparateFromUserConfirmation() throws Exception {
        var watchlist = mock(MonitoringWatchlistService.class);
        var context = mock(InvestmentContextService.class);
        var candidates = mock(ThesisCandidateGenerator.class);
        var asOf = Instant.parse("2026-10-10T20:00:00Z");
        var readiness = new ObjectMapper().createObjectNode();
        readiness.put("priceStatus", "OK");
        readiness.put("trendStatus", "OK");
        readiness.put("fundamentalStatus", "OK");
        readiness.put("consensusStatus", "OK");
        readiness.put("revisionStatus", "OK");
        readiness.put("valuationStatus", "OK");
        readiness.put("balanceSheetStatus", "OK");
        readiness.put("riskStatus", "OK");
        readiness.put("overallDataStatus", "OK");
        readiness.set("missingFields", new ObjectMapper().createArrayNode());
        var thesis = new InvestmentContextService.ThesisView(
                "NEWT", "private thesis text", null, null, null, null, null,
                new BigDecimal("80.25"), "CONFIRMED", null, null, null, asOf,
                UUID.randomUUID(), 4L, "USER_SESSION", null, null, null);
        var riskMark = new InvestmentContextService.RiskMarkView(new BigDecimal("99.99"), asOf,
                "TOSS", "QUOTE", "QUOTE_AS_OF", InvestmentDataCalculator.DataStatus.OK, null);
        var risk = new InvestmentContextService.RiskContributionView(null, null, null,
                InvestmentDataCalculator.DataStatus.OK, null, InvestmentDataCalculator.DataStatus.OK,
                null, null, null, InvestmentDataCalculator.DataStatus.OK, false, null,
                List.of("POSITION_WEIGHT_MISSING"), riskMark);
        var security = new InvestmentContextService.SecurityView("NEWT", null, asOf,
                new ObjectMapper().createObjectNode().put("latestPrice", "99.99")
                        .put("latestPriceAsOf", asOf.toString()), null, null, null,
                new ObjectMapper().createObjectNode().put("status", "OK"), null,
                readiness, thesis, risk);
        var pipeline = new InvestmentContextService.PipelineView("SUCCEEDED", asOf, asOf, null);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        when(context.context(USER_ID)).thenReturn(new InvestmentContextService.ContextView(
                null, List.of(security), List.of(), null, List.of(), pipeline));
        when(candidates.candidates(USER_ID, "NEWT")).thenReturn(Map.of(
                ThesisCandidateGenerator.COMPUTED_ATR,
                new ThesisCandidateGenerator.Candidate(ThesisCandidateGenerator.COMPUTED_ATR,
                        "OK", null, new BigDecimal("80.25"), asOf, Map.of("period", 14, "atr", "3.2"))));

        var result = new InvestmentOnboardingService(watchlist, context, candidates).readiness(USER_ID, "newt");
        var json = new ObjectMapper().writeValueAsString(result);

        assertThat(result.registered()).isFalse();
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.dataStatuses()).containsEntry("revision", "OK");
        assertThat(result.dataAsOf()).containsEntry("price", asOf);
        assertThat(result.thesis().revision()).isEqualTo(4L);
        assertThat(result.thesis().invalidationStatus()).isEqualTo("CONFIRMED");
        assertThat(result.thesis().receiptStatus()).isEqualTo("USER_AUTHORED_NO_AI_RECEIPT");
        assertThat(result.riskMark().status()).isEqualTo("OK");
        assertThat(result.thesis().triggerConfigured()).isTrue();
        assertThat(result.thesis().sizingEligible()).isFalse();
        assertThat(result.thesis().eligibilityReasons()).containsExactly("POSITION_WEIGHT_MISSING");
        assertThat(result.blockers()).contains("POSITION_WEIGHT_MISSING", "SIZING_INELIGIBLE");
        assertThat(result.riskMark().asOf()).isEqualTo(asOf);
        assertThat(result.riskMark().source()).isEqualTo("TOSS");
        assertThat(result.riskMark().basis()).isEqualTo("QUOTE");
        assertThat(result.riskMark().asOfBasis()).isEqualTo("QUOTE_AS_OF");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.trigger()).isEqualByComparingTo("80.25");
            assertThat(candidate.sourceAsOf()).isEqualTo(asOf);
            assertThat(candidate.inputs()).containsEntry("period", 14).containsEntry("atr", "3.2");
        });
        assertThat(json).contains("\"value\":99.99", "\"trigger\":80.25", "COMPUTED_ATR")
                .doesNotContain("private thesis text", "revisionId", "verificationEventId", "assertedRunId");
    }

    @Test
    void linkedApprovedReceiptStaysCurrentWhenLatestVerificationIsBlocked() {
        var watchlist = mock(MonitoringWatchlistService.class);
        var context = mock(InvestmentContextService.class);
        var candidates = mock(ThesisCandidateGenerator.class);
        var verification = mock(InvestmentThesisVerificationService.class);
        var eventId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        var asOf = Instant.parse("2026-10-10T20:00:00Z");
        var security = readySecurity("CONFIRMED", "AI_POLICY", eventId, revisionId, null, asOf);
        var laterBlocked = new InvestmentThesisVerificationService.VerificationResult(
                UUID.randomUUID(), "NEWT", revisionId, "BLOCKED", "EVIDENCE_STALE", "UNKNOWN", "v1",
                USER_ID, null, asOf, null);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        when(context.context(USER_ID)).thenReturn(readyContext(security, asOf));
        when(candidates.candidates(USER_ID, "NEWT")).thenReturn(Map.of());
        when(verification.verificationReceipt(USER_ID, "NEWT", eventId))
                .thenReturn(new InvestmentThesisVerificationService.VerificationReceipt(
                        eventId, revisionId, "AUTO_APPROVED", null, "v1", revisionId, 4L));
        when(verification.verificationContext(USER_ID, "NEWT"))
                .thenReturn(new InvestmentThesisVerificationService.VerificationContextView(
                        null, security.thesis(), null, laterBlocked, List.of()));

        var result = new InvestmentOnboardingService(watchlist, context, candidates, verification)
                .readiness(USER_ID, "NEWT");

        assertThat(result.thesis().receiptStatus()).isEqualTo("AI_RECEIPT_CURRENT_REVISION");
        assertThat(result.thesis().verificationOutcome()).isEqualTo("AUTO_APPROVED");
        assertThat(result.thesis().receiptMatchesCurrentRevision()).isTrue();
        assertThat(result.blockers()).contains("POSITION_WEIGHT_MISSING", "SIZING_INELIGIBLE")
                .doesNotContain("AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED");
        verify(verification).verificationReceipt(USER_ID, "NEWT", eventId);
        verify(verification, never()).verificationContext(USER_ID, "NEWT");
    }

    @Test
    void linkedReceiptMustMatchApprovedRevisionNumberAndPolicyVersion() {
        var eventId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        var asOf = Instant.parse("2026-10-10T20:00:00Z");

        var wrongApprovedRevision = readinessWithReceipt(new InvestmentThesisVerificationService.VerificationReceipt(
                eventId, UUID.randomUUID(), "AUTO_APPROVED", null, "v1", revisionId, 3L),
                eventId, revisionId, asOf);
        var wrongPolicyVersion = readinessWithReceipt(new InvestmentThesisVerificationService.VerificationReceipt(
                eventId, UUID.randomUUID(), "AUTO_APPROVED", null, "v2", revisionId, 4L),
                eventId, revisionId, asOf);

        assertThat(wrongApprovedRevision.status()).isEqualTo("BLOCKED");
        assertThat(wrongApprovedRevision.thesis().receiptMatchesCurrentRevision()).isFalse();
        assertThat(wrongApprovedRevision.blockers())
                .contains("AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED", "SIZING_INELIGIBLE",
                        "POSITION_WEIGHT_MISSING");
        assertThat(wrongPolicyVersion.status()).isEqualTo("BLOCKED");
        assertThat(wrongPolicyVersion.thesis().receiptMatchesCurrentRevision()).isFalse();
        assertThat(wrongPolicyVersion.blockers())
                .contains("AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED");
    }

    @Test
    void aiProposalRunIdWithoutPersistedVerificationReceiptIsNotVerified() {
        var watchlist = mock(MonitoringWatchlistService.class);
        var context = mock(InvestmentContextService.class);
        var candidates = mock(ThesisCandidateGenerator.class);
        var verification = mock(InvestmentThesisVerificationService.class);
        var asOf = Instant.parse("2026-10-10T20:00:00Z");
        var security = readySecurity("AI_PROPOSED", "AI_POLICY", null, UUID.randomUUID(), "proposal-run-1", asOf);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        when(context.context(USER_ID)).thenReturn(readyContext(security, asOf));
        when(candidates.candidates(USER_ID, "NEWT")).thenReturn(Map.of());

        var result = new InvestmentOnboardingService(watchlist, context, candidates, verification)
                .readiness(USER_ID, "NEWT");

        assertThat(result.thesis().receiptStatus()).isEqualTo("AI_PROPOSED_NO_VERIFICATION_RECEIPT");
        assertThat(result.thesis().verificationOutcome()).isEqualTo("NOT_VERIFIED");
        assertThat(result.thesis().receiptMatchesCurrentRevision()).isFalse();
        assertThat(result.blockers()).contains("INVALIDATION_NOT_CONFIRMED");
        verify(verification, never()).verificationReceipt(USER_ID, "NEWT", null);
    }

    @Test
    void registrationDelegatesToWatchlistAndDoesNotRunCollectionOrOrderPipelines() {
        var watchlist = mock(MonitoringWatchlistService.class);
        var context = mock(InvestmentContextService.class);
        var candidates = mock(ThesisCandidateGenerator.class);
        var verification = mock(InvestmentThesisVerificationService.class);
        var now = Instant.parse("2026-10-10T20:00:00Z");
        var entry = new MonitoringWatchlistService.WatchlistEntry(UUID.randomUUID(), "NEWT", "WATCH",
                new MonitoringWatchlistService.WatchlistLevels(null, null, null, null),
                Map.of(), now, now, now);
        when(watchlist.registerIfAbsent(USER_ID, "NEWT"))
                .thenReturn(new MonitoringWatchlistService.RegistrationResult(entry, true));

        var result = new InvestmentOnboardingService(watchlist, context, candidates, verification)
                .register(USER_ID, "newt");

        assertThat(result).isEqualTo(new InvestmentOnboardingService.RegistrationView(
                "NEWT", true, false, "WATCH"));
        verify(watchlist).registerIfAbsent(USER_ID, "NEWT");
        verifyNoInteractions(context, candidates, verification);
    }

    private static InvestmentContextService.ContextView readyContext(
            InvestmentContextService.SecurityView security, Instant asOf) {
        return new InvestmentContextService.ContextView(null, List.of(security), List.of(), null, List.of(),
                new InvestmentContextService.PipelineView("SUCCEEDED", asOf, asOf, null));
    }

    private InvestmentOnboardingService.ReadinessView readinessWithReceipt(
            InvestmentThesisVerificationService.VerificationReceipt receipt,
            UUID eventId, UUID revisionId, Instant asOf) {
        var watchlist = mock(MonitoringWatchlistService.class);
        var context = mock(InvestmentContextService.class);
        var candidates = mock(ThesisCandidateGenerator.class);
        var verification = mock(InvestmentThesisVerificationService.class);
        var security = readySecurity("CONFIRMED", "AI_POLICY", eventId, revisionId, null, asOf);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        when(context.context(USER_ID)).thenReturn(readyContext(security, asOf));
        when(candidates.candidates(USER_ID, "NEWT")).thenReturn(Map.of());
        when(verification.verificationReceipt(USER_ID, "NEWT", eventId)).thenReturn(receipt);

        return new InvestmentOnboardingService(watchlist, context, candidates, verification)
                .readiness(USER_ID, "NEWT");
    }

    private static InvestmentContextService.SecurityView readySecurity(
            String invalidationStatus, String actorType, UUID eventId, UUID revisionId,
            String assertedRunId, Instant asOf) {
        var mapper = new ObjectMapper();
        var readiness = mapper.createObjectNode();
        for (var field : List.of("priceStatus", "trendStatus", "fundamentalStatus", "consensusStatus",
                "revisionStatus", "valuationStatus", "balanceSheetStatus", "riskStatus", "overallDataStatus")) {
            readiness.put(field, "OK");
        }
        readiness.set("missingFields", mapper.createArrayNode());
        var thesis = new InvestmentContextService.ThesisView("NEWT", null, null, null, null, null, null,
                new BigDecimal("80.25"), invalidationStatus, null, null, null, asOf,
                revisionId, 4L, actorType, "v1", eventId, assertedRunId);
        var riskMark = new InvestmentContextService.RiskMarkView(new BigDecimal("99.99"), asOf,
                "TOSS", "QUOTE", "QUOTE_AS_OF", InvestmentDataCalculator.DataStatus.OK, null);
        var risk = new InvestmentContextService.RiskContributionView(null, null, null,
                InvestmentDataCalculator.DataStatus.OK, null, InvestmentDataCalculator.DataStatus.OK,
                null, null, null, InvestmentDataCalculator.DataStatus.OK, false, null,
                List.of("POSITION_WEIGHT_MISSING"), riskMark);
        return new InvestmentContextService.SecurityView("NEWT", null, asOf,
                mapper.createObjectNode().put("latestPrice", "99.99"), null, null, null,
                null, null, readiness, thesis, risk);
    }
}
