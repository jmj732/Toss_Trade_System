package com.jmj.trade.marketdata;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    ConfiguredStockDataProvider(
            StockDataProviderId id,
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper
    ) {
        this.id = id;
        this.role = ProviderCatalog.roleOf(id);
        this.configuration = configuration;
        this.transport = new ProviderHttpTransport(id, configuration);
        this.objectMapper = objectMapper;
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
        return configuration.fields().keySet();
    }

    @Override
    public List<ProviderValue> fetch(ProviderRequest request) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(transport.get(request));
        } catch (JacksonException exception) {
            throw new ProviderUnavailableException(id, "INVALID_RESPONSE");
        }
        var values = new ArrayList<ProviderValue>();
        configuration.fields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.add(value(root, request, entry.getKey(), entry.getValue())));
        return List.copyOf(values);
    }

    private ProviderValue value(JsonNode root, ProviderRequest request, String field, String pointer) {
        var asOf = asOf(root, field);
        var node = pointer == null || pointer.isBlank() ? root : root.at(pointer);
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
                configuration.units().get(field),
                configuration.periods().get(field),
                resolve(configuration.identifiers().get(field), request),
                asOf,
                missing);
    }

    private Instant asOf(JsonNode root, String field) {
        var path = configuration.asOfPaths().getOrDefault(field, configuration.asOfPath());
        if (path == null || path.isBlank()) {
            return null;
        }
        var node = root.at(path);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return switch (configuration.asOfFormat()) {
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
