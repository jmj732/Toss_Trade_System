package com.jmj.trade.analysis;

import com.jmj.trade.marketdata.StockAnalysisInput;
import com.jmj.trade.marketdata.StockDataProviderId;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StockAnalysisCoreContract {

    public static final String SCHEMA_VERSION = "1";
    public static final List<String> ANALYZER_ORDER = List.of(
            "fundamental", "valuation", "technical", "marketRegime");

    private StockAnalysisCoreContract() {
    }

    public static List<String> metricOrder(String analyzer) {
        return switch (analyzer) {
            case "fundamental" -> List.of(
                    "fundamental.profit_margin",
                    "fundamental.roe",
                    "fundamental.debt_to_equity",
                    "fundamental.operating_cash_flow_margin");
            case "valuation" -> List.of(
                    "valuation.pe",
                    "valuation.price_to_book",
                    "valuation.price_to_sales",
                    "valuation.fcf_yield");
            case "technical" -> List.of(
                    "technical.price_vs_sma20",
                    "technical.price_vs_sma50",
                    "technical.sma_trend",
                    "technical.rsi14",
                    "technical.volatility20");
            case "marketRegime" -> List.of(
                    "marketRegime.vix",
                    "marketRegime.sp500Return20d",
                    "marketRegime.state");
            default -> List.of();
        };
    }

    public record Request(UUID requestId, StockAnalysisInput input) {
    }

    public record Response(
            UUID requestId,
            String schemaVersion,
            UUID inputSnapshotId,
            String symbol,
            Instant asOf,
            Status status,
            List<String> missingData,
            List<StockAnalysisInput.Observation> observations,
            List<Analyzer> analyzers
    ) {
    }

    public record Analyzer(
            String analyzer,
            BigDecimal confidence,
            List<String> missingData,
            List<Metric> metrics
    ) {
    }

    public record Metric(
            String name,
            JsonNode value,
            String unit,
            Instant asOf,
            List<Provenance> provenance,
            List<String> missingData
    ) {
    }

    public record Provenance(
            StockDataProviderId provider,
            String field,
            Instant asOf,
            Instant collectedAt
    ) {
    }

    public enum Status {
        COMPLETED,
        DEGRADED
    }
}
