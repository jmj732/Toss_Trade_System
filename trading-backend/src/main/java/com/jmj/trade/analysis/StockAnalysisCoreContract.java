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
            List<Analyzer> analyzers,
            Decision decision,
            PositionPlan positionPlan
    ) {
    }

    /**
     * 결정론적 판단 규칙(analysis-service {@code decision-rule-v1})의 결과.
     * 판단 근거가 없으면 null 이다 — {@code HOLD} 같은 기본값으로 대체하지 않는다.
     */
    public record Decision(
            Action action,
            BigDecimal confidence,
            String ruleVersion,
            List<Basis> basis,
            List<String> missingData
    ) {
    }

    public record Basis(
            String metric,
            BigDecimal value,
            Contribution contribution
    ) {
    }

    /**
     * 결정론적 포지션 계획(analysis-service {@code position-plan-v1}).
     * {@code quote.price} 와 {@code technical.volatility20} 이 둘 다 있을 때만 존재한다.
     */
    public record PositionPlan(
            BigDecimal entry,
            BigDecimal add,
            BigDecimal stop,
            BigDecimal target1,
            BigDecimal target2,
            BigDecimal riskReward,
            BigDecimal maxLossPerShare,
            String invalidation,
            String ruleVersion,
            String currency,
            BigDecimal basisPrice,
            List<String> missingData
    ) {
    }

    public enum Action {
        BUY,
        ADD,
        HOLD,
        REDUCE,
        SELL,
        WAIT
    }

    public enum Contribution {
        POSITIVE,
        NEGATIVE,
        NEUTRAL
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
