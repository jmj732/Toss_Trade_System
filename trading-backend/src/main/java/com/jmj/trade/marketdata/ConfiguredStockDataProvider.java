package com.jmj.trade.marketdata;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfiguredStockDataProvider implements StockDataProvider {

    private final StockDataProviderId id;
    private final DataProviderRole role;
    private final StockAnalysisProviderProperties.ProviderConfiguration configuration;
    private final ProviderHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ConfiguredStockDataProvider(
            StockDataProviderId id,
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper
    ) {
        this(id, configuration, objectMapper, Clock.systemUTC());
    }

    ConfiguredStockDataProvider(
            StockDataProviderId id,
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.id = id;
        this.role = ProviderCatalog.roleOf(id);
        this.configuration = configuration;
        this.transport = new ProviderHttpTransport(id, configuration);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public StockDataProviderId id() {
        return id;
    }

    @Override
    public DataProviderRole role() {
        return role;
    }

    @Override
    public Set<String> fields() {
        return configuration.configuredFields();
    }

    @Override
    public List<ProviderValue> fetch(ProviderRequest request) {
        return fetch(request, null);
    }

    @Override
    public List<ProviderValue> fetch(ProviderRequest request, Set<String> selectedFields) {
        var values = new ArrayList<ProviderValue>();
        var isolateEndpointFailures = !configuration.endpoints().isEmpty();
        for (var endpoint : configuration.configuredEndpoints()) {
            var selectedEndpointFields = selectedFields == null ? endpoint.fields().keySet()
                    : endpoint.fields().keySet().stream().filter(selectedFields::contains)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            // Static session metadata never selects an endpoint by itself.
            if (selectedFields != null && selectedEndpointFields.isEmpty()) continue;
            try {
                values.addAll(fetch(endpoint, request, selectedEndpointFields,
                        selectedFields == null || selectedFields.contains("price.session")));
            } catch (ProviderUnavailableException exception) {
                if (!isolateEndpointFailures) throw exception;
                endpoint.declaredFields().stream()
                        .filter(field -> selectedFields == null || selectedFields.contains(field))
                        .sorted().forEach(field -> values.add(new ProviderValue(
                        field, null, null, null, null, null,
                        List.of("PROVIDER_UNAVAILABLE", "PROVIDER_" + exception.reasonCode()))));
            }
        }
        return List.copyOf(values);
    }

    private List<ProviderValue> fetch(StockAnalysisProviderProperties.EndpointConfiguration endpoint,
                                      ProviderRequest request, Set<String> selectedEndpointFields,
                                      boolean includeSessionMetadata) {
        final JsonNode root;
        try {
            var response = transport.get(request, endpoint);
            var observedAt = clock.instant();
            root = objectMapper.readTree(response);
            var values = new ArrayList<ProviderValue>();
            endpoint.fields().entrySet().stream()
                    .filter(entry -> selectedEndpointFields.contains(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> values.add(value(root, request, endpoint, entry.getKey(), entry.getValue(), observedAt)));
            if (includeSessionMetadata && endpoint.priceSession() != null) {
                var asOf = asOf(root, endpoint, "price.session", observedAt);
                values.add(new ProviderValue(
                        "price.session", objectMapper.valueToTree(endpoint.priceSession().name()),
                        null, null, null, asOf,
                        asOf == null && endpoint.asOfMode() == StockAnalysisProviderProperties.AsOfMode.SOURCE_AS_OF
                                ? List.of("AS_OF_UNAVAILABLE") : List.of(),
                        asOfBasis(endpoint)));
            }
            return List.copyOf(values);
        } catch (JacksonException exception) {
            throw new ProviderUnavailableException(id, "INVALID_RESPONSE");
        }
    }

    private ProviderValue value(JsonNode root, ProviderRequest request,
                                StockAnalysisProviderProperties.EndpointConfiguration endpoint,
                                String field, String pointer, Instant observedAt) {
        var asOf = asOf(root, endpoint, field, observedAt);
        var node = pointer == null || pointer.isEmpty() ? root : root.at(pointer);
        var missing = new ArrayList<String>();
        if (node.isMissingNode() || node.isNull()) {
            node = null;
            missing.add("DATA_NOT_PRESENT");
        }
        if (asOf == null) {
            missing.add("AS_OF_UNAVAILABLE");
        }
        return new ProviderValue(
                field,
                node,
                endpoint.units().get(field),
                endpoint.periods().get(field),
                resolve(endpoint.identifiers().get(field), request),
                asOf,
                missing,
                asOfBasis(endpoint));
    }

    private static StockAnalysisInput.AsOfBasis asOfBasis(
            StockAnalysisProviderProperties.EndpointConfiguration endpoint
    ) {
        return endpoint.asOfMode() == StockAnalysisProviderProperties.AsOfMode.OBSERVED_AT
                ? StockAnalysisInput.AsOfBasis.OBSERVED_AT : StockAnalysisInput.AsOfBasis.SOURCE_AS_OF;
    }

    private Instant asOf(JsonNode root, StockAnalysisProviderProperties.EndpointConfiguration endpoint,
                         String field, Instant observedAt) {
        if (endpoint.asOfMode() == StockAnalysisProviderProperties.AsOfMode.OBSERVED_AT) {
            return observedAt;
        }
        var path = endpoint.asOfPaths().getOrDefault(field, endpoint.asOfPath());
        if (path == null || path.isBlank()) {
            return null;
        }
        var node = root.at(path);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return switch (endpoint.asOfFormat()) {
                case "EPOCH_SECONDS" -> Instant.ofEpochSecond(Long.parseLong(node.asText()));
                case "EPOCH_MILLIS" -> Instant.ofEpochMilli(Long.parseLong(node.asText()));
                case "DATE" -> LocalDate.parse(node.asText()).atStartOfDay(ZoneOffset.UTC).toInstant();
                default -> Instant.parse(node.asText());
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String resolve(String template, ProviderRequest request) {
        if (template == null || template.isBlank()) {
            return template;
        }
        var resolved = template.replace("{symbol}", request.symbol());
        for (var entry : request.identifiers().entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }
}
