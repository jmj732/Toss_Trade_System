package com.jmj.trade.analysis;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StockForecastCoreContract {

    public static final String SCHEMA_VERSION = "1";
    public static final List<String> FORECAST_ORDER = List.of(
            "forecast.d1_up_probability",
            "forecast.d5_expected_return",
            "forecast.d20_expected_return",
            "forecast.expected_max_loss");

    private StockForecastCoreContract() {
    }

    public static QuoteBaseline quoteBaseline(StockAnalysisCoreContract.Response response) {
        var matches = response.observations().stream()
                .filter(item -> "quote.price".equals(item.field())).toList();
        if (matches.size() != 1 || !matches.getFirst().missingData().isEmpty()
                || matches.getFirst().value() == null || matches.getFirst().unit() == null) {
            return null;
        }
        try {
            var price = new BigDecimal(matches.getFirst().value().asText());
            var currency = matches.getFirst().unit().toUpperCase();
            return price.signum() > 0 && price.scale() <= 10
                    && (currency.equals("KRW") || currency.equals("USD"))
                    ? new QuoteBaseline(price, currency)
                    : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record Request(
            UUID requestId,
            String schemaVersion,
            StockAnalysisCoreContract.Response analysis,
            Instant evaluatedAt,
            String modelVersion,
            String contractVersion
    ) {
    }

    public record Response(
            UUID requestId,
            String schemaVersion,
            UUID inputSnapshotId,
            String symbol,
            Instant asOf,
            Instant evaluatedAt,
            StockAnalysisCoreContract.Status status,
            List<String> missingData,
            BigDecimal confidence,
            String modelVersion,
            String contractVersion,
            List<Metric> forecasts
    ) {
    }

    public record Metric(
            String name,
            JsonNode value,
            String unit,
            Instant asOf,
            List<StockAnalysisCoreContract.Provenance> provenance,
            List<String> missingData
    ) {
    }

    public record QuoteBaseline(BigDecimal price, String currency) {
    }
}
