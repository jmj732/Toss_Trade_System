package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session facts are derived only from the official Toss US calendar (KST-offset instants as returned in
 * production) and are anchored to the New York session date; no weekday or wall-clock guessing.
 */
class InvestmentPriceSessionFactsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode friday() {
        return mapper.readTree("""
                {"today":{"date":"2026-10-02",
                  "dayMarket":{"startTime":"2026-10-02T09:00:00.000+09:00","endTime":"2026-10-02T17:00:00.000+09:00"},
                  "preMarket":{"startTime":"2026-10-02T17:00:00.000+09:00","endTime":"2026-10-02T22:30:00.000+09:00"},
                  "regularMarket":{"startTime":"2026-10-02T22:30:00.000+09:00","endTime":"2026-10-03T05:00:00.000+09:00"},
                  "afterMarket":{"startTime":"2026-10-03T05:00:00.000+09:00","endTime":"2026-10-03T08:50:00.000+09:00"}},
                 "previousBusinessDay":{"date":"2026-10-01"},
                 "nextBusinessDay":{"date":"2026-10-05"}}
                """);
    }

    private JsonNode saturday() {
        return mapper.readTree("""
                {"today":{"date":"2026-10-03","dayMarket":null,"preMarket":null,"regularMarket":null,"afterMarket":null},
                 "previousBusinessDay":{"date":"2026-10-02"},
                 "nextBusinessDay":{"date":"2026-10-05"}}
                """);
    }

    private JsonNode monday() {
        return mapper.readTree("""
                {"today":{"date":"2026-10-05",
                  "dayMarket":{"startTime":"2026-10-05T09:00:00.000+09:00","endTime":"2026-10-05T17:00:00.000+09:00"},
                  "preMarket":{"startTime":"2026-10-05T17:00:00.000+09:00","endTime":"2026-10-05T22:30:00.000+09:00"},
                  "regularMarket":{"startTime":"2026-10-05T22:30:00.000+09:00","endTime":"2026-10-06T05:00:00.000+09:00"},
                  "afterMarket":{"startTime":"2026-10-06T05:00:00.000+09:00","endTime":"2026-10-06T08:50:00.000+09:00"}},
                 "previousBusinessDay":{"date":"2026-10-02"},
                 "nextBusinessDay":{"date":"2026-10-06"}}
                """);
    }

    @Test
    void duringRegularSessionTheLastCompletedSessionIsThePreviousBusinessDayUntilTodaysClose() {
        var facts = InvestmentContextService.regularSessionFacts(
                Instant.parse("2026-10-02T14:00:00Z"), friday(), monday());

        assertThat(facts.lastCompletedSessionDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(facts.regularCloseValidUntil()).isEqualTo(Instant.parse("2026-10-02T20:00:00Z"));
    }

    @Test
    void beforeTheRegularOpenThePreviousBusinessDayIsStillTheLastCompletedSession() {
        var facts = InvestmentContextService.regularSessionFacts(
                Instant.parse("2026-10-02T12:00:00Z"), friday(), monday());

        assertThat(facts.lastCompletedSessionDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(facts.regularCloseValidUntil()).isEqualTo(Instant.parse("2026-10-02T20:00:00Z"));
    }

    @Test
    void afterTheCloseTodayIsTheLastCompletedSessionUntilTheNextDeclaredRegularSessionEnds() {
        // 22:00 New York on Friday is already Saturday in UTC and KST; the session date is still Friday.
        var facts = InvestmentContextService.regularSessionFacts(
                Instant.parse("2026-10-03T02:00:00Z"), friday(), monday());

        assertThat(facts.lastCompletedSessionDate()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(facts.regularCloseValidUntil()).isEqualTo(Instant.parse("2026-10-05T20:00:00Z"));
    }

    @Test
    void aDeclaredNonTradingDayUsesTheProviderPreviousBusinessDay() {
        var facts = InvestmentContextService.regularSessionFacts(
                Instant.parse("2026-10-03T14:00:00Z"), saturday(), monday());

        assertThat(facts.lastCompletedSessionDate()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(facts.regularCloseValidUntil()).isEqualTo(Instant.parse("2026-10-05T20:00:00Z"));
    }

    @Test
    void missingNextBusinessDayCalendarKeepsTheSessionDateButLeavesValidityUnbounded() {
        var facts = InvestmentContextService.regularSessionFacts(
                Instant.parse("2026-10-03T14:00:00Z"), saturday(), null);

        assertThat(facts.lastCompletedSessionDate()).isEqualTo(LocalDate.parse("2026-10-02"));
        assertThat(facts.regularCloseValidUntil()).isNull();
    }

    @Test
    void unavailableMismatchedOrUndeclaredCalendarsAreUnverifiedInsteadOfGuessed() {
        var friday = Instant.parse("2026-10-02T14:00:00Z");
        assertThat(InvestmentContextService.regularSessionFacts(friday, null, monday())).isNull();
        // The calendar for another New York date never stands in for today's.
        assertThat(InvestmentContextService.regularSessionFacts(friday, saturday(), monday())).isNull();
        var noRegularKey = mapper.readTree("""
                {"today":{"date":"2026-10-02"},"previousBusinessDay":{"date":"2026-10-01"}}
                """);
        assertThat(InvestmentContextService.regularSessionFacts(friday, noRegularKey, monday())).isNull();
        var noPrevious = mapper.readTree("""
                {"today":{"date":"2026-10-03","regularMarket":null}}
                """);
        assertThat(InvestmentContextService.regularSessionFacts(
                Instant.parse("2026-10-03T14:00:00Z"), noPrevious, monday())).isNull();
    }

    @Test
    void nextDeclaredIntervalStartComesFromTheQuoteDayOrTheNextBusinessDayCalendar() {
        // After the Friday after-market ends (19:50 New York) the next declared interval is Monday's day market,
        // which Toss opens at Sunday 20:00 New York.
        assertThat(InvestmentContextService.nextDeclaredIntervalStart(
                Instant.parse("2026-10-02T23:55:00Z"), friday(), monday()))
                .isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
        // Inside pre-market the next declared start is the regular open of the same day.
        assertThat(InvestmentContextService.nextDeclaredIntervalStart(
                Instant.parse("2026-10-02T08:10:00Z"), friday(), monday()))
                .isEqualTo(Instant.parse("2026-10-02T13:30:00Z"));
        assertThat(InvestmentContextService.nextDeclaredIntervalStart(
                Instant.parse("2026-10-02T23:55:00Z"), friday(), null)).isNull();
        assertThat(InvestmentContextService.nextDeclaredIntervalStart(
                Instant.parse("2026-10-02T23:55:00Z"), null, monday())).isNull();
    }

    @Test
    void nextBusinessDateIsReadOnlyFromTheMatchingCalendar() {
        assertThat(InvestmentContextService.calendarNextBusinessDate(friday(), LocalDate.parse("2026-10-02")))
                .isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(InvestmentContextService.calendarNextBusinessDate(friday(), LocalDate.parse("2026-10-03")))
                .isNull();
        assertThat(InvestmentContextService.calendarNextBusinessDate(null, LocalDate.parse("2026-10-02")))
                .isNull();
    }
}
