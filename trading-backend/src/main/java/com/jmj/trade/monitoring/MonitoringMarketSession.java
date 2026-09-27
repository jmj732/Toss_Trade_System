package com.jmj.trade.monitoring;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Fails closed unless an official fresh calendar provides a regular session window whose instant span covers now. */
final class MonitoringMarketSession {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Duration MAX_CALENDAR_AGE = Duration.ofMinutes(15);
    private static final LocalTime EARLIEST_REGULAR_OPEN = LocalTime.of(9, 30);
    private static final LocalTime LATEST_REGULAR_CLOSE = LocalTime.of(16, 0);

    private MonitoringMarketSession() {
    }

    static boolean isPotentialRegularSession(Instant instant) {
        if (instant == null) {
            return false;
        }
        var local = instant.atZone(NEW_YORK);
        var weekday = local.getDayOfWeek().getValue() <= 5;
        var time = local.toLocalTime();
        return weekday && !time.isBefore(EARLIEST_REGULAR_OPEN) && time.isBefore(LATEST_REGULAR_CLOSE);
    }

    static boolean isOpen(Instant now, JsonNode calendar, Instant observedAt) {
        if (!isPotentialRegularSession(now) || calendar == null || calendar.isNull() || observedAt == null
                || observedAt.isAfter(now) || observedAt.isBefore(now.minus(MAX_CALENDAR_AGE))) {
            return false;
        }
        // The official calendar carries absolute open/close instants (ISO offset datetimes in the venue's local
        // offset). A regular session can span two calendar dates (e.g. a US session labelled by its KST date runs
        // into the next KST day), so a currently-running session may be described by either today or the previous
        // business day. Decide purely on the instant window; a missing, malformed, or non-window entry is unknown.
        for (var key : new String[] {"today", "previousBusinessDay"}) {
            var day = calendar.path(key);
            if (!day.isObject()) {
                continue;
            }
            var regular = day.path("regularMarket");
            if (!regular.isObject()) {
                continue;
            }
            var open = instant(regular, "open", "openTime", "start", "startTime");
            var close = instant(regular, "close", "closeTime", "end", "endTime");
            if (open == null || close == null || !open.isBefore(close)) {
                continue;
            }
            if (!now.isBefore(open) && now.isBefore(close)) {
                return true;
            }
        }
        return false;
    }

    private static Instant instant(JsonNode object, String... keys) {
        for (var key : keys) {
            var value = object.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                var text = value.asText(null);
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return OffsetDateTime.parse(text).toInstant();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
