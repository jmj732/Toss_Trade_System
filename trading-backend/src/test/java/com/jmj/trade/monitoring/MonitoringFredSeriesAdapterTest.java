package com.jmj.trade.monitoring;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringFredSeriesAdapterTest {

    @Test
    void mapsPersistedHighYieldSpreadToBasisPointsWithSourceTimes() {
        var observation = MonitoringFredSeriesAdapter.parse(
                "BAMLH0A0HYM2:2026-09-25:2026-09-25:2026-09-27:3.42",
                "BAMLH0A0HYM2",
                Instant.parse("2026-09-25T00:00:00Z"),
                Instant.parse("2026-09-27T01:00:00Z")).orElseThrow();

        assertThat(observation.metric()).isEqualTo("credit.hy_oas");
        assertThat(observation.value()).isEqualTo("342");
        assertThat(observation.unit()).isEqualTo("bps");
        assertThat(observation.source()).isEqualTo("FRED");
        assertThat(observation.asOf()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
        assertThat(observation.collectedAt()).isEqualTo(Instant.parse("2026-09-27T01:00:00Z"));
    }

    @Test
    void leavesMalformedFredValuesUnknownInsteadOfTurningThemIntoZero() {
        assertThat(MonitoringFredSeriesAdapter.parse(
                "DGS10:2026-09-25:2026-09-25:2026-09-27:.",
                "DGS10", Instant.parse("2026-09-25T00:00:00Z"),
                Instant.parse("2026-09-27T01:00:00Z"))).isEmpty();
    }

    @Test
    void keepsNewestStoredRevisionWhenAReadingWasRevised() {
        var day = Instant.parse("2026-09-25T00:00:00Z");
        var older = MonitoringFredSeriesAdapter.parse(
                "DGS10:2026-09-25:2026-09-25:2026-09-25:4.10", "DGS10", day,
                Instant.parse("2026-09-25T13:00:00Z")).orElseThrow();
        var newer = MonitoringFredSeriesAdapter.parse(
                "DGS10:2026-09-25:2026-09-26:2026-09-26:4.12", "DGS10", day,
                Instant.parse("2026-09-26T13:00:00Z")).orElseThrow();

        assertThat(MonitoringFredSeriesAdapter.latestByMetricAndDate(List.of(older, newer)))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.value()).isEqualTo("4.12");
                    assertThat(value.collectedAt()).isEqualTo(Instant.parse("2026-09-26T13:00:00Z"));
                });
    }
}
