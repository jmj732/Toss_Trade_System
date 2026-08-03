package com.jmj.trade.marketdata;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

public final class StockAnalysisInputAssembler {

    private final StockDataProviderRegistry registry;
    private final Clock clock;

    public StockAnalysisInputAssembler(
            StockDataProviderRegistry registry,
            Clock clock
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StockAnalysisInput assemble(String symbol, Map<String, String> identifiers) {
        var request = new ProviderRequest(symbol, identifiers);
        var observations = new ArrayList<StockAnalysisInput.Observation>();
        for (var provider : registry.providers()) {
            collect(provider, request, observations);
        }
        var collectedAt = clock.instant();
        return new StockAnalysisInput(
                UUID.randomUUID(),
                symbol,
                "1",
                collectedAt,
                List.copyOf(observations));
    }

    private void collect(
            StockDataProvider provider,
            ProviderRequest request,
            List<StockAnalysisInput.Observation> target
    ) {
        Set<String> declared = Set.of();
        try {
            declared = provider.fields();
            if (declared == null) {
                declared = Set.of("provider");
                throw new IllegalStateException("provider fields are required");
            }
            var values = provider.fetch(request);
            if (values == null) {
                throw new IllegalStateException("provider returned null values");
            }
            var collectedAt = clock.instant();
            var returned = new HashSet<String>();
            for (var value : values) {
                if (value == null || !declared.contains(value.field()) || !returned.add(value.field())) {
                    throw new IllegalStateException("provider returned undeclared or duplicate field");
                }
                target.add(new StockAnalysisInput.Observation(
                        value.field(),
                        value.value(),
                        value.unit(),
                        value.period(),
                        value.identifier(),
                        provider.id(),
                        value.asOf(),
                        collectedAt,
                        value.missingData()));
            }
            declared.stream()
                    .filter(field -> !returned.contains(field))
                    .sorted()
                    .forEach(field -> target.add(missing(
                            field, provider.id(), collectedAt, "PROVIDER_FIELD_MISSING")));
        } catch (ProviderUnavailableException exception) {
            var collectedAt = clock.instant();
            declared.stream()
                    .sorted()
                    .forEach(field -> target.add(missing(
                            field, provider.id(), collectedAt, "PROVIDER_UNAVAILABLE")));
        } catch (RuntimeException exception) {
            var collectedAt = clock.instant();
            var fields = declared.isEmpty() ? Set.of("provider") : declared;
            fields.stream()
                    .sorted()
                    .forEach(field -> target.add(missing(
                            field, provider.id(), collectedAt, "PROVIDER_FAILURE")));
        }
    }

    private static StockAnalysisInput.Observation missing(
            String field,
            StockDataProviderId provider,
            Instant collectedAt,
            String reason
    ) {
        return new StockAnalysisInput.Observation(
                field, null, null, null, null, provider, null, collectedAt, List.of(reason));
    }
}
