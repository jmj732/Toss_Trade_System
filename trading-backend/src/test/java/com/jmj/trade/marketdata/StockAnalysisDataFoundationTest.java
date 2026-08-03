package com.jmj.trade.marketdata;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StockAnalysisDataFoundationTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant SOURCE_AT = Instant.parse("2026-08-01T23:59:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsSameFieldFromDifferentProvidersAsSeparateObservedValues() {
        var providers = new StockDataProviderRegistry(List.of(
                provider(StockDataProviderId.FMP, "quote.price", "101"),
                provider(StockDataProviderId.FINNHUB, "quote.price", "102")));

        var input = new StockAnalysisInputAssembler(
                providers,
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)).assemble("AAPL", Map.of());

        assertThat(input.observations())
                .extracting(StockAnalysisInput.Observation::provider)
                .containsExactly(StockDataProviderId.FMP, StockDataProviderId.FINNHUB);
        assertThat(input.observations())
                .extracting(StockAnalysisInput.Observation::value)
                .extracting(value -> value.asText())
                .containsExactly("101", "102");
        assertThat(input.observations()).allSatisfy(observation -> {
            assertThat(observation.asOf()).isEqualTo(SOURCE_AT);
            assertThat(observation.collectedAt()).isEqualTo(COLLECTED_AT);
            assertThat(observation.missingData()).isEmpty();
        });
    }

    @Test
    void providerFailureOnlyMarksThatProvidersFieldsMissing() {
        var calls = new AtomicInteger();
        var healthy = provider(StockDataProviderId.SEC, "filing.revenue", "500");
        var failing = new StockDataProvider() {
            @Override
            public StockDataProviderId id() {
                return StockDataProviderId.FRED;
            }

            @Override
            public DataProviderRole role() {
                return DataProviderRole.MACRO;
            }

            @Override
            public Set<String> fields() {
                return Set.of("macro.gdp");
            }

            @Override
            public List<ProviderValue> fetch(ProviderRequest request) {
                calls.incrementAndGet();
                throw new ProviderUnavailableException(id(), "timeout");
            }
        };

        var input = new StockAnalysisInputAssembler(
                new StockDataProviderRegistry(List.of(healthy, failing)),
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)).assemble("AAPL", Map.of());

        assertThat(calls).hasValue(1);
        assertThat(input.degraded()).isTrue();
        assertThat(input.observations()).filteredOn(item -> item.provider() == StockDataProviderId.SEC)
                .singleElement()
                .satisfies(item -> assertThat(item.value().asText()).isEqualTo("500"));
        assertThat(input.observations()).filteredOn(item -> item.provider() == StockDataProviderId.FRED)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.value()).isNull();
                    assertThat(item.missingData()).containsExactly("PROVIDER_UNAVAILABLE");
                });
    }

    @Test
    void unexpectedProviderFailureIsSafeAndProviderSpecific() {
        var failing = new StockDataProvider() {
            @Override
            public StockDataProviderId id() {
                return StockDataProviderId.FMP;
            }

            @Override
            public DataProviderRole role() {
                return DataProviderRole.FUNDAMENTALS;
            }

            @Override
            public Set<String> fields() {
                return Set.of("quote.price");
            }

            @Override
            public List<ProviderValue> fetch(ProviderRequest request) {
                throw new IllegalStateException("unexpected test fault");
            }
        };

        var input = new StockAnalysisInputAssembler(
                new StockDataProviderRegistry(List.of(failing)),
                Clock.fixed(COLLECTED_AT, ZoneOffset.UTC)).assemble("AAPL", Map.of());

        assertThat(input.observations()).singleElement().satisfies(item -> {
            assertThat(item.provider()).isEqualTo(StockDataProviderId.FMP);
            assertThat(item.missingData()).containsExactly("PROVIDER_FAILURE");
        });
    }

    @Test
    void disabledProviderIsNotInRegistry() {
        var registry = StockDataProviderRegistry.optIn(
                Map.of(StockDataProviderId.FMP, false, StockDataProviderId.SEC, true),
                List.of(provider(StockDataProviderId.FMP, "quote.price", "101"),
                        provider(StockDataProviderId.SEC, "filing.revenue", "500")));

        assertThat(registry.providers()).extracting(StockDataProvider::id)
                .containsExactly(StockDataProviderId.SEC);
    }

    @Test
    void catalogCoversAllRequestedProvidersByRole() {
        assertThat(ProviderCatalog.roles()).containsExactlyInAnyOrderEntriesOf(Map.of(
                StockDataProviderId.TOSS, DataProviderRole.BROKER_ACCOUNT,
                StockDataProviderId.SEC, DataProviderRole.REGULATORY_FILINGS,
                StockDataProviderId.FRED, DataProviderRole.MACRO,
                StockDataProviderId.BLS, DataProviderRole.MACRO,
                StockDataProviderId.BEA, DataProviderRole.MACRO,
                StockDataProviderId.FED, DataProviderRole.MACRO,
                StockDataProviderId.FMP, DataProviderRole.FUNDAMENTALS,
                StockDataProviderId.FINNHUB, DataProviderRole.NEWS,
                StockDataProviderId.POLYGON, DataProviderRole.MARKET_DATA,
                StockDataProviderId.TWELVE_DATA, DataProviderRole.MARKET_DATA));
    }

    @Test
    void catalogKeepsProviderAuthenticationProfilesSeparate() {
        assertThat(ProviderCatalog.transportOf(StockDataProviderId.SEC).userAgentRequired()).isTrue();
        assertThat(ProviderCatalog.transportOf(StockDataProviderId.FRED)
                .defaultApiKeyQueryParameter()).isEqualTo("api_key");
        assertThat(ProviderCatalog.transportOf(StockDataProviderId.FMP)
                .defaultApiKeyQueryParameter()).isEqualTo("apikey");
        assertThat(ProviderCatalog.credentialsPresent(StockDataProviderId.FMP, "", "User-Agent"))
                .isFalse();
        assertThat(ProviderCatalog.credentialsPresent(StockDataProviderId.SEC, "secret", ""))
                .isFalse();
        assertThat(ProviderCatalog.credentialsPresent(StockDataProviderId.SEC, "", "User-Agent"))
                .isTrue();
    }

    private static StockDataProvider provider(
            StockDataProviderId id,
            String field,
            String value
    ) {
        return new StockDataProvider() {
            @Override
            public StockDataProviderId id() {
                return id;
            }

            @Override
            public DataProviderRole role() {
                return ProviderCatalog.roleOf(id);
            }

            @Override
            public Set<String> fields() {
                return Set.of(field);
            }

            @Override
            public List<ProviderValue> fetch(ProviderRequest request) {
                return List.of(new ProviderValue(field, new ObjectMapper().readTree(value), SOURCE_AT, List.of()));
            }
        };
    }
}
