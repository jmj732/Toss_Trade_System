package com.jmj.trade.investment.tactical;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TacticalOverlayPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TacticalOverlayConfiguration.class);

    @Test
    void registersCalculatorAndBindsDocumentedTacticalDefaultsAndOverrides() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TacticalOverlayCalculator.class);
            var defaults = context.getBean(TacticalOverlayProperties.class);
            assertThat(defaults.breakoutLookback()).isEqualTo(20);
            assertThat(defaults.breakoutBufferRatio()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(defaults.extensionThreshold()).isEqualByComparingTo("0.10");
            assertThat(defaults.highRvolThreshold()).isEqualByComparingTo("2.0");
            assertThat(defaults.cohortHorizons()).containsExactly(3, 5);
            assertThat(defaults.performanceHorizons()).containsExactly(5, 20);
            assertThat(defaults.markFreshness()).isEqualTo(Duration.ofMinutes(15));
        });

        new ApplicationContextRunner().withUserConfiguration(TacticalOverlayConfiguration.class)
                .withPropertyValues(
                        "investment.tactical-overlay.breakout-lookback=30",
                        "investment.tactical-overlay.breakout-buffer-ratio=0.01",
                        "investment.tactical-overlay.extension-threshold=0.15",
                        "investment.tactical-overlay.high-rvol-threshold=2.5",
                        "investment.tactical-overlay.cohort-horizons[0]=3",
                        "investment.tactical-overlay.cohort-horizons[1]=5",
                        "investment.tactical-overlay.mark-freshness=PT10M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(TacticalOverlayProperties.class);
                    assertThat(properties.breakoutLookback()).isEqualTo(30);
                    assertThat(properties.breakoutBufferRatio()).isEqualByComparingTo("0.01");
                    assertThat(properties.extensionThreshold()).isEqualByComparingTo("0.15");
                    assertThat(properties.highRvolThreshold()).isEqualByComparingTo("2.5");
                    assertThat(properties.markFreshness()).isEqualTo(Duration.ofMinutes(10));
                });
    }

    @Test
    void rejectsNegativeThresholdsAndInvalidHorizons() {
        assertThatThrownBy(() -> new TacticalOverlayProperties(20, new BigDecimal("-0.01"),
                new BigDecimal("0.10"), new BigDecimal("2"), List.of(3, 5), List.of(5, 20),
                Duration.ofMinutes(15))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TacticalOverlayProperties(20, BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("2"), List.of(3, 3), List.of(5, 20),
                Duration.ofMinutes(15))).isInstanceOf(IllegalArgumentException.class);
    }
}
