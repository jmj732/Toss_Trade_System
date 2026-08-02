package com.jmj.trade.intelligence.ingestion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketEventProviderRegistryTest {

    @Test
    void registryKeepsOnlyOneProviderPerStableProviderId() {
        var registry = new MarketEventProviderRegistry(List.of(
                provider(MarketEventProviderId.SEC),
                provider(MarketEventProviderId.FRED)));

        assertThat(registry.providers()).extracting(MarketEventProvider::id)
                .containsExactly(MarketEventProviderId.FRED, MarketEventProviderId.SEC);
    }

    @Test
    void duplicateProviderIdsAreRejected() {
        assertThatThrownBy(() -> new MarketEventProviderRegistry(List.of(
                provider(MarketEventProviderId.SEC),
                provider(MarketEventProviderId.SEC))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private static MarketEventProvider provider(MarketEventProviderId id) {
        return new MarketEventProvider() {
            @Override
            public MarketEventProviderId id() {
                return id;
            }

            @Override
            public List<MarketEvent> collect(Request request) {
                return List.of(new MarketEvent(
                        id, id.name() + "-event", "TEST", "test",
                        Instant.parse("2026-08-01T00:00:00Z"), List.of(), List.of()));
            }
        };
    }
}
