package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentDataCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-01T16:00:00Z");

    @Test
    void flagsSameSessionQuotesAtTheHalfPercentBoundary() {
        var result = InvestmentDataCalculator.assessPrices(List.of(
                new InvestmentDataCalculator.SourceQuote("POLYGON", bd("100"), NOW, "LIVE_REGULAR"),
                new InvestmentDataCalculator.SourceQuote("TWELVE_DATA", bd("100.5"), NOW, "LIVE_REGULAR")
        ), NOW, Duration.ofMinutes(15));

        assertThat(result.status()).isEqualTo(InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT);
        assertThat(result.source()).isEqualTo("POLYGON");
        assertThat(result.secondarySource()).isEqualTo("TWELVE_DATA");
    }

    @Test
    void doesNotComparePricesFromDifferentSessions() {
        var result = InvestmentDataCalculator.assessPrices(List.of(
                new InvestmentDataCalculator.SourceQuote("POLYGON", bd("100"), NOW, "REGULAR_CLOSE"),
                new InvestmentDataCalculator.SourceQuote("TWELVE_DATA", bd("102"), NOW, "AFTER_HOURS")
        ), NOW, Duration.ofMinutes(15));

        assertThat(result.status()).isEqualTo(InvestmentDataCalculator.DataStatus.OK);
    }

    @Test
    void preservesRegularCloseButMarksLatestPricePartialWhenNoQuoteExists() {
        var result = InvestmentDataCalculator.assessPrices(List.of(
                new InvestmentDataCalculator.SourceQuote("POLYGON", null, null, null,
                        bd("100"), NOW.minus(Duration.ofHours(1)))
        ), NOW, Duration.ofMinutes(15));

        assertThat(result.regularClose()).isEqualByComparingTo("100");
        assertThat(result.latestPrice()).isNull();
        assertThat(result.status()).isEqualTo(InvestmentDataCalculator.DataStatus.PARTIAL);
    }

    @Test
    void treatsCurrentRegularCloseAsFreshOutsideTheIntradayQuoteWindow() {
        var result = InvestmentDataCalculator.assessPrices(List.of(
                new InvestmentDataCalculator.SourceQuote("POLYGON", bd("100"),
                        NOW.minus(Duration.ofHours(6)), "REGULAR_CLOSE")
        ), NOW, Duration.ofMinutes(15));

        assertThat(result.status()).isEqualTo(InvestmentDataCalculator.DataStatus.OK);
    }

    @Test
    void missingRevisionHistoryIsDistinctFromMissingCurrentConsensus() {
        var current = NOW;
        var result = InvestmentDataCalculator.revision(
                bd("12"), current, List.of(new InvestmentDataCalculator.ConsensusValue(
                        NOW.minus(Duration.ofDays(29)), bd("10"))), Duration.ofDays(30));

        assertThat(result.status().name()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(result.value()).isNull();

        var noCurrent = InvestmentDataCalculator.revision(null, current, List.of(), Duration.ofDays(30));
        assertThat(noCurrent.status()).isEqualTo(InvestmentDataCalculator.DataStatus.DATA_MISSING);
    }

    @Test
    void calculatesRevisionFromTheNearestPriorSnapshotWithoutBackfilling() {
        var result = InvestmentDataCalculator.revision(
                bd("12"), NOW, List.of(
                        new InvestmentDataCalculator.ConsensusValue(NOW.minus(Duration.ofDays(31)), bd("10")),
                        new InvestmentDataCalculator.ConsensusValue(NOW.minus(Duration.ofDays(29)), bd("11"))
                ), Duration.ofDays(30));

        assertThat(result.status()).isEqualTo(InvestmentDataCalculator.DataStatus.OK);
        assertThat(result.value()).isEqualByComparingTo("20.0000");
        assertThat(result.baselineAsOf()).isEqualTo(NOW.minus(Duration.ofDays(31)));
    }

    @Test
    void calculatesRevisionForNegativeEpsConsensus() {
        var result = InvestmentDataCalculator.revision(
                bd("-5"), NOW, List.of(new InvestmentDataCalculator.ConsensusValue(
                        NOW.minus(Duration.ofDays(31)), bd("-10"))), Duration.ofDays(30));

        assertThat(result.status()).isEqualTo(InvestmentDataCalculator.DataStatus.OK);
        assertThat(result.value()).isEqualByComparingTo("50.0000");
    }

    @Test
    void distinguishesAZeroBaselineFromMissingHistory() {
        var baselineAsOf = NOW.minus(Duration.ofDays(31));
        var result = InvestmentDataCalculator.revision(
                bd("12"), NOW, List.of(new InvestmentDataCalculator.ConsensusValue(baselineAsOf, BigDecimal.ZERO)),
                Duration.ofDays(30));

        assertThat(result.status()).isEqualTo(InvestmentDataCalculator.DataStatus.UNVERIFIED);
        assertThat(result.baselineAsOf()).isEqualTo(baselineAsOf);
        assertThat(result.reason()).isEqualTo("ZERO_BASELINE");
    }

    @Test
    void returnsPearsonCorrelationForAlignedReturns() {
        assertThat(InvestmentDataCalculator.correlation(
                List.of(bd("1"), bd("2"), bd("3"), bd("4")),
                List.of(bd("2"), bd("4"), bd("6"), bd("8"))))
                .isEqualByComparingTo("1.00000000");
    }

    @Test
    void leavesCorrelationMissingWhenEitherSeriesHasNoVariance() {
        assertThat(InvestmentDataCalculator.correlation(
                List.of(bd("1"), bd("1"), bd("1")),
                List.of(bd("2"), bd("3"), bd("4"))))
                .isNull();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
