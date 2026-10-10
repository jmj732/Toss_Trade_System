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
                input.lastCompletedSessionDate(), input.regularCloseStatus(), now);
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
