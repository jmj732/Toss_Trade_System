package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closed-market gaps come only from the official Toss US calendar (KST-offset instants as returned in production):
 * an instant counts as outside the market only when no declared interval of its New York date or of the next
 * business day (whose day market opens the previous New York evening) contains it.
 */
class DeclaredMarketGapTest {

    private static final Instant MONDAY_DAY_MARKET_OPEN = Instant.parse("2026-10-05T00:00:00Z");

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

    private JsonNode sunday() {
        return mapper.readTree("""
                {"today":{"date":"2026-10-04","regularMarket":null},
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

    private Map<LocalDate, JsonNode> week() {
        var calendars = new HashMap<LocalDate, JsonNode>();
        calendars.put(LocalDate.parse("2026-10-02"), friday());
        calendars.put(LocalDate.parse("2026-10-03"), saturday());
        calendars.put(LocalDate.parse("2026-10-04"), sunday());
        calendars.put(LocalDate.parse("2026-10-05"), monday());
        return calendars;
    }

    @Test
    void saturdayIsOutsideEveryIntervalUntilMondaysDayMarketOpensOnSundayEvening() {
        // Saturday 10:00 New York.
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-03T14:00:00Z"), week()::get))
                .isEqualTo(MONDAY_DAY_MARKET_OPEN);
        // Sunday 19:00 New York, one hour before Monday's day market opens.
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-04T23:00:00Z"), week()::get))
                .isEqualTo(MONDAY_DAY_MARKET_OPEN);
    }

    @Test
    void afterTheFridayAfterMarketEndsTheGapRunsToTheNextDeclaredStart() {
        // The Friday after-market ends 19:50 New York (08:50 KST Saturday).
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-02T23:50:00Z"), week()::get))
                .isEqualTo(MONDAY_DAY_MARKET_OPEN);
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-02T23:55:00Z"), week()::get))
                .isEqualTo(MONDAY_DAY_MARKET_OPEN);
    }

    @Test
    void instantsInsideAnyDeclaredIntervalIncludingExtendedSessionsAreNotOutside() {
        // Friday after-market (19:45 New York), regular session, pre-market and day market.
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-02T23:45:00Z"), week()::get))
                .isNull();
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-02T15:00:00Z"), week()::get))
                .isNull();
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-02T09:00:00Z"), week()::get))
                .isNull();
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-02T06:00:00Z"), week()::get))
                .isNull();
        // Sunday 21:00 New York is inside Monday's day market, which only the next business day declares.
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(Instant.parse("2026-10-05T01:00:00Z"), week()::get))
                .isNull();
    }

    @Test
    void missingOrUnverifiableCalendarsAreNeverTreatedAsClosed() {
        var saturdayNoon = Instant.parse("2026-10-03T16:00:00Z");
        var withoutNextBusinessDay = week();
        withoutNextBusinessDay.remove(LocalDate.parse("2026-10-05"));
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(saturdayNoon, withoutNextBusinessDay::get)).isNull();
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(saturdayNoon, date -> null)).isNull();
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(null, week()::get)).isNull();

        // A day that does not declare its regular-session key is unverifiable, not a holiday.
        var undeclared = week();
        undeclared.put(LocalDate.parse("2026-10-03"), mapper.readTree("""
                {"today":{"date":"2026-10-03"},"previousBusinessDay":{"date":"2026-10-02"},
                 "nextBusinessDay":{"date":"2026-10-05"}}
                """));
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(saturdayNoon, undeclared::get)).isNull();

        // A malformed interval anywhere in the consulted days makes the gap unverifiable.
        var malformed = week();
        malformed.put(LocalDate.parse("2026-10-05"), mapper.readTree("""
                {"today":{"date":"2026-10-05","regularMarket":null,
                  "dayMarket":{"startTime":"not-a-time","endTime":"2026-10-05T17:00:00.000+09:00"}},
                 "previousBusinessDay":{"date":"2026-10-02"},"nextBusinessDay":{"date":"2026-10-06"}}
                """));
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(saturdayNoon, malformed::get)).isNull();

        // A calendar for another New York date never stands in for the instant's date.
        var mismatched = week();
        mismatched.put(LocalDate.parse("2026-10-03"), sunday());
        assertThat(DeclaredMarketGap.nextIntervalStartIfOutside(saturdayNoon, mismatched::get)).isNull();
    }
}
