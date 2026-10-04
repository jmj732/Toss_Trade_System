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
        var providers = registry.providers();
        for (var provider : providers) {
            if (provider.id() == StockDataProviderId.ALPHA_VANTAGE) continue;
            collectSelected(provider, request, observations, selectedFields);
        }
        var shareFallbackCheckAt = clock.instant();
        var secDilutedSharesPresent = observations.stream()
                .anyMatch(observation -> observation.provider() == StockDataProviderId.SEC
                        && "fundamental.dilutedShares".equals(observation.field())
                        && observation.value() != null && decimalPositive(observation.value())
                        && "shares".equalsIgnoreCase(observation.unit())
                        && observation.asOf() != null
                        && !observation.asOf().isAfter(shareFallbackCheckAt)
                        && observation.missingData().isEmpty());
        for (var provider : providers) {
            if (provider.id() != StockDataProviderId.ALPHA_VANTAGE) continue;
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
            var selected = declared.stream()
                    .filter(field -> selectedFields == null || selectedFields.contains(field))
                    .filter(field -> !secDilutedSharesPresent || !"fundamental.dilutedShares".equals(field))
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

    private void collectSelected(StockDataProvider provider, ProviderRequest request,
                                 List<StockAnalysisInput.Observation> observations,
                                 Set<String> selectedFields) {
        if (selectedFields == null) {
            collect(provider, request, observations, null);
            return;
        }
        final Set<String> declared;
        try {
            declared = provider.fields();
        } catch (RuntimeException exception) {
            collect(provider, request, observations, Set.of("provider"));
            return;
        }
        if (declared == null) {
            collect(provider, request, observations, Set.of("provider"));
            return;
        }
        var selected = declared.stream().filter(selectedFields::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!selected.isEmpty()) collect(provider, request, observations, selected);
    }

    private static boolean decimalPositive(tools.jackson.databind.JsonNode value) {
        if (value == null || value.isNull()) return false;
        try {
            return new java.math.BigDecimal(value.asText()).signum() > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
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
            var missingData = safeProviderReason(reason)
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

    private static boolean safeProviderReason(String reason) {
        return reason != null && (reason.matches("HTTP_[1-5][0-9]{2}")
                || Set.of("DAILY_QUOTA_EXHAUSTED", "REQUEST_IN_PROGRESS", "CACHE_UNAVAILABLE",
                "CACHE_CORRUPT", "API_ERROR", "INVALID_RESPONSE", "SOURCE_CONFLICT", "SYMBOL_MISMATCH",
                "CLIENT", "EMPTY_RESPONSE", "INTERRUPTED", "NETWORK", "ISSUER_MISMATCH",
                "NO_RECENT_FILING", "SYMBOL_NOT_FOUND", "INVALID_API_KEY", "API_KEY_UNAVAILABLE",
                "RATE_LIMITED", "PREMIUM_ENDPOINT").contains(reason));
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
