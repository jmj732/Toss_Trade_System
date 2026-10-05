package com.jmj.trade.investment.tactical;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

@ConfigurationProperties("investment.tactical-overlay")
public record TacticalOverlayProperties(
        int breakoutLookback,
        BigDecimal breakoutBufferRatio,
        BigDecimal extensionThreshold,
        BigDecimal highRvolThreshold,
        List<Integer> cohortHorizons,
        List<Integer> performanceHorizons,
        Duration markFreshness
) {

    public static TacticalOverlayProperties defaults() {
        return new TacticalOverlayProperties(0, null, null, null, null, null, null);
    }

    public TacticalOverlayProperties {
        if (breakoutLookback < 0) {
            throw new IllegalArgumentException("breakoutLookback must be positive");
        }
        breakoutLookback = breakoutLookback == 0 ? 20 : breakoutLookback;
        breakoutBufferRatio = breakoutBufferRatio == null ? BigDecimal.ZERO : breakoutBufferRatio;
        extensionThreshold = extensionThreshold == null ? new BigDecimal("0.10") : extensionThreshold;
        highRvolThreshold = highRvolThreshold == null ? new BigDecimal("2.0") : highRvolThreshold;
        cohortHorizons = positiveHorizons(cohortHorizons, List.of(3, 5), "cohortHorizons");
        performanceHorizons = positiveHorizons(performanceHorizons, List.of(5, 20), "performanceHorizons");
        markFreshness = markFreshness == null ? Duration.ofMinutes(15) : markFreshness;
        nonNegative(breakoutBufferRatio, "breakoutBufferRatio");
        nonNegative(extensionThreshold, "extensionThreshold");
        positive(highRvolThreshold, "highRvolThreshold");
        Objects.requireNonNull(markFreshness, "markFreshness");
        if (markFreshness.isZero() || markFreshness.isNegative()) {
            throw new IllegalArgumentException("markFreshness must be positive");
        }
    }

    private static List<Integer> positiveHorizons(List<Integer> values, List<Integer> defaults, String name) {
        var effective = values == null || values.isEmpty() ? defaults : values;
        if (effective.stream().anyMatch(value -> value == null || value <= 0)
                || effective.stream().distinct().count() != effective.size()) {
            throw new IllegalArgumentException(name + " must contain unique positive trading-bar horizons");
        }
        return effective.stream().sorted().toList();
    }

    private static void nonNegative(BigDecimal value, String name) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void positive(BigDecimal value, String name) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
