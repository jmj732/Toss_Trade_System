package com.jmj.trade.analysis;

import com.jmj.trade.marketdata.StockAnalysisInput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.TreeMap;

public final class StockAnalysisSnapshotHasher {

    private final ObjectMapper objectMapper;

    public StockAnalysisSnapshotHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(StockAnalysisInput input) {
        return hashCanonical(canonicalJson(input));
    }

    public String canonicalJson(StockAnalysisInput input) {
        try {
            var canonical = new LinkedHashMap<String, Object>();
            canonical.put("snapshotId", input.snapshotId());
            canonical.put("symbol", input.symbol());
            canonical.put("schemaVersion", input.schemaVersion());
            canonical.put("collectedAt", input.collectedAt());
            var observations = new ArrayList<CanonicalObservation>();
            for (var observation : input.observations()) {
                var value = canonicalObservation(observation);
                observations.add(new CanonicalObservation(value, objectMapper.writeValueAsString(value)));
            }
            observations.sort(Comparator.comparing(CanonicalObservation::serialized));
            canonical.put("observations", observations.stream().map(CanonicalObservation::value).toList());
            return objectMapper.writeValueAsString(canonical);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stock analysis snapshot serialization unavailable", exception);
        }
    }

    public String hashCanonical(String canonicalJson) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("stock analysis snapshot hash unavailable", exception);
        }
    }

    private static LinkedHashMap<String, Object> canonicalObservation(
            StockAnalysisInput.Observation observation
    ) {
        var value = new LinkedHashMap<String, Object>();
        value.put("field", observation.field());
        value.put("value", canonicalValue(observation.value()));
        value.put("unit", observation.unit());
        value.put("period", observation.period());
        value.put("identifier", observation.identifier());
        value.put("provider", observation.provider());
        value.put("asOf", observation.asOf());
        value.put("collectedAt", observation.collectedAt());
        value.put("missingData", observation.missingData().stream().sorted().toList());
        return value;
    }

    private static Object canonicalValue(tools.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            var fields = new TreeMap<String, Object>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), canonicalValue(entry.getValue())));
            return fields;
        }
        if (node.isArray()) {
            var values = new java.util.ArrayList<Object>();
            node.forEach(item -> values.add(canonicalValue(item)));
            return values;
        }
        return node;
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private record CanonicalObservation(
            LinkedHashMap<String, Object> value,
            String serialized
    ) {
    }
}
