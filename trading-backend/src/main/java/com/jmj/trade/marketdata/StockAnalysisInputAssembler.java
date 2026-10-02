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
        return assemble(symbol, identifiers, null);
    }

    public StockAnalysisInput assemble(String symbol, Map<String, String> identifiers, Set<String> selectedFields) {
        var request = new ProviderRequest(symbol, identifiers);
        var observations = new ArrayList<StockAnalysisInput.Observation>();
        for (var provider : registry.providers()) {
            if (selectedFields == null) {
                collect(provider, request, observations, null);
                continue;
            }
            final Set<String> declared;
            try {
                declared = provider.fields();
            } catch (RuntimeException exception) {
                collect(provider, request, observations, Set.of("provider"));
                continue;
            }
            if (declared == null) {
                collect(provider, request, observations, Set.of("provider"));
                continue;
            }
            var selected = declared.stream().filter(selectedFields::contains)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!selected.isEmpty()) collect(provider, request, observations, selected);
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
            List<StockAnalysisInput.Observation> target,
            Set<String> selectedFields
    ) {
        Set<String> declared = Set.of();
        try {
            declared = provider.fields();
            if (declared == null) {
                declared = Set.of("provider");
                throw new IllegalStateException("provider fields are required");
            }
            var values = selectedFields == null ? provider.fetch(request) : provider.fetch(request, selectedFields);
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
                        value.missingData(),
                        value.asOfBasis()));
            }
            declared.stream()
                    .filter(field -> selectedFields == null || selectedFields.contains(field))
                    .filter(field -> !returned.contains(field))
                    .sorted()
                    .forEach(field -> target.add(missing(
                            field, provider.id(), collectedAt, "PROVIDER_FIELD_MISSING")));
        } catch (ProviderUnavailableException exception) {
            var collectedAt = clock.instant();
            var reason = exception.reasonCode();
            var missingData = reason.matches("HTTP_[1-5][0-9]{2}")
                    ? List.of("PROVIDER_UNAVAILABLE", "PROVIDER_" + reason)
                    : List.of("PROVIDER_UNAVAILABLE");
            declared.stream()
                    .filter(field -> selectedFields == null || selectedFields.contains(field))
                    .sorted()
                    .forEach(field -> target.add(missing(
                            field, provider.id(), collectedAt, missingData)));
        } catch (RuntimeException exception) {
            var collectedAt = clock.instant();
            var fields = declared.isEmpty() ? Set.of("provider") : declared;
            fields.stream()
                    .filter(field -> selectedFields == null || selectedFields.contains(field))
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
        return missing(field, provider, collectedAt, List.of(reason));
    }

    private static StockAnalysisInput.Observation missing(
            String field,
            StockDataProviderId provider,
            Instant collectedAt,
            List<String> reasons
    ) {
        return new StockAnalysisInput.Observation(
                field, null, null, null, null, provider, null, collectedAt, reasons);
    }
}
