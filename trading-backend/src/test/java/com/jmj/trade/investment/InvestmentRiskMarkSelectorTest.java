package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static com.jmj.trade.investment.InvestmentDataCalculator.DataStatus;
import static org.assertj.core.api.Assertions.assertThat;

class InvestmentRiskMarkSelectorTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:10:00Z");
    private static final Instant QUOTE_AS_OF = Instant.parse("2026-10-03T00:05:00Z");
    private static final Instant NEXT_INTERVAL = Instant.parse("2026-10-05T13:30:00Z");
    private static final Instant CLOSE_SESSION_LABEL = Instant.parse("2026-10-02T04:00:00Z");
    private static final LocalDate SESSION_DATE = LocalDate.parse("2026-10-02");
    private static final Instant CLOSED_INTERVAL_START = Instant.parse("2026-10-03T00:08:00Z");
    private static final Instant CLOSED_INTERVAL_END = Instant.parse("2026-10-05T13:30:00Z");

    @Test
    void prefersAnOkLatestQuoteAndPreservesItsObservationTimestamp() {
        var selection = InvestmentRiskMarkSelector.select(input(DataStatus.OK, "AFTER_HOURS", null,
                bd("101"), QUOTE_AS_OF, "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW));

        assertThat(selection).isEqualTo(new InvestmentRiskMarkSelector.Selection(
                bd("101"), QUOTE_AS_OF, "TOSS", InvestmentRiskMarkSelector.LATEST_QUOTE_BASIS,
                InvestmentRiskMarkSelector.OBSERVATION_TIMESTAMP_BASIS, DataStatus.OK, "LATEST_QUOTE_ACCEPTED"));
    }

    @Test
    void rejectsOkRegularCloseQuoteWhenItsRegularCloseIsUnverified() {
        var now = Instant.parse("2026-10-14T02:49:41Z");
        var quoteAsOf = now.minusSeconds(22 * 60 * 60 + 48 * 60);
        var input = new InvestmentRiskMarkSelector.Input(
                bd("101"), quoteAsOf, "TOSS", "REGULAR_CLOSE", DataStatus.OK,
                null, null, bd("100"), quoteAsOf, "TOSS", LocalDate.parse("2026-10-13"),
                LocalDate.parse("2026-10-14"), DataStatus.UNVERIFIED, now);

        var selection = InvestmentRiskMarkSelector.select(input);

        assertThat(selection.available()).isFalse();
        assertThat(selection.status()).isEqualTo(DataStatus.UNVERIFIED);
        assertThat(selection.reason()).isEqualTo("VERIFIED_REGULAR_CLOSE_STATUS_NOT_OK");
    }

    @Test
    void usesVerifiedCanonicalCloseInsteadOfRegularCloseQuote() {
        var now = Instant.parse("2026-10-14T02:49:41Z");
        var closeAsOf = Instant.parse("2026-10-13T04:00:00Z");
        var input = new InvestmentRiskMarkSelector.Input(
                bd("101"), closeAsOf, "TOSS", "REGULAR_CLOSE", DataStatus.OK,
                null, null, bd("100"), closeAsOf, "TOSS", LocalDate.parse("2026-10-13"),
                LocalDate.parse("2026-10-13"), DataStatus.OK, now);

        var selection = InvestmentRiskMarkSelector.select(input);

        assertThat(selection.available()).isTrue();
        assertThat(selection.value()).isEqualByComparingTo("100");
        assertThat(selection.basis()).isEqualTo(InvestmentRiskMarkSelector.REGULAR_CLOSE_BASIS);
        assertThat(selection.asOfBasis()).isEqualTo(InvestmentRiskMarkSelector.PROVIDER_SESSION_LABEL_BASIS);
        assertThat(selection.reason()).isEqualTo("VERIFIED_REGULAR_CLOSE_ACCEPTED");
    }

    @Test
    void selectsTheVerifiedCloseOnlyInsideTheDeclaredGapAndKeepsItsProviderSessionLabel() {
        var selection = InvestmentRiskMarkSelector.select(input(DataStatus.PARTIAL, null,
                InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON, bd("101"), QUOTE_AS_OF, "TOSS",
                CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE, DataStatus.OK, NOW));

        assertThat(selection.available()).isTrue();
        assertThat(selection.value()).isEqualByComparingTo("100");
        assertThat(selection.asOf()).isEqualTo(CLOSE_SESSION_LABEL);
        assertThat(selection.source()).isEqualTo("TOSS");
        assertThat(selection.basis()).isEqualTo(InvestmentRiskMarkSelector.REGULAR_CLOSE_BASIS);
        assertThat(selection.asOfBasis()).isEqualTo(InvestmentRiskMarkSelector.PROVIDER_SESSION_LABEL_BASIS);
        assertThat(selection.status()).isEqualTo(DataStatus.OK);
    }

    @Test
    void allowsStaleQuoteOnlyWhileTheSameDeclaredGapIsStillOpen() {
        var input = input(DataStatus.STALE, null, InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON,
                bd("101"), QUOTE_AS_OF, "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);

        assertThat(InvestmentRiskMarkSelector.select(input).available()).isTrue();
        assertThat(InvestmentRiskMarkSelector.select(withNow(input, NEXT_INTERVAL)).status())
                .isEqualTo(DataStatus.STALE);
    }

    @Test
    void sourceConflictNeverFallsBackToTheRegularClose() {
        var selection = InvestmentRiskMarkSelector.select(input(DataStatus.SOURCE_CONFLICT, null,
                InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON, bd("101"), QUOTE_AS_OF, "TOSS",
                CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE, DataStatus.OK, NOW));

        assertThat(selection.available()).isFalse();
        assertThat(selection.status()).isEqualTo(DataStatus.SOURCE_CONFLICT);
    }

    @Test
    void missingOrInconsistentCalendarEvidenceDoesNotEnableCloseFallback() {
        var validEvidence = input(DataStatus.PARTIAL, null, InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON,
                bd("101"), QUOTE_AS_OF, "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);
        var noReason = input(DataStatus.PARTIAL, null, null, bd("101"), QUOTE_AS_OF, "TOSS",
                CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE, DataStatus.OK, NOW);
        var noNextInterval = withNextInterval(validEvidence, null);
        var reachedInterval = withNextInterval(validEvidence, NOW);
        var incoherentInterval = withNextInterval(validEvidence, QUOTE_AS_OF);

        assertThat(InvestmentRiskMarkSelector.select(noReason).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(noNextInterval).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(reachedInterval).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(incoherentInterval).status()).isEqualTo(DataStatus.UNVERIFIED);
    }

    @Test
    void staleClassifiedQuoteWithoutClosedGapEvidenceRetainsStaleStatus() {
        var ordinaryStaleQuote = input(DataStatus.STALE, "AFTER_HOURS", null,
                bd("101"), QUOTE_AS_OF, "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);

        var selection = InvestmentRiskMarkSelector.select(withNextInterval(ordinaryStaleQuote, null));

        assertThat(selection.available()).isFalse();
        assertThat(selection.status()).isEqualTo(DataStatus.STALE);
        assertThat(selection.reason()).isEqualTo("CLOSED_INTERVAL_CALENDAR_EVIDENCE_MISSING");
    }

    @Test
    void classifiedAfterHoursQuoteUsesRegularCloseOnlyWithCurrentBoundedTossEvidence() {
        var quote = input(DataStatus.STALE, "AFTER_HOURS", null,
                bd("101"), QUOTE_AS_OF, "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);
        var evidence = withClosedIntervalEvidence(quote, CLOSED_INTERVAL_START, CLOSED_INTERVAL_END,
                "TOSS", NOW.minusSeconds(30));

        var selection = InvestmentRiskMarkSelector.select(evidence);

        assertThat(selection.available()).isTrue();
        assertThat(selection.value()).isEqualByComparingTo("100");
        assertThat(selection.asOf()).isEqualTo(CLOSE_SESSION_LABEL);
        assertThat(selection.reason()).isEqualTo("DECLARED_CLOSED_INTERVAL_REGULAR_CLOSE_ACCEPTED");
    }

    @Test
    void quoteCapturedInsideCurrentClosedGapCanUseTheVerifiedRegularClose() {
        var quote = input(DataStatus.PARTIAL, null, InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON,
                bd("101"), CLOSED_INTERVAL_START.plusSeconds(60), "TOSS", CLOSE_SESSION_LABEL,
                SESSION_DATE, SESSION_DATE, DataStatus.OK, NOW);
        var evidence = withClosedIntervalEvidence(quote, CLOSED_INTERVAL_START, CLOSED_INTERVAL_END,
                "TOSS", NOW.minusSeconds(30));

        var selection = InvestmentRiskMarkSelector.select(evidence);

        assertThat(selection.available()).isTrue();
        assertThat(selection.value()).isEqualByComparingTo("100");
        assertThat(selection.asOf()).isEqualTo(CLOSE_SESSION_LABEL);
        assertThat(selection.reason()).isEqualTo("DECLARED_CLOSED_INTERVAL_REGULAR_CLOSE_ACCEPTED");
    }

    @Test
    void classifiedQuoteDoesNotUseClosureEvidenceOutsideItsBoundsOrWithoutFreshTossProvenance() {
        var quote = input(DataStatus.STALE, "AFTER_HOURS", null,
                bd("101"), QUOTE_AS_OF, "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);
        var current = withClosedIntervalEvidence(quote, CLOSED_INTERVAL_START, CLOSED_INTERVAL_END,
                "TOSS", NOW.minusSeconds(30));

        assertThat(InvestmentRiskMarkSelector.select(withNow(current, CLOSED_INTERVAL_START.minusNanos(1)))
                .available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(withNow(current, CLOSED_INTERVAL_END)).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(withClosedIntervalEvidence(current, CLOSED_INTERVAL_START,
                CLOSED_INTERVAL_END, "OTHER", NOW.minusSeconds(30))).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(withClosedIntervalEvidence(current, CLOSED_INTERVAL_START,
                CLOSED_INTERVAL_END, "TOSS", NOW.plusSeconds(1))).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(current).available()).isTrue();
        assertThat(InvestmentRiskMarkSelector.select(withClosedIntervalEvidence(quote, null, CLOSED_INTERVAL_END,
                "TOSS", NOW.minusSeconds(30))).available()).isFalse();
    }

    @Test
    void partialClassifiedQuoteWithoutClosedGapEvidenceRetainsPartialStatus() {
        var ordinaryPartialQuote = input(DataStatus.PARTIAL, "AFTER_HOURS", null,
                bd("101"), QUOTE_AS_OF, "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);

        var selection = InvestmentRiskMarkSelector.select(withNextInterval(ordinaryPartialQuote, null));

        assertThat(selection.available()).isFalse();
        assertThat(selection.status()).isEqualTo(DataStatus.PARTIAL);
        assertThat(selection.reason()).isEqualTo("CLOSED_INTERVAL_CALENDAR_EVIDENCE_MISSING");
    }

    @Test
    void rejectsUnverifiedQuoteSourceSessionAndTimestamp() {
        var wrongSource = input(DataStatus.PARTIAL, null, InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON,
                bd("101"), QUOTE_AS_OF, "OTHER", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);
        var classifiedSession = input(DataStatus.PARTIAL, "AFTER_HOURS",
                InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON, bd("101"), QUOTE_AS_OF, "TOSS",
                CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE, DataStatus.OK, NOW);
        var futureQuote = input(DataStatus.PARTIAL, null, InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON,
                bd("101"), NOW.plusSeconds(1), "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);

        assertThat(InvestmentRiskMarkSelector.select(wrongSource).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(classifiedSession).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(futureQuote).status()).isEqualTo(DataStatus.UNVERIFIED);
    }

    @Test
    void rejectsMissingStaleFutureOrMismatchedCloseFacts() {
        var base = input(DataStatus.PARTIAL, null, InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON,
                bd("101"), QUOTE_AS_OF, "TOSS", CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE,
                DataStatus.OK, NOW);
        var staleClose = withCloseStatus(base, DataStatus.STALE);
        var futureClose = withCloseAsOf(base, NOW.plusSeconds(1));
        var wrongCloseSource = withCloseSource(base, "OTHER");
        var mismatchedSession = withCloseSessions(base, SESSION_DATE.minusDays(1), SESSION_DATE);
        var laterCloseSession = withCloseSessions(base, SESSION_DATE.plusDays(1), SESSION_DATE.plusDays(1));
        var missingClose = withClose(base, null, null);

        assertThat(InvestmentRiskMarkSelector.select(staleClose).status()).isEqualTo(DataStatus.STALE);
        assertThat(InvestmentRiskMarkSelector.select(futureClose).status()).isEqualTo(DataStatus.UNVERIFIED);
        assertThat(InvestmentRiskMarkSelector.select(wrongCloseSource).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(mismatchedSession).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(laterCloseSession).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(missingClose).status()).isEqualTo(DataStatus.DATA_MISSING);
    }

    @Test
    void onlyPartialOrStaleQuotesCanUseClosedGapFallback() {
        var unverified = input(DataStatus.UNVERIFIED, null,
                InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON, bd("101"), QUOTE_AS_OF, "TOSS",
                CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE, DataStatus.OK, NOW);
        var missing = input(DataStatus.DATA_MISSING, null,
                InvestmentRiskMarkSelector.CLOSED_INTERVAL_REASON, bd("101"), QUOTE_AS_OF, "TOSS",
                CLOSE_SESSION_LABEL, SESSION_DATE, SESSION_DATE, DataStatus.OK, NOW);

        assertThat(InvestmentRiskMarkSelector.select(unverified).available()).isFalse();
        assertThat(InvestmentRiskMarkSelector.select(missing).available()).isFalse();
    }

    private static InvestmentRiskMarkSelector.Input input(
            DataStatus quoteStatus, String quoteSession, String sessionReason,
            BigDecimal quote, Instant quoteAsOf, String quoteSource,
            Instant closeAsOf, LocalDate closeSessionDate, LocalDate lastCompletedSessionDate,
            DataStatus closeStatus, Instant now
    ) {
        return new InvestmentRiskMarkSelector.Input(quote, quoteAsOf, quoteSource, quoteSession, quoteStatus,
                sessionReason, NEXT_INTERVAL, bd("100"), closeAsOf, "TOSS", closeSessionDate,
                lastCompletedSessionDate, closeStatus, now);
    }

    private static InvestmentRiskMarkSelector.Input withNow(InvestmentRiskMarkSelector.Input input, Instant now) {
        return new InvestmentRiskMarkSelector.Input(input.latestPrice(), input.latestPriceAsOf(),
                input.latestPriceSource(), input.latestPriceSession(), input.latestPriceStatus(),
                input.sessionReason(), input.nextDeclaredIntervalStartsAt(), input.regularClose(),
                input.regularCloseAsOf(), input.regularCloseSource(), input.regularCloseSessionDate(),
                input.lastCompletedSessionDate(), input.regularCloseStatus(), now,
                input.closedIntervalStartsAt(), input.closedIntervalEndsAt(), input.closedIntervalCalendarSource(),
                input.closedIntervalCalendarObservedAt());
    }

    private static InvestmentRiskMarkSelector.Input withClosedIntervalEvidence(
            InvestmentRiskMarkSelector.Input input, Instant startsAt, Instant endsAt,
            String source, Instant observedAt
    ) {
        return new InvestmentRiskMarkSelector.Input(input.latestPrice(), input.latestPriceAsOf(),
                input.latestPriceSource(), input.latestPriceSession(), input.latestPriceStatus(),
                input.sessionReason(), input.nextDeclaredIntervalStartsAt(), input.regularClose(),
                input.regularCloseAsOf(), input.regularCloseSource(), input.regularCloseSessionDate(),
                input.lastCompletedSessionDate(), input.regularCloseStatus(), input.now(), startsAt, endsAt,
                source, observedAt);
    }

    private static InvestmentRiskMarkSelector.Input withNextInterval(
            InvestmentRiskMarkSelector.Input input, Instant nextInterval
    ) {
        return new InvestmentRiskMarkSelector.Input(input.latestPrice(), input.latestPriceAsOf(),
                input.latestPriceSource(), input.latestPriceSession(), input.latestPriceStatus(),
                input.sessionReason(), nextInterval, input.regularClose(), input.regularCloseAsOf(),
                input.regularCloseSource(), input.regularCloseSessionDate(), input.lastCompletedSessionDate(),
                input.regularCloseStatus(), input.now());
    }

    private static InvestmentRiskMarkSelector.Input withCloseStatus(
            InvestmentRiskMarkSelector.Input input, DataStatus status
    ) {
        return new InvestmentRiskMarkSelector.Input(input.latestPrice(), input.latestPriceAsOf(),
                input.latestPriceSource(), input.latestPriceSession(), input.latestPriceStatus(),
                input.sessionReason(), input.nextDeclaredIntervalStartsAt(), input.regularClose(),
                input.regularCloseAsOf(), input.regularCloseSource(), input.regularCloseSessionDate(),
                input.lastCompletedSessionDate(), status, input.now());
    }

    private static InvestmentRiskMarkSelector.Input withCloseAsOf(
            InvestmentRiskMarkSelector.Input input, Instant asOf
    ) {
        return new InvestmentRiskMarkSelector.Input(input.latestPrice(), input.latestPriceAsOf(),
                input.latestPriceSource(), input.latestPriceSession(), input.latestPriceStatus(),
                input.sessionReason(), input.nextDeclaredIntervalStartsAt(), input.regularClose(), asOf,
                input.regularCloseSource(), input.regularCloseSessionDate(), input.lastCompletedSessionDate(),
                input.regularCloseStatus(), input.now());
    }

    private static InvestmentRiskMarkSelector.Input withCloseSource(
            InvestmentRiskMarkSelector.Input input, String source
    ) {
        return new InvestmentRiskMarkSelector.Input(input.latestPrice(), input.latestPriceAsOf(),
                input.latestPriceSource(), input.latestPriceSession(), input.latestPriceStatus(),
                input.sessionReason(), input.nextDeclaredIntervalStartsAt(), input.regularClose(),
                input.regularCloseAsOf(), source, input.regularCloseSessionDate(), input.lastCompletedSessionDate(),
                input.regularCloseStatus(), input.now());
    }

    private static InvestmentRiskMarkSelector.Input withCloseSessions(
            InvestmentRiskMarkSelector.Input input, LocalDate closeSessionDate, LocalDate lastCompletedSessionDate
    ) {
        return new InvestmentRiskMarkSelector.Input(input.latestPrice(), input.latestPriceAsOf(),
                input.latestPriceSource(), input.latestPriceSession(), input.latestPriceStatus(),
                input.sessionReason(), input.nextDeclaredIntervalStartsAt(), input.regularClose(),
                input.regularCloseAsOf(), input.regularCloseSource(), closeSessionDate, lastCompletedSessionDate,
                input.regularCloseStatus(), input.now());
    }

    private static InvestmentRiskMarkSelector.Input withClose(
            InvestmentRiskMarkSelector.Input input, BigDecimal close, Instant closeAsOf
    ) {
        return new InvestmentRiskMarkSelector.Input(input.latestPrice(), input.latestPriceAsOf(),
                input.latestPriceSource(), input.latestPriceSession(), input.latestPriceStatus(),
                input.sessionReason(), input.nextDeclaredIntervalStartsAt(), close, closeAsOf,
                input.regularCloseSource(), input.regularCloseSessionDate(), input.lastCompletedSessionDate(),
                input.regularCloseStatus(), input.now());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
