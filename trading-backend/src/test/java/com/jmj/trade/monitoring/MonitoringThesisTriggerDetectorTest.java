package com.jmj.trade.monitoring;

import com.jmj.trade.investment.InvestmentContextService.ConfirmedTriggerObservation;
import com.jmj.trade.investment.InvestmentDataCalculator.DataStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringThesisTriggerDetectorTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void prefersVerifiedRegularCloseWhenLatestQuoteIsPremarketOrStale() {
        var sessionDate = LocalDate.parse("2026-10-14");
        var observation = new ConfirmedTriggerObservation(
                "LUNR", new BigDecimal("11.1883"), DataStatus.STALE,
                new BigDecimal("10.90"), Instant.parse("2026-10-15T10:55:00Z"), "PREMARKET",
                DataStatus.OK, new BigDecimal("11.02"), sessionDate.atStartOfDay(NEW_YORK).toInstant(),
                "TOSS", sessionDate, sessionDate);

        var selected = MonitoringThesisTriggerDetector.trustedAlertPrice(observation);

        assertThat(selected).isNotNull();
        assertThat(selected.value()).isEqualByComparingTo("11.02");
        assertThat(selected.basis()).isEqualTo("REGULAR_CLOSE");
        assertThat(selected.asOf()).isEqualTo(sessionDate.atStartOfDay(NEW_YORK).toInstant());
    }

    @Test
    void rejectsRegularCloseThatIsNotForLastCompletedSession() {
        var closeDate = LocalDate.parse("2026-10-13");
        var observation = new ConfirmedTriggerObservation(
                "LUNR", new BigDecimal("11.1883"), DataStatus.STALE,
                new BigDecimal("10.90"), Instant.parse("2026-10-15T10:55:00Z"), "PREMARKET",
                DataStatus.OK, new BigDecimal("11.02"), closeDate.atStartOfDay(NEW_YORK).toInstant(),
                "TOSS", closeDate, LocalDate.parse("2026-10-14"));

        assertThat(MonitoringThesisTriggerDetector.trustedAlertPrice(observation)).isNull();
    }
}
