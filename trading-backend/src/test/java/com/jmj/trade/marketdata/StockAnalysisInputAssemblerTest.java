package com.jmj.trade.marketdata;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StockAnalysisInputAssemblerTest {

    private static final String DILUTED_SHARES = "fundamental.dilutedShares";
    private static final Instant AS_OF = Instant.parse("2026-10-03T11:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void verifiedSecDilutedSharesSuppressAlphaFallback() {
        var sec = provider(StockDataProviderId.SEC,
                dilutedShares("shares", AS_OF, List.of()));
        var alpha = provider(StockDataProviderId.ALPHA_VANTAGE,
                dilutedShares("shares", AS_OF, List.of()));

        var input = assemble(sec, alpha);

        assertThat(alpha.calls()).hasValue(0);
        assertThat(input.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.provider()).isEqualTo(StockDataProviderId.SEC);
            assertThat(observation.unit()).isEqualTo("shares");
        });
    }

    @Test
    void unverifiedSecDilutedSharesAllowAlphaFallback() {
        var unverified = List.of(
                dilutedShares("USD", AS_OF, List.of()),
                dilutedShares("shares", null, List.of("AS_OF_UNAVAILABLE")),
                dilutedShares("shares", NOW.plusSeconds(1), List.of()),
                dilutedShares("shares", AS_OF, List.of("SOURCE_CONFLICT")));

        for (var value : unverified) {
            var sec = provider(StockDataProviderId.SEC, value);
            var alpha = provider(StockDataProviderId.ALPHA_VANTAGE,
                    dilutedShares("shares", AS_OF, List.of()));

            var input = assemble(sec, alpha);

            assertThat(alpha.calls()).hasValue(1);
            assertThat(alpha.selectedFields()).hasValue(Set.of(DILUTED_SHARES));
            assertThat(input.observations()).anySatisfy(observation -> {
                assertThat(observation.provider()).isEqualTo(StockDataProviderId.ALPHA_VANTAGE);
                assertThat(observation.field()).isEqualTo(DILUTED_SHARES);
            });
        }
    }

    @Test
    void alphaKeyFailuresKeepTheirSafeReasonAndOrdinarySecAbsenceStaysGeneric() {
        for (var code : List.of("INVALID_API_KEY", "API_KEY_UNAVAILABLE", "RATE_LIMITED", "PREMIUM_ENDPOINT")) {
            var input = new StockAnalysisInputAssembler(
                    new StockDataProviderRegistry(List.of(failingProvider(
                            StockDataProviderId.ALPHA_VANTAGE, "consensus.epsConsensus", code))), CLOCK)
                    .assemble("AVT", Map.of());

            assertThat(input.observations()).singleElement().satisfies(observation -> {
                assertThat(observation.missingData()).containsExactly("PROVIDER_UNAVAILABLE", "PROVIDER_" + code);
            });
        }

        var secInput = new StockAnalysisInputAssembler(
                new StockDataProviderRegistry(List.of(failingProvider(
                        StockDataProviderId.SEC, "fundamental.cash", "DATA_NOT_PRESENT"))), CLOCK)
                .assemble("AVT", Map.of());

        assertThat(secInput.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.provider()).isEqualTo(StockDataProviderId.SEC);
            assertThat(observation.missingData()).containsExactly("PROVIDER_UNAVAILABLE");
        });
    }

    private static StockDataProvider failingProvider(StockDataProviderId id, String field, String reason) {
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
                throw new ProviderUnavailableException(id, reason);
            }
        };
    }

    private static StockAnalysisInput assemble(StockDataProvider sec, StockDataProvider alpha) {
        return new StockAnalysisInputAssembler(
                new StockDataProviderRegistry(List.of(sec, alpha)), CLOCK)
                .assemble("AVT", Map.of());
    }

    private static ProviderValue dilutedShares(String unit, Instant asOf, List<String> missingData) {
        return new ProviderValue(DILUTED_SHARES, MAPPER.valueToTree(new BigDecimal("1000")),
                unit, "FY2025", "shares_fact", asOf, missingData);
    }

    private static TestProvider provider(StockDataProviderId id, ProviderValue value) {
        return new TestProvider(id, value);
    }

    private static final class TestProvider implements StockDataProvider {
        private final StockDataProviderId id;
        private final ProviderValue value;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Set<String>> selectedFields = new AtomicReference<>();

        private TestProvider(StockDataProviderId id, ProviderValue value) {
            this.id = id;
            this.value = value;
        }

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
            return Set.of(DILUTED_SHARES);
        }

        @Override
        public List<ProviderValue> fetch(ProviderRequest request) {
            calls.incrementAndGet();
            return List.of(value);
        }

        @Override
        public List<ProviderValue> fetch(ProviderRequest request, Set<String> fields) {
            calls.incrementAndGet();
            selectedFields.set(Set.copyOf(fields));
            return fields.contains(DILUTED_SHARES) ? List.of(value) : List.of();
        }

        private AtomicInteger calls() {
            return calls;
        }

        private AtomicReference<Set<String>> selectedFields() {
            return selectedFields;
        }
    }
}
