package com.jmj.trade.analysis;

import com.jmj.trade.marketdata.StockAnalysisInput;

import java.util.List;
import java.util.UUID;

public final class StockAnalysisContract {

    public static final String SCHEMA_VERSION = "1";

    private StockAnalysisContract() {
    }

    public record Request(UUID requestId, StockAnalysisInput input) {
    }

    public record Response(
            UUID requestId,
            String schemaVersion,
            UUID inputSnapshotId,
            String symbol,
            Status status,
            List<String> missingData,
            List<StockAnalysisInput.Observation> observations
    ) {
    }

    public enum Status {
        COMPLETED,
        DEGRADED
    }
}
