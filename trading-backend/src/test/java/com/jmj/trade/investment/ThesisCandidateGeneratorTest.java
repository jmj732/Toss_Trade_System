package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThesisCandidateGeneratorTest {

    @Test
    void atrWithInsufficientBarsReturnsUnverified() {
        var bars = new ArrayList<ThesisCandidateGenerator.BarData>();
        for (int i = 0; i < 10; i++) {
            bars.add(new ThesisCandidateGenerator.BarData(
                    bd(100), bd(105), bd(95), bd(100), bd(1000000)));
        }

        var result = ThesisCandidateGenerator.atrCandidate(bars);
        assertThat(result.verified).isFalse();
        assertThat(result.reason).isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    void atrWithConstantSpreadCalculatesCorrectly() {
        // Bars with constant H-L of 10 (high=105, low=95)
        var bars = new ArrayList<ThesisCandidateGenerator.BarData>();
        for (int i = 0; i < 20; i++) {
            bars.add(new ThesisCandidateGenerator.BarData(
                    bd(100), bd(105), bd(95), bd(100), bd(1000000)));
        }

        var result = ThesisCandidateGenerator.atrCandidate(bars);
        assertThat(result.verified).isTrue();
        // ATR should stabilize at 10 (H-L)
        // Candidate = 100 - 2*10 = 80
        assertThat(result.trigger).isNotNull();
        assertThat(result.trigger.toPlainString()).isEqualTo("80.0000");
    }

    @Test
    void atrWithNullPricesReturnsUnverified() {
        var bars = new ArrayList<ThesisCandidateGenerator.BarData>();
        bars.add(new ThesisCandidateGenerator.BarData(bd(100), null, bd(95), bd(100), bd(1000000)));
        for (int i = 1; i < 20; i++) {
            bars.add(new ThesisCandidateGenerator.BarData(
                    bd(100), bd(105), bd(95), bd(100), bd(1000000)));
        }

        var result = ThesisCandidateGenerator.atrCandidate(bars);
        // After filtering nulls, should have 19 bars - INSUFFICIENT_HISTORY
        assertThat(result.verified).isFalse();
    }

    @Test
    void supportWithInsufficientBarsReturnsUnverified() {
        var bars = new ArrayList<ThesisCandidateGenerator.BarData>();
        for (int i = 0; i < 15; i++) {
            bars.add(new ThesisCandidateGenerator.BarData(
                    bd(100), bd(105), bd(95), bd(100), bd(1000000)));
        }

        var result = ThesisCandidateGenerator.supportCandidate(bars);
        assertThat(result.verified).isFalse();
        assertThat(result.reason).isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    void supportCalculatesLowestLow() {
        var bars = new ArrayList<ThesisCandidateGenerator.BarData>();
        // First 10 bars with low=95
        for (int i = 0; i < 10; i++) {
            bars.add(new ThesisCandidateGenerator.BarData(
                    bd(100), bd(105), bd(95), bd(100), bd(1000000)));
        }
        // Last 10 bars with varying lows, min=80
        for (int i = 0; i < 10; i++) {
            var low = bd(100 - i); // 100, 99, 98, ..., 91
            bars.add(new ThesisCandidateGenerator.BarData(
                    bd(100), bd(105), low, bd(100), bd(1000000)));
        }

        var result = ThesisCandidateGenerator.supportCandidate(bars);
        assertThat(result.verified).isTrue();
        assertThat(result.trigger).isNotNull();
        assertThat(result.trigger.toPlainString()).isEqualTo("91.0000");
    }

    @Test
    void emptyBarsReturnsUnverified() {
        var result = ThesisCandidateGenerator.atrCandidate(new ArrayList<>());
        assertThat(result.verified).isFalse();
        assertThat(result.reason).isEqualTo("NO_DATA");

        result = ThesisCandidateGenerator.supportCandidate(new ArrayList<>());
        assertThat(result.verified).isFalse();
        assertThat(result.reason).isEqualTo("NO_DATA");
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }
}
