package com.jmj.trade.prediction;

import com.jmj.trade.analysis.StockForecastCoreContract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StockAnalysisGeminiExplainContract {

    public static final String SCHEMA_VERSION = "1";

    private StockAnalysisGeminiExplainContract() {
    }

    public enum Status {
        COMPLETED,
        DEGRADED
    }

    public record Citation(
            String id,
            String field,
            String provider,
            Instant asOf,
            Instant collectedAt,
            List<String> missingData
    ) {
        public Citation {
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
        }
    }

    public record Claim(String text, List<String> citationIds) {
        public Claim {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }

    public record Claims(
            List<Claim> evidence,
            List<Claim> counterArguments,
            List<Claim> missingData,
            List<Claim> invalidationConditions
    ) {
        public Claims {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            counterArguments = counterArguments == null ? List.of() : List.copyOf(counterArguments);
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
            invalidationConditions = invalidationConditions == null ? List.of() : List.copyOf(invalidationConditions);
        }
    }

    public record Response(
            UUID id,
            UUID stockAnalysisRunId,
            UUID stockForecastId,
            UUID inputSnapshotId,
            String symbol,
            Instant asOf,
            Status status,
            List<String> missingData,
            String modelId,
            String promptVersion,
            List<Citation> citations,
            Claims explanation,
            StockForecastCoreContract.Response forecast,
            Instant createdAt
    ) {
        public Response {
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }
}
