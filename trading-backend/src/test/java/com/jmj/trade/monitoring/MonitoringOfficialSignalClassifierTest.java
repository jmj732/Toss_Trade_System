package com.jmj.trade.monitoring;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringOfficialSignalClassifierTest {

    private static final Instant AS_OF = Instant.parse("2026-09-27T12:00:00Z");

    @Test
    void classifiesOnlyExplicitOfficialShockLanguage() {
        var shock = MonitoringOfficialSignalClassifier.classify(
                "BLS", "cpi-2026-09", "INFLATION_RELEASE", "CPI inflation surprise: sharp surge", AS_OF);

        assertThat(shock.shocks()).singleElement().satisfies(value -> {
            assertThat(value.category()).isEqualTo("INFLATION");
            assertThat(value.severity()).isEqualTo("STRESS");
            assertThat(value.source()).isEqualTo("BLS:cpi-2026-09");
        });
    }

    @Test
    void createsSystemicIncidentOnlyWithExplicitForcedDeleveragingAndInstitution() {
        var incident = MonitoringOfficialSignalClassifier.classify(
                "SEC", "filing-1", "8-K", "Bank reports forced liquidation following margin call", AS_OF);
        var vague = MonitoringOfficialSignalClassifier.classify(
                "SEC", "filing-2", "8-K", "Bank reports liquidity concerns", AS_OF);
        var hypothetical = MonitoringOfficialSignalClassifier.classify(
                "SEC", "filing-3", "8-K", "Bank warns of risk of forced liquidation", AS_OF);
        var secondary = MonitoringOfficialSignalClassifier.classify(
                "NEWS", "item-1", "NEWS", "Fund forced liquidation", AS_OF);

        assertThat(incident.incidents()).singleElement().satisfies(value -> {
            assertThat(value.kind()).isEqualTo("INSTITUTION");
            assertThat(value.forcedDeleveraging()).isTrue();
            assertThat(value.official()).isTrue();
        });
        assertThat(vague.incidents()).isEmpty();
        assertThat(hypothetical.incidents()).isEmpty();
        assertThat(secondary.incidents()).isEmpty();
    }
}
