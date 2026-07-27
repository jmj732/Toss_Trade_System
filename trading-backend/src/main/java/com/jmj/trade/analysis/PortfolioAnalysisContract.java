package com.jmj.trade.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PortfolioAnalysisContract {

    private PortfolioAnalysisContract() {
    }

    public record Request(
            UUID requestId,
            String schemaVersion,
            Instant asOf,
            Quality quality,
            List<PositionInput> positions
    ) {
    }

    public record Response(
            UUID requestId,
            String schemaVersion,
            Instant asOf,
            Status status,
            Quality quality,
            List<PositionAnalysis> positions,
            List<CurrencyTotal> currencyTotals
    ) {
    }

    public record Quality(
            boolean stale,
            boolean partial,
            List<String> unknownFields
    ) {
    }

    public record PositionInput(
            String symbol,
            Currency currency,
            BigDecimal marketValue,
            BigDecimal profitLoss
    ) {
    }

    public record PositionAnalysis(
            String symbol,
            Currency currency,
            BigDecimal marketValue,
            BigDecimal profitLoss,
            BigDecimal weight
    ) {
    }

    public record CurrencyTotal(
            Currency currency,
            BigDecimal marketValue,
            BigDecimal profitLoss,
            BigDecimal concentration
    ) {
    }

    public enum Currency {
        KRW,
        USD
    }

    public enum Status {
        COMPLETED,
        DEGRADED
    }
}
