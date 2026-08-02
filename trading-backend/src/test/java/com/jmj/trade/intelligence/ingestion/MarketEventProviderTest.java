package com.jmj.trade.intelligence.ingestion;

import com.jmj.trade.intelligence.EventIntelligenceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketEventProviderTest {

    @Test
    void normalizedEventPreservesProviderIdentityAndNormalizesStockScope() {
        var event = new MarketEvent(
                MarketEventProviderId.SEC,
                "CIK0001045810:0001045810-26-000001",
                "SEC_8-K",
                "NVIDIA filing",
                Instant.parse("2026-08-01T12:00:00Z"),
                List.of(" nvda "),
                List.of());

        assertThat(event.provider()).isEqualTo(MarketEventProviderId.SEC);
        assertThat(event.sourceEventId()).startsWith("CIK0001045810:");
        assertThat(event.affectedSymbols()).containsExactly("NVDA");
        assertThat(event.macroScope()).isEmpty();
    }

    @Test
    void macroEventRequiresStableScopeAndKeepsObservationPeriod() {
        var event = new MarketEvent(
                MarketEventProviderId.FRED,
                "CPIAUCSL:2026-07:2026-07-01:2026-08-01",
                "FRED_OBSERVATION",
                "CPI observation",
                Instant.parse("2026-07-01T00:00:00Z"),
                List.of(),
                List.of(new EventIntelligenceService.MacroScope(
                        "fred", "CPIAUCSL", "2026-07")));

        assertThat(event.affectedSymbols()).isEmpty();
        assertThat(event.macroScope()).singleElement().satisfies(scope -> {
            assertThat(scope.provider()).isEqualTo("FRED");
            assertThat(scope.identifier()).isEqualTo("CPIAUCSL");
            assertThat(scope.period()).isEqualTo("2026-07");
        });
    }

    @Test
    void invalidEventIdentityIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> new MarketEvent(
                MarketEventProviderId.BLS,
                " ",
                "BLS_DATA",
                "value",
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryClassificationOnlyRetriesTransientProviderFailures() {
        assertThat(MarketEventHttpClient.retryable(408)).isTrue();
        assertThat(MarketEventHttpClient.retryable(429)).isTrue();
        assertThat(MarketEventHttpClient.retryable(503)).isTrue();
        assertThat(MarketEventHttpClient.retryable(404)).isFalse();
    }
}
