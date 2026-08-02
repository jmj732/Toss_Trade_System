package com.jmj.trade.marketdata;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record ProviderValue(
        String field,
        JsonNode value,
        String unit,
        String period,
        String identifier,
        Instant asOf,
        List<String> missingData
) {

    public ProviderValue(String field, JsonNode value, Instant asOf, List<String> missingData) {
        this(field, value, null, null, null, asOf, missingData);
    }

    public ProviderValue {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field is required");
        }
        if (value != null && value.isNull()) {
            value = null;
        }
        missingData = missingData == null ? List.of() : List.copyOf(missingData);
        if (value == null && missingData.isEmpty()) {
            throw new IllegalArgumentException("null value requires missingData");
        }
        if (asOf == null && missingData.isEmpty()) {
            throw new IllegalArgumentException("missing asOf requires missingData");
        }
    }
}
