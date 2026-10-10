package com.jmj.trade.investment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static com.jmj.trade.investment.InvestmentDataCalculator.DataStatus;

/** Selects an explicit risk mark without changing the quote or its freshness status. */
final class InvestmentRiskMarkSelector {

    static final String CLOSED_INTERVAL_REASON = "TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS";
    static final String LATEST_QUOTE_BASIS = "LATEST_QUOTE";
    static final String REGULAR_CLOSE_BASIS = "VERIFIED_REGULAR_CLOSE";
    static final String OBSERVATION_TIMESTAMP_BASIS = "OBSERVATION_TIMESTAMP";
    static final String PROVIDER_SESSION_LABEL_BASIS = "PROVIDER_SESSION_LABEL";

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private InvestmentRiskMarkSelector() {
    }

    static Selection select(Input input) {
        if (input == null || input.now() == null) {
            return unavailable(DataStatus.DATA_MISSING, "EVALUATION_TIME_MISSING");
        }

        var quoteStatus = input.latestPriceStatus() == null ? DataStatus.DATA_MISSING : input.latestPriceStatus();
        if (quoteStatus == DataStatus.SOURCE_CONFLICT) {
            return unavailable(DataStatus.SOURCE_CONFLICT, "LATEST_QUOTE_SOURCE_CONFLICT");
        }
        if (quoteStatus == DataStatus.OK) {
            return selectCurrentQuote(input);
        }
        if (quoteStatus != DataStatus.PARTIAL && quoteStatus != DataStatus.STALE) {
            return unavailable(quoteStatus, "LATEST_QUOTE_STATUS_NOT_ELIGIBLE");
        }
        return selectClosedIntervalClose(input, quoteStatus);
    }

    private static Selection selectCurrentQuote(Input input) {
        if (!positive(input.latestPrice()) || input.latestPriceAsOf() == null) {
            return unavailable(DataStatus.DATA_MISSING, "LATEST_QUOTE_INCOMPLETE");
        }
        if (input.latestPriceAsOf().isAfter(input.now())) {
            return unavailable(DataStatus.UNVERIFIED, "LATEST_QUOTE_TIMESTAMP_IN_FUTURE");
        }
        if (blank(input.latestPriceSource())) {
            return unavailable(DataStatus.UNVERIFIED, "LATEST_QUOTE_SOURCE_MISSING");
        }
        return new Selection(input.latestPrice(), input.latestPriceAsOf(), input.latestPriceSource(),
                LATEST_QUOTE_BASIS, OBSERVATION_TIMESTAMP_BASIS, DataStatus.OK, "LATEST_QUOTE_ACCEPTED");
    }

    private static Selection selectClosedIntervalClose(Input input, DataStatus quoteStatus) {
        if (!positive(input.latestPrice()) || input.latestPriceAsOf() == null) {
            return unavailable(DataStatus.DATA_MISSING, "CLOSED_INTERVAL_QUOTE_INCOMPLETE");
        }
        if (input.latestPriceAsOf().isAfter(input.now())) {
            return unavailable(DataStatus.UNVERIFIED, "CLOSED_INTERVAL_QUOTE_TIMESTAMP_IN_FUTURE");
        }
        if (!CLOSED_INTERVAL_REASON.equals(input.sessionReason())
                || input.nextDeclaredIntervalStartsAt() == null) {
            return unavailable(quoteStatus, "CLOSED_INTERVAL_CALENDAR_EVIDENCE_MISSING");
        }
        if (!"TOSS".equals(input.latestPriceSource())) {
            return unavailable(DataStatus.UNVERIFIED, "CLOSED_INTERVAL_QUOTE_SOURCE_UNVERIFIED");
        }
        if (input.latestPriceSession() != null) {
            return unavailable(DataStatus.UNVERIFIED, "CLOSED_INTERVAL_SESSION_INCONSISTENT");
        }
        if (!input.nextDeclaredIntervalStartsAt().isAfter(input.latestPriceAsOf())) {
            return unavailable(DataStatus.UNVERIFIED, "CLOSED_INTERVAL_TIMESTAMPS_INCOHERENT");
        }
        if (!input.now().isBefore(input.nextDeclaredIntervalStartsAt())) {
            return unavailable(DataStatus.STALE, "CLOSED_INTERVAL_EXPIRED");
        }
        if (!positive(input.regularClose()) || input.regularCloseAsOf() == null) {
            return unavailable(DataStatus.DATA_MISSING, "VERIFIED_REGULAR_CLOSE_INCOMPLETE");
        }
        if (!"TOSS".equals(input.regularCloseSource())) {
            return unavailable(DataStatus.UNVERIFIED, "VERIFIED_REGULAR_CLOSE_SOURCE_UNVERIFIED");
        }
        if (input.regularCloseStatus() != DataStatus.OK) {
            var closeStatus = input.regularCloseStatus() == null
                    ? DataStatus.UNVERIFIED : input.regularCloseStatus();
            return unavailable(closeStatus, "VERIFIED_REGULAR_CLOSE_STATUS_NOT_OK");
        }
        if (input.regularCloseAsOf().isAfter(input.now())) {
            return unavailable(DataStatus.UNVERIFIED, "VERIFIED_REGULAR_CLOSE_TIMESTAMP_IN_FUTURE");
        }
        if (input.regularCloseSessionDate() == null || input.lastCompletedSessionDate() == null
                || !input.regularCloseSessionDate().equals(input.lastCompletedSessionDate())) {
            return unavailable(DataStatus.UNVERIFIED, "VERIFIED_REGULAR_CLOSE_SESSION_UNVERIFIED");
        }
        if (!input.regularCloseAsOf().atZone(NEW_YORK).toLocalDate().equals(input.regularCloseSessionDate())
                || input.regularCloseSessionDate().isAfter(input.latestPriceAsOf().atZone(NEW_YORK).toLocalDate())) {
            return unavailable(DataStatus.UNVERIFIED, "VERIFIED_REGULAR_CLOSE_SESSION_INCOHERENT");
        }
        return new Selection(input.regularClose(), input.regularCloseAsOf(), input.regularCloseSource(),
                REGULAR_CLOSE_BASIS, PROVIDER_SESSION_LABEL_BASIS, DataStatus.OK,
                "DECLARED_CLOSED_INTERVAL_REGULAR_CLOSE_ACCEPTED");
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Selection unavailable(DataStatus status, String reason) {
        return new Selection(null, null, null, null, null, status, reason);
    }

    record Input(
            BigDecimal latestPrice,
            Instant latestPriceAsOf,
            String latestPriceSource,
            String latestPriceSession,
            DataStatus latestPriceStatus,
            String sessionReason,
            Instant nextDeclaredIntervalStartsAt,
            BigDecimal regularClose,
            Instant regularCloseAsOf,
            String regularCloseSource,
            LocalDate regularCloseSessionDate,
            LocalDate lastCompletedSessionDate,
            DataStatus regularCloseStatus,
            Instant now
    ) {
    }

    record Selection(
            BigDecimal value,
            Instant asOf,
            String source,
            String basis,
            String asOfBasis,
            DataStatus status,
            String reason
    ) {
        boolean available() {
            return status == DataStatus.OK && positive(value) && asOf != null && !blank(source) && basis != null;
        }
    }
}
