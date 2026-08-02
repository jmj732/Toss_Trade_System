package com.jmj.trade.marketdata;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StockAnalysisInput(
        UUID snapshotId,
        String symbol,
        String schemaVersion,
        Instant collectedAt,
        List<Observation> observations
) {

    public StockAnalysisInput {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public boolean degraded() {
        return observations.isEmpty() || observations.stream().anyMatch(item -> !item.missingData().isEmpty());
    }

    public record Observation(
            String field,
            JsonNode value,
            String unit,
            String period,
            String identifier,
            StockDataProviderId provider,
            Instant asOf,
            Instant collectedAt,
            List<String> missingData
    ) {

        public Observation {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(collectedAt, "collectedAt");
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
}
