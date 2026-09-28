package com.jmj.trade.intelligence.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketEventIngestionPropertiesTest {

    @Test
    void fredDefaultsToLongLookbackWhileOtherProvidersUseGlobalLookback() {
        var properties = defaults();

        assertThat(properties.lookback()).isEqualTo(Duration.ofDays(2));
        assertThat(properties.lookback(MarketEventProviderId.FRED)).isEqualTo(Duration.ofDays(45));
        assertThat(properties.lookback(MarketEventProviderId.SEC)).isEqualTo(properties.lookback());
        assertThat(properties.lookback(MarketEventProviderId.IR)).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void perProviderLookbackOverrideBindsFromKebabCaseConfiguration() {
        var source = new MapConfigurationPropertySource(Map.of(
                "market-events.lookback", "P3D",
                "market-events.provider-lookbacks.fred", "P10D",
                "market-events.provider-lookbacks.sec", "PT36H"));

        var properties = new Binder(source)
                .bind("market-events", MarketEventIngestionProperties.class)
                .get();

        assertThat(properties.lookback(MarketEventProviderId.FRED)).isEqualTo(Duration.ofDays(10));
        assertThat(properties.lookback(MarketEventProviderId.SEC)).isEqualTo(Duration.ofHours(36));
        // A provider without an explicit override falls back to the global lookback.
        assertThat(properties.lookback(MarketEventProviderId.BLS)).isEqualTo(Duration.ofDays(3));
    }

    @Test
    void nonPositiveProviderLookbackIsRejected() {
        var lookbacks = new HashMap<String, Duration>();
        lookbacks.put("fred", Duration.ZERO);

        assertThatThrownBy(() -> withProviderLookbacks(lookbacks))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerLookbacks");
    }

    private static MarketEventIngestionProperties defaults() {
        return withProviderLookbacks(null);
    }

    private static MarketEventIngestionProperties withProviderLookbacks(Map<String, Duration> providerLookbacks) {
        return new MarketEventIngestionProperties(
                null, null, null, null, null, null, null,
                0, 0, 0, Map.of(), null, providerLookbacks);
    }
}
