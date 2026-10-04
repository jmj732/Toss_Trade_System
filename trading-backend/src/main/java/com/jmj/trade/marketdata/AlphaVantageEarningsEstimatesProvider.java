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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class AlphaVantageEarningsEstimatesProvider implements StockDataProvider {

    private static final StockDataProviderId ID = StockDataProviderId.ALPHA_VANTAGE;
    private static final String FISCAL_YEAR = "fiscal year";
    private static final String FISCAL_QUARTER = "fiscal quarter";
    private static final String CURRENCY_UNVERIFIED = "CURRENCY_UNVERIFIED";
    private static final String OBSERVATIONS_FIELD = "consensus.observations";
    private static final String DILUTED_SHARES_FIELD = "fundamental.dilutedShares";
    private static final Set<String> CONSENSUS_FIELDS = Set.of(
            "consensus.horizon",
            "consensus.epsConsensus",
            "consensus.revenueConsensus",
            "consensus.currency",
            OBSERVATIONS_FIELD);
    private static final Set<String> FIELDS = Set.of(
            "consensus.horizon",
            "consensus.epsConsensus",
            "consensus.revenueConsensus",
            "consensus.currency",
            OBSERVATIONS_FIELD,
            DILUTED_SHARES_FIELD);

    private static final int DAILY_REQUEST_LIMIT = 25;

    private final StockAnalysisProviderProperties.ProviderConfiguration configuration;
    private final ProviderHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AlphaVantageDailyRequestCache dailyCache;
    private final List<String> apiKeys;

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
        this(configuration, objectMapper, clock,
                new AlphaVantageDailyRequestCache(objectMapper, clock, DAILY_REQUEST_LIMIT));
    }

    AlphaVantageEarningsEstimatesProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper,
            Clock clock,
            AlphaVantageDailyRequestCache dailyCache
    ) {
        this(configuration, objectMapper, clock, dailyCache, "");
    }

    AlphaVantageEarningsEstimatesProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper,
            Clock clock,
            AlphaVantageDailyRequestCache dailyCache,
            String additionalApiKeys
    ) {
        this.configuration = configuration;
        this.transport = new ProviderHttpTransport(ID, withoutRetries(configuration));
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.dailyCache = dailyCache;
        this.apiKeys = configuredApiKeys(configuration.apiKey(), additionalApiKeys);
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

    /** Fetches consensus by default; diluted shares are only fetched through the selected-field fallback path. */
    @Override
    public List<ProviderValue> fetch(ProviderRequest request) {
        return dailyCache.get(request.symbol(), "EARNINGS_ESTIMATES", apiKeys, configuration.apiKey(),
                apiKey -> readEstimates(request, apiKey));
    }

    @Override
    public List<ProviderValue> fetch(ProviderRequest request, Set<String> selectedFields) {
        if (selectedFields == null) return fetch(request);
        if (selectedFields.isEmpty()) return List.of();
        var values = new ArrayList<ProviderValue>();
        var selectedConsensus = selectedFields.stream().filter(CONSENSUS_FIELDS::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!selectedConsensus.isEmpty()) {
            try {
                values.addAll(fetch(request));
            } catch (RuntimeException exception) {
                values.addAll(missingValues(selectedConsensus, safeFailureCode(exception)));
            }
        }
        if (selectedFields.contains(DILUTED_SHARES_FIELD)) {
            try {
                values.addAll(dailyCache.get(request.symbol(), "SHARES_OUTSTANDING", apiKeys,
                        configuration.apiKey(),
                        apiKey -> readDilutedShares(request, apiKey)));
            } catch (RuntimeException exception) {
                values.add(missing(DILUTED_SHARES_FIELD, clock.instant(), safeFailureCode(exception)));
            }
        }
        return values.stream().filter(value -> selectedFields.contains(value.field())).toList();
    }

    private List<ProviderValue> missingValues(Set<String> selectedFields, String reason) {
        var observedAt = clock.instant();
        return selectedFields.stream().sorted().map(field -> missing(field, observedAt, reason)).toList();
    }

    private static String safeFailureCode(RuntimeException exception) {
        return exception instanceof ProviderUnavailableException unavailable
                && unavailable.provider() == ID ? unavailable.reasonCode() : "API_ERROR";
    }

    private List<ProviderValue> readEstimates(ProviderRequest request, String apiKey) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(transport.get(request, apiKey));
        } catch (JacksonException exception) {
            throw unavailable("INVALID_RESPONSE");
        }
        var observedAt = clock.instant();
        validateRoot(root, request);
        var estimates = root.get("estimates");
        if (estimates == null || !estimates.isArray()) throw unavailable("INVALID_RESPONSE");
        var captureDate = observedAt.atZone(ZoneOffset.UTC).toLocalDate();
        var selectedByKey = new HashMap<EstimateKey, EstimateRow>();
        for (var row : estimates) {
            if (!row.isObject()) throw unavailable("INVALID_RESPONSE");
            var horizon = row.get("horizon");
            var dateNode = row.get("date");
            if (horizon == null || !horizon.isTextual() || dateNode == null || !dateNode.isTextual()) {
                throw unavailable("INVALID_RESPONSE");
            }
            var sourceEstimateType = horizon.asText().trim();
            var estimateType = sourceEstimateType.toLowerCase(java.util.Locale.ROOT);
            if (!FISCAL_YEAR.equals(estimateType) && !FISCAL_QUARTER.equals(estimateType)) continue;
            final LocalDate periodEnd;
            try {
                periodEnd = LocalDate.parse(dateNode.asText());
            } catch (RuntimeException exception) {
                throw unavailable("INVALID_RESPONSE");
            }
            if (periodEnd.isAfter(captureDate)) {
                var key = new EstimateKey(periodEnd, estimateType);
                var candidate = new EstimateRow(periodEnd, estimateType, sourceEstimateType, row);
                var previous = selectedByKey.putIfAbsent(key, candidate);
                if (previous != null && !sameConsensus(previous.row(), row)) {
                    throw unavailable("SOURCE_CONFLICT");
                }
            }
        }
        var selected = new ArrayList<>(selectedByKey.values());
        selected.sort(Comparator.comparingInt((EstimateRow row) -> FISCAL_YEAR.equals(row.estimateType()) ? 0 : 1)
                .thenComparing(EstimateRow::periodEnd));
        var nearestAnnual = selected.stream().filter(row -> FISCAL_YEAR.equals(row.estimateType()))
                .min(Comparator.comparing(EstimateRow::periodEnd)).orElse(null);
        var values = new ArrayList<ProviderValue>();
        if (selected.isEmpty()) {
            values.add(missing(OBSERVATIONS_FIELD, observedAt, "NO_FUTURE_ESTIMATES"));
        } else {
            values.add(observationArray(selected, observedAt));
        }
        if (nearestAnnual == null) {
            values.add(missing("consensus.horizon", observedAt, "NO_FUTURE_FISCAL_YEAR"));
            values.add(missing("consensus.epsConsensus", observedAt, "NO_FUTURE_FISCAL_YEAR"));
            values.add(missing("consensus.revenueConsensus", observedAt, "NO_FUTURE_FISCAL_YEAR"));
        } else {
            values.add(value("consensus.horizon", objectMapper.valueToTree(nearestAnnual.periodEnd().toString()),
                    null, FISCAL_YEAR, observedAt));
            values.add(decimalValue("consensus.epsConsensus", nearestAnnual.row(), "eps_estimate_average",
                    null, FISCAL_YEAR, observedAt));
            values.add(decimalValue("consensus.revenueConsensus", nearestAnnual.row(), "revenue_estimate_average",
                    CURRENCY_UNVERIFIED, FISCAL_YEAR, observedAt));
        }
        values.add(missing("consensus.currency", observedAt, "CURRENCY_UNAVAILABLE"));
        return List.copyOf(values);
    }

    private List<ProviderValue> readDilutedShares(ProviderRequest request, String apiKey) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(transport.get(request, sharesOutstandingEndpoint(), apiKey));
        } catch (JacksonException exception) {
            throw unavailable("INVALID_RESPONSE");
        }
        var observedAt = clock.instant();
        validateRoot(root, request);
        validateShareStatus(root);
        var reports = root.get("data");
        if (reports == null || !reports.isArray()) throw unavailable("INVALID_RESPONSE");
        var captureDate = observedAt.atZone(ZoneOffset.UTC).toLocalDate();
        var candidates = new ArrayList<ShareRow>();
        for (var row : reports) {
            if (!row.isObject()) throw unavailable("INVALID_RESPONSE");
            var fiscalDate = row.get("date");
            if (fiscalDate == null || !fiscalDate.isTextual()) throw unavailable("INVALID_RESPONSE");
            final LocalDate periodEnd;
            try {
                periodEnd = LocalDate.parse(fiscalDate.asText());
            } catch (RuntimeException exception) {
                throw unavailable("INVALID_RESPONSE");
            }
            if (periodEnd.isAfter(captureDate)) continue;
            var diluted = row.get("shares_outstanding_diluted");
            if (diluted != null && !diluted.isNull()) {
                var previous = candidates.stream().filter(candidate -> candidate.periodEnd().equals(periodEnd))
                        .findFirst().orElse(null);
                if (previous != null && !sameNumber(previous.value(), diluted)) throw unavailable("SOURCE_CONFLICT");
                if (previous == null) candidates.add(new ShareRow(periodEnd, diluted));
            }
        }
        var latest = candidates.stream().max(Comparator.comparing(ShareRow::periodEnd)).orElse(null);
        if (latest == null) return List.of(missing(DILUTED_SHARES_FIELD, observedAt,
                reports.isEmpty() ? "NO_QUARTERLY_SHARES" : "DILUTED_SHARES_NOT_PRESENT"));
        var amount = decimal(latest.value());
        if (amount == null || amount.signum() <= 0) {
            return List.of(missing(DILUTED_SHARES_FIELD, observedAt, "INVALID_NUMERIC"));
        }
        return List.of(new ProviderValue(DILUTED_SHARES_FIELD, objectMapper.valueToTree(amount), "shares",
                latest.periodEnd().toString(), "shares_outstanding_diluted",
                latest.periodEnd().atStartOfDay(ZoneOffset.UTC).toInstant(), List.of(),
                StockAnalysisInput.AsOfBasis.SOURCE_AS_OF));
    }

    private ProviderValue observationArray(List<EstimateRow> estimates, Instant observedAt) {
        var observations = objectMapper.createArrayNode();
        for (var estimate : estimates) {
            var row = objectMapper.createObjectNode();
            var horizon = FISCAL_YEAR.equals(estimate.estimateType()) ? "ANNUAL:" : "QUARTERLY:";
            row.put("horizon", horizon + estimate.periodEnd());
            row.put("estimateType", FISCAL_YEAR.equals(estimate.estimateType()) ? "ANNUAL" : "QUARTERLY");
            row.put("sourceEstimateType", estimate.sourceEstimateType());
            row.put("periodEnd", estimate.periodEnd().toString());
            putDecimal(row, "epsConsensus", estimate.row().get("eps_estimate_average"));
            putDecimal(row, "revenueConsensus", estimate.row().get("revenue_estimate_average"));
            putInteger(row, "epsAnalystCount", estimate.row().get("eps_estimate_analyst_count"));
            putInteger(row, "revenueAnalystCount", estimate.row().get("revenue_estimate_analyst_count"));
            row.putNull("currency");
            row.put("observedAt", observedAt.toString());
            observations.add(row);
        }
        return value(OBSERVATIONS_FIELD, observations, null, "annual and quarterly", observedAt);
    }

    private static void putDecimal(tools.jackson.databind.node.ObjectNode target, String field, JsonNode source) {
        var value = decimal(source);
        if (value == null) target.putNull(field);
        else target.put(field, value);
    }

    private static void putInteger(tools.jackson.databind.node.ObjectNode target, String field, JsonNode source) {
        var value = integer(source);
        if (value == null) target.putNull(field);
        else target.put(field, value);
    }

    private ProviderValue decimalValue(String field, JsonNode row, String inputField,
                                       String unit, String period, Instant observedAt) {
        var node = row.get(inputField);
        if (node == null || node.isNull()) return missing(field, observedAt, "DATA_NOT_PRESENT");
        var value = decimal(node);
        if (value == null) return missing(field, observedAt, "INVALID_NUMERIC");
        return value(field, objectMapper.valueToTree(value), unit, period, observedAt);
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || !(node.isTextual() || node.isNumber())) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer integer(JsonNode node) {
        var amount = decimal(node);
        if (amount == null) return null;
        try {
            var count = amount.intValueExact();
            return count < 0 ? null : count;
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private StockAnalysisProviderProperties.EndpointConfiguration sharesOutstandingEndpoint() {
        return new StockAnalysisProviderProperties.EndpointConfiguration(
                configuration.path(), Map.of("function", "SHARES_OUTSTANDING"), Map.of(), Map.of(), Map.of(),
                Map.of(), "INSTANT", "", Map.of());
    }

    private void validateRoot(JsonNode root, ProviderRequest request) {
        if (root == null || !root.isObject()) throw unavailable("INVALID_RESPONSE");
        var message = providerMessage(root, "Information", "Note", "Error Message");
        if (message != null) throw unavailable(classifyProviderMessage(message));
        var symbol = root.get("symbol");
        if (symbol == null || !symbol.isTextual() || !request.symbol().equalsIgnoreCase(symbol.asText())) {
            throw unavailable("SYMBOL_MISMATCH");
        }
    }

    private void validateShareStatus(JsonNode root) {
        var status = root.get("status");
        if (status != null && !status.isNull()
                && (!status.isTextual() || !"success".equalsIgnoreCase(status.asText()))) {
            var message = providerMessage(root, "Information", "Note", "Error Message");
            throw unavailable(message == null ? "API_ERROR" : classifyProviderMessage(message));
        }
    }

    private static boolean sameConsensus(JsonNode left, JsonNode right) {
        return sameNumber(left.get("eps_estimate_average"), right.get("eps_estimate_average"))
                && sameNumber(left.get("revenue_estimate_average"), right.get("revenue_estimate_average"))
                && Objects.equals(integer(left.get("eps_estimate_analyst_count")),
                integer(right.get("eps_estimate_analyst_count")))
                && Objects.equals(integer(left.get("revenue_estimate_analyst_count")),
                integer(right.get("revenue_estimate_analyst_count")));
    }

    private static boolean sameNumber(JsonNode left, JsonNode right) {
        if (left == null || left.isNull()) return right == null || right.isNull();
        if (right == null || right.isNull()) return false;
        var leftNumber = decimal(left);
        var rightNumber = decimal(right);
        if (leftNumber == null || rightNumber == null) return left.equals(right);
        return leftNumber.compareTo(rightNumber) == 0;
    }

    private static StockAnalysisProviderProperties.ProviderConfiguration withoutRetries(
            StockAnalysisProviderProperties.ProviderConfiguration source
    ) {
        return new StockAnalysisProviderProperties.ProviderConfiguration(
                source.enabled(), source.includeSymbolQuery(), source.baseUrl(), source.path(), source.apiKey(),
                source.apiKeyHeader(), source.apiKeyQueryParameter(), source.queryParameters(),
                source.queryIdentifiers(), source.userAgent(), source.units(), source.periods(),
                source.identifiers(), source.asOfPaths(), source.asOfFormat(), source.connectTimeout(),
                source.readTimeout(), 0, source.retryBackoff(), source.requestsPerWindow(),
                source.rateLimitWindow(), source.asOfPath(), source.fields(), source.endpoints());
    }

    private ProviderValue value(String field, JsonNode value, String unit, String period, Instant observedAt) {
        if (value == null) return missing(field, observedAt, unit);
        return new ProviderValue(field, value, unit, period, null, observedAt, List.of(),
                StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }

    private ProviderValue missing(String field, Instant observedAt, String reason) {
        return new ProviderValue(field, null, null, null, null, observedAt, List.of(reason),
                StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }

    private static String providerMessage(JsonNode root, String... fields) {
        for (var field : fields) {
            var message = root.get(field);
            if (message != null && !message.isNull() && !message.asText().isBlank()) return message.asText();
        }
        return null;
    }

    static List<String> configuredApiKeys(String primaryApiKey, String additionalApiKeys) {
        var keys = new LinkedHashSet<String>();
        addKey(keys, primaryApiKey);
        if (additionalApiKeys != null) {
            for (var key : additionalApiKeys.split(",", -1)) addKey(keys, key);
        }
        return List.copyOf(keys);
    }

    private static void addKey(LinkedHashSet<String> keys, String value) {
        if (value != null && !value.isBlank()) keys.add(value.trim());
    }

    static String classifyProviderMessage(String message) {
        if (message == null || message.isBlank()) return "API_ERROR";
        var normalized = message.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalized.contains("invalid api key") || normalized.contains("api key is invalid")
                || normalized.contains("apikey is invalid") || normalized.contains("invalid apikey")) {
            return "INVALID_API_KEY";
        }
        if (normalized.contains("premium endpoint")) {
            return "PREMIUM_ENDPOINT";
        }
        var dailyWindow = normalized.contains("per day") || normalized.contains("daily");
        var quotaLimit = normalized.contains("quota") || normalized.contains("rate limit")
                || normalized.contains("request limit");
        var limitReached = normalized.contains("exceed") || normalized.contains("reached")
                || normalized.contains("maximum") || normalized.contains("subscribe");
        var standardDailyLimit = normalized.contains("25 requests per day");
        if (standardDailyLimit || dailyWindow && quotaLimit && limitReached) {
            return "DAILY_QUOTA_EXHAUSTED";
        }
        if (normalized.contains("premium subscription")) return "PREMIUM_ENDPOINT";
        if (normalized.contains("per minute") || normalized.contains("per second")
                || normalized.contains("rate limit")) return "RATE_LIMITED";
        return "API_ERROR";
    }

    private static ProviderUnavailableException unavailable(String reason) {
        return new ProviderUnavailableException(ID, reason);
    }

    private record EstimateRow(LocalDate periodEnd, String estimateType, String sourceEstimateType, JsonNode row) {
    }

    private record EstimateKey(LocalDate periodEnd, String estimateType) {
    }

    private record ShareRow(LocalDate periodEnd, JsonNode value) {
    }
}
