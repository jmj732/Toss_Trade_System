package com.jmj.trade.investment;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

/**
 * Classifies an instant against the trading intervals declared by the official Toss US market calendar
 * (day market, pre-market, regular, after-market). Only declared calendar facts are used; there is no weekday
 * or wall-clock guessing, and any missing or malformed calendar makes the answer unverifiable (null).
 */
public final class DeclaredMarketGap {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final List<String> INTERVAL_FIELDS = List.of("dayMarket", "preMarket", "regularMarket", "afterMarket");

    private DeclaredMarketGap() {
    }

    /**
     * Returns the start of the next declared interval when {@code at} lies outside every interval declared for its
     * New York date and for the next business day (whose day market opens the previous evening). Every instant in
     * {@code [at, result)} is then outside all declared intervals. Returns null when {@code at} is inside an
     * interval or the calendars cannot verify it. {@code calendarFor} supplies the Toss calendar requested for a
     * New York date and may return null.
     */
    public static Instant nextIntervalStartIfOutside(Instant at, Function<LocalDate, JsonNode> calendarFor) {
        if (at == null || calendarFor == null) return null;
        var date = at.atZone(NEW_YORK).toLocalDate();
        var calendar = calendarFor.apply(date);
        var day = InvestmentContextService.calendarDay(calendar, "today", date);
        var nextDate = InvestmentContextService.calendarNextBusinessDate(calendar, date);
        if (day == null || nextDate == null) return null;
        var nextCalendar = calendarFor.apply(nextDate);
        var nextDay = InvestmentContextService.calendarDay(nextCalendar, "today", nextDate);
        if (nextDay == null) return null;
        if (!Boolean.TRUE.equals(outsideDeclaredIntervals(day, at))
                || !Boolean.TRUE.equals(outsideDeclaredIntervals(nextDay, at))) {
            return null;
        }
        return InvestmentContextService.nextDeclaredIntervalStart(at, calendar, nextCalendar);
    }

    /** Null when the day does not declare its regular session key or any declared interval is malformed. */
    private static Boolean outsideDeclaredIntervals(JsonNode day, Instant at) {
        if (!day.has("regularMarket")) return null;
        for (var field : INTERVAL_FIELDS) {
            var interval = day.get(field);
            if (interval == null || interval.isNull()) continue;
            if (!interval.isObject()) return null;
            var start = InvestmentContextService.tossCalendarBound(interval, "startTime", "open", "openTime", "start");
            var end = InvestmentContextService.tossCalendarBound(interval, "endTime", "close", "closeTime", "end");
            if (start == null || end == null || !start.isBefore(end)) return null;
            if (!at.isBefore(start) && at.isBefore(end)) return false;
        }
        return true;
    }
}
