package com.jmj.trade.prediction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastQualityMonitoringServiceTest {

    @Test
    void calculatesD1CalibrationAndExcludesFlatReturnsFromCalibrationSamples() {
        var rows = ForecastQualityMonitoringService.aggregate(
                List.of(
                        observation("AAPL", "m1", "c1", Horizon.D1, "0.8", "0.1", true),
                        observation("AAPL", "m1", "c1", Horizon.D1, "0.6", "-0.1", false),
                        observation("AAPL", "m1", "c1", Horizon.D1, "0.7", "0", false)),
                List.of(),
                2);

        var row = rows.getFirst();
        assertThat(row.sampleCount()).isEqualTo(3);
        assertThat(row.calibrationSampleCount()).isEqualTo(2);
        assertThat(row.hitRate()).isEqualByComparingTo("0.3333");
        assertThat(row.calibrationError()).isEqualByComparingTo("-0.2");
        assertThat(row.brierScore()).isEqualByComparingTo("0.2");
        assertThat(row.meanAbsoluteError()).isNull();
        assertThat(row.status()).isEqualTo(ForecastQualityMonitoringService.Status.SUFFICIENT);
    }

    @Test
    void calculatesExpectedReturnHitRateAndErrorForD5AndD20() {
        var rows = ForecastQualityMonitoringService.aggregate(
                List.of(
                        observation("AAPL", "m1", "c1", Horizon.D5, "0.10", "0.05", true),
                        observation("AAPL", "m1", "c1", Horizon.D5, "-0.10", "0.05", false),
                        observation("AAPL", "m1", "c1", Horizon.D20, "0.10", "0.00", false)),
                List.of(),
                2);

        var d5 = rows.stream().filter(row -> row.horizon() == Horizon.D5).findFirst().orElseThrow();
        assertThat(d5.hitRate()).isEqualByComparingTo("0.5");
        assertThat(d5.meanError()).isEqualByComparingTo("0.05");
        assertThat(d5.meanAbsoluteError()).isEqualByComparingTo("0.1");
        assertThat(d5.brierScore()).isNull();

        var d20 = rows.stream().filter(row -> row.horizon() == Horizon.D20).findFirst().orElseThrow();
        assertThat(d20.sampleCount()).isEqualTo(1);
        assertThat(d20.status()).isEqualTo(ForecastQualityMonitoringService.Status.DATA_SHORTAGE);
        assertThat(d20.hitRate()).isNull();
    }

    @Test
    void suppressesDriftAndDegradationWhenEitherPeriodHasTooFewSamples() {
        var rows = ForecastQualityMonitoringService.aggregate(
                List.of(observation("AAPL", "m1", "c1", Horizon.D1, "0.2", "-0.1", true)),
                List.of(observation("AAPL", "m1", "c1", Horizon.D1, "0.9", "0.1", true)),
                2);

        var row = rows.getFirst();
        assertThat(row.status()).isEqualTo(ForecastQualityMonitoringService.Status.DATA_SHORTAGE);
        assertThat(row.drift().status()).isEqualTo(ForecastQualityMonitoringService.DriftStatus.DATA_SHORTAGE);
        assertThat(row.drift().degraded()).isFalse();
        assertThat(row.drift().hitRateDelta()).isNull();
    }

    @Test
    void reportsDriftOnlyAfterBothPeriodsReachTheMinimumSampleCount() {
        var current = List.of(
                observation("AAPL", "m1", "c1", Horizon.D5, "0.10", "-0.10", false),
                observation("AAPL", "m1", "c1", Horizon.D5, "0.10", "-0.10", false));
        var baseline = List.of(
                observation("AAPL", "m1", "c1", Horizon.D5, "0.10", "0.10", true),
                observation("AAPL", "m1", "c1", Horizon.D5, "0.10", "0.10", true));

        var row = ForecastQualityMonitoringService.aggregate(current, baseline, 2)
                .getFirst();

        assertThat(row.status()).isEqualTo(ForecastQualityMonitoringService.Status.SUFFICIENT);
        assertThat(row.drift().status()).isEqualTo(ForecastQualityMonitoringService.DriftStatus.DRIFT);
        assertThat(row.drift().degraded()).isTrue();
        assertThat(row.drift().hitRateDelta()).isEqualByComparingTo("-1.0");
    }

    @Test
    void groupsQualityBySymbolAndModelContractVersion() {
        var rows = ForecastQualityMonitoringService.aggregate(
                List.of(
                        observation("AAPL", "m1", "c1", Horizon.D5, "0.1", "0.1", true),
                        observation("MSFT", "m2", "c2", Horizon.D5, "0.1", "0.1", true)),
                List.of(),
                1);

        assertThat(rows).extracting(
                ForecastQualityMonitoringService.QualityRow::symbol,
                ForecastQualityMonitoringService.QualityRow::modelVersion,
                ForecastQualityMonitoringService.QualityRow::contractVersion)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("AAPL", "m1", "c1"),
                        org.assertj.core.groups.Tuple.tuple("MSFT", "m2", "c2"));
    }

    private static ForecastQualityMonitoringService.Observation observation(
            String symbol,
            String modelVersion,
            String contractVersion,
            Horizon horizon,
            String forecastValue,
            String actualReturn,
            boolean directionCorrect
    ) {
        return new ForecastQualityMonitoringService.Observation(
                symbol,
                modelVersion,
                contractVersion,
                horizon,
                new BigDecimal(forecastValue),
                new BigDecimal(actualReturn),
                directionCorrect,
                true,
                true);
    }
}
