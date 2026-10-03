package com.jmj.trade.marketdata;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AlphaVantageEarningsEstimatesProvider implements StockDataProvider {

    private static final StockDataProviderId ID = StockDataProviderId.ALPHA_VANTAGE;
    private static final String HORIZON_FISCAL_YEAR = "fiscal year";
    private static final String CURRENCY_UNVERIFIED = "CURRENCY_UNVERIFIED";
    private static final Set<String> FIELDS = Set.of(
            "consensus.horizon",
            "consensus.epsConsensus",
            "consensus.revenueConsensus",
            "consensus.currency");

    private final ProviderHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    AlphaVantageEarningsEstimatesProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper
    ) {
        this(configuration, objectMapper, Clock.systemUTC());
    }

    AlphaVantageEarningsEstimatesProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.transport = new ProviderHttpTransport(ID, configuration);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public StockDataProviderId id() {
        return ID;
    }

    @Override
    public DataProviderRole role() {
        return ProviderCatalog.roleOf(ID);
    }

    @Override
    public Set<String> fields() {
        return FIELDS;
    }

    @Override
    public List<ProviderValue> fetch(ProviderRequest request) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(transport.get(request));
        } catch (JacksonException exception) {
            throw unavailable("INVALID_RESPONSE");
        }
        var observedAt = clock.instant();
        if (root == null || !root.isObject()) throw unavailable("INVALID_RESPONSE");
        if (hasProviderMessage(root, "Information", "Note", "Error Message")) {
            throw unavailable("API_ERROR");
        }
        var symbol = root.get("symbol");
        if (symbol == null || !symbol.isTextual() || !request.symbol().equalsIgnoreCase(symbol.asText())) {
            throw unavailable("SYMBOL_MISMATCH");
        }
        var estimates = root.path("estimates");
        if (!estimates.isArray()) throw unavailable("INVALID_RESPONSE");

        var captureDate = observedAt.atZone(ZoneOffset.UTC).toLocalDate();
        var selected = new ArrayList<EstimateRow>();
        for (var row : estimates) {
            if (!row.isObject()) throw unavailable("INVALID_RESPONSE");
            var horizon = row.get("horizon");
            if (horizon == null || !horizon.isTextual()) throw unavailable("INVALID_RESPONSE");
            if (!HORIZON_FISCAL_YEAR.equals(horizon.asText())) continue;
            var fiscalDate = row.get("date");
            if (fiscalDate == null || !fiscalDate.isTextual()) throw unavailable("INVALID_RESPONSE");
            final LocalDate date;
            try {
                date = LocalDate.parse(fiscalDate.asText());
            } catch (RuntimeException exception) {
                throw unavailable("INVALID_RESPONSE");
            }
            if (date.isAfter(captureDate)) selected.add(new EstimateRow(date, row));
        }
        var nearest = selected.stream().min(java.util.Comparator.comparing(EstimateRow::date)).orElse(null);
        if (nearest == null) return missingValues(observedAt, "NO_FUTURE_FISCAL_YEAR");
        return values(nearest, observedAt);
    }

    private List<ProviderValue> values(EstimateRow estimate, Instant observedAt) {
        var result = new ArrayList<ProviderValue>();
        result.add(value("consensus.horizon", objectMapper.valueToTree(estimate.date().toString()), null,
                HORIZON_FISCAL_YEAR, observedAt));
        result.add(decimal("consensus.epsConsensus", estimate.row(), "eps_estimate_average",
                CURRENCY_UNVERIFIED, HORIZON_FISCAL_YEAR, observedAt));
        result.add(decimal("consensus.revenueConsensus", estimate.row(), "revenue_estimate_average",
                CURRENCY_UNVERIFIED, HORIZON_FISCAL_YEAR, observedAt));
        result.add(missing("consensus.currency", observedAt, "CURRENCY_UNAVAILABLE"));
        return List.copyOf(result);
    }

    private ProviderValue decimal(String outputField, JsonNode row, String inputField,
                                  String unit, String period, Instant observedAt) {
        var node = row.get(inputField);
        if (node == null || node.isNull()) return missing(outputField, observedAt, "DATA_NOT_PRESENT");
        if (!node.isTextual()) throw unavailable("INVALID_RESPONSE");
        try {
            var value = new BigDecimal(node.asText());
            return value(outputField, objectMapper.valueToTree(value), unit, period, observedAt);
        } catch (NumberFormatException exception) {
            return missing(outputField, observedAt, "INVALID_NUMERIC");
        }
    }

    private List<ProviderValue> missingValues(Instant observedAt, String reason) {
        return FIELDS.stream().sorted().map(field -> missing(field, observedAt, reason)).toList();
    }

    private ProviderValue value(String field, JsonNode value, String unit, String period, Instant observedAt) {
        return new ProviderValue(field, value, unit, period, null, observedAt, List.of(),
                StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }

    private ProviderValue missing(String field, Instant observedAt, String reason) {
        return new ProviderValue(field, null, null, null, null, observedAt, List.of(reason),
                StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }

    private static boolean hasProviderMessage(JsonNode root, String... fields) {
        for (var field : fields) {
            var message = root.get(field);
            if (message != null && !message.isNull() && !message.asText().isBlank()) return true;
        }
        return false;
    }

    private static ProviderUnavailableException unavailable(String reason) {
        return new ProviderUnavailableException(ID, reason);
    }

    private record EstimateRow(LocalDate date, JsonNode row) {
    }
}
