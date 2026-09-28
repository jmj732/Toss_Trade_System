package com.jmj.trade.monitoring;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringMarketSessionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requiresAUsWeekdayRegularSessionWindow() {
        assertThat(MonitoringMarketSession.isPotentialRegularSession(
                Instant.parse("2026-09-28T14:00:00Z"))).isTrue();
        assertThat(MonitoringMarketSession.isPotentialRegularSession(
                Instant.parse("2026-09-26T14:00:00Z"))).isFalse();
        assertThat(MonitoringMarketSession.isPotentialRegularSession(
                Instant.parse("2026-09-28T21:00:00Z"))).isFalse();
    }

    @Test
    void usesAbsoluteInstantWindowFromOfficialCalendar() throws Exception {
        // Real Toss market-calendar shape: open/close are ISO offset datetimes in KST. The US session
        // labelled 2026-08-05 runs 09:30-16:00 EDT (22:30 KST through 05:00 KST the following day).
        var calendar = objectMapper.readTree("""
                {"previousBusinessDay":{"date":"2026-08-04","regularMarket":{"open":"2026-08-04T22:30:00+09:00","close":"2026-08-05T05:00:00+09:00"}},
                 "today":{"date":"2026-08-05","regularMarket":{"open":"2026-08-05T22:30:00+09:00","close":"2026-08-06T05:00:00+09:00"}},
                 "nextBusinessDay":{"date":"2026-08-06","regularMarket":{"open":"2026-08-06T22:30:00+09:00","close":"2026-08-07T05:00:00+09:00"}}}
                """);

        // Open at NY 10:00 (14:00Z), inside today's window.
        var open10 = Instant.parse("2026-08-05T14:00:00Z");
        assertThat(MonitoringMarketSession.isOpen(open10, calendar, open10)).isTrue();
        // Closed at NY 16:30 (20:30Z), past the close.
        var afterClose = Instant.parse("2026-08-05T20:30:00Z");
        assertThat(MonitoringMarketSession.isOpen(afterClose, calendar, afterClose)).isFalse();
        // A stale official calendar (older than the freshness window) is not trusted.
        assertThat(MonitoringMarketSession.isOpen(open10, calendar,
                open10.minus(java.time.Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void detectsSessionRunningUnderThePreviousBusinessDayEntry() throws Exception {
        // Calendar as fetched at KST 2026-08-06 04:30 (NY 2026-08-05 15:30): Toss labels "today" as
        // 2026-08-06 (not yet open); the still-running NY session is under previousBusinessDay (2026-08-05).
        var calendar = objectMapper.readTree("""
                {"previousBusinessDay":{"date":"2026-08-05","regularMarket":{"open":"2026-08-05T22:30:00+09:00","close":"2026-08-06T05:00:00+09:00"}},
                 "today":{"date":"2026-08-06","regularMarket":{"open":"2026-08-06T22:30:00+09:00","close":"2026-08-07T05:00:00+09:00"}}}
                """);

        var now = Instant.parse("2026-08-05T19:30:00Z"); // NY 15:30, inside the previous business day's window
        assertThat(MonitoringMarketSession.isOpen(now, calendar, now)).isTrue();
    }

    @Test
    void honorsEarlyCloseWithinTheInstantWindow() throws Exception {
        // Early close on 2026-11-27 (Friday after Thanksgiving, EST): session ends 13:00 EST (03:00 KST next day).
        var calendar = objectMapper.readTree("""
                {"today":{"date":"2026-11-27","regularMarket":{"open":"2026-11-27T23:30:00+09:00","close":"2026-11-28T03:00:00+09:00"}}}
                """);

        var beforeClose = Instant.parse("2026-11-27T17:30:00Z"); // NY 12:30 EST
        assertThat(MonitoringMarketSession.isOpen(beforeClose, calendar, beforeClose)).isTrue();
        var afterEarlyClose = Instant.parse("2026-11-27T18:30:00Z"); // NY 13:30 EST, past the early close
        assertThat(MonitoringMarketSession.isOpen(afterEarlyClose, calendar, afterEarlyClose)).isFalse();
    }

    @Test
    void unknownCalendarDoesNotPermitIntradayFetching() throws Exception {
        var now = Instant.parse("2026-09-28T14:00:00Z");
        var missingHours = objectMapper.readTree("""
                {"today":{"date":"2026-09-28","regularMarket":{"status":"OPEN"}}}
                """);
        var holiday = objectMapper.readTree("""
                {"today":{"date":"2026-09-28","regularMarket":null}}
                """);

        assertThat(MonitoringMarketSession.isOpen(now, missingHours, now)).isFalse();
        assertThat(MonitoringMarketSession.isOpen(now, holiday, now)).isFalse();
    }
}
