package com.jmj.trade.prediction;

import com.jmj.trade.analysis.StockAnalysisCoreContract;
import com.jmj.trade.analysis.StockAnalysisWorkflowService;
import com.jmj.trade.analysis.StockForecastCoreContract;
import com.jmj.trade.observability.CorrelationIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class StockForecastService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final StockAnalysisWorkflowService analyses;
    private final ObjectProvider<AnalysisPredictionService> predictionProvider;
    private final PredictionModelRegistryService registry;
    private final RestClient restClient;
    private final Clock clock;

    public StockForecastService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            StockAnalysisWorkflowService analyses,
            ObjectProvider<AnalysisPredictionService> predictionProvider,
            PredictionModelRegistryService registry,
            @Value("${analysis.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${analysis.service.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${analysis.service.read-timeout:PT5S}") Duration readTimeout
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.analyses = Objects.requireNonNull(analyses, "analyses");
        this.predictionProvider = Objects.requireNonNull(predictionProvider, "predictionProvider");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Clock.systemUTC();
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout, "connectTimeout"))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(positive(readTimeout, "readTimeout"));
        this.restClient = RestClient.builder()
                .baseUrl(Objects.requireNonNull(baseUrl, "baseUrl"))
                .requestFactory(requestFactory)
                .build();
    }

    public StockForecastView execute(UUID userId, String symbol, GenerateCommand command) {
        requireId(userId);
        var normalizedSymbol = normalizeSymbol(symbol);
        var normalized = normalize(command);
        var predictions = predictionProvider.getIfAvailable();
        if (predictions != null) {
            if (normalized.connectionId() == null) {
                throw new StockForecastException(StockForecastException.Code.INVALID_INPUT);
            }
            predictions.requireOwnedConnection(userId, normalized.connectionId());
        }
        if (!registry.isActive(userId, normalized.modelVersion(), normalized.contractVersion())) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.MODEL_VERSION_NOT_ACTIVE);
        }

        var analysis = analyses.latest(userId, normalizedSymbol);
        var existing = findBySnapshot(
                userId,
                analysis.inputSnapshotId(),
                normalized.modelVersion(),
                normalized.contractVersion());
        if (existing.isPresent()) {
            return existing.get();
        }

        var forecastId = UUID.randomUUID();
        var evaluatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var request = new StockForecastCoreContract.Request(
                forecastId,
                StockForecastCoreContract.SCHEMA_VERSION,
                analysis.result(),
                evaluatedAt,
                normalized.modelVersion(),
                normalized.contractVersion());
        var response = call(request);
        validate(request, response);
        var responseJson = encode(response);
        try {
            return transaction.execute(status -> persist(
                    forecastId, userId, normalized, analysis, response, responseJson, evaluatedAt));
        } catch (DuplicateKeyException exception) {
            return findBySnapshot(
                    userId,
                    analysis.inputSnapshotId(),
                    normalized.modelVersion(),
                    normalized.contractVersion()).orElseThrow(() -> exception);
        }
    }

    public StockForecastView latest(UUID userId, String symbol) {
        return latest(userId, symbol, null);
    }

    public StockForecastView latest(UUID userId, String symbol, UUID runId) {
        requireId(userId);
        var normalizedSymbol = normalizeSymbol(symbol);
        if (jdbc.queryForList("SELECT 1 FROM users WHERE id = ?", Integer.class, userId).isEmpty()) {
            throw new StockForecastException(StockForecastException.Code.NOT_FOUND);
        }
        var query = """
                SELECT id, stock_analysis_run_id, input_snapshot_id, symbol, prediction_id,
                       created_at, response::text
                  FROM stock_forecasts
                 WHERE user_id = ? AND symbol = ?
                """ + (runId == null ? "" : " AND stock_analysis_run_id = ? ") + """
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """;
        var arguments = runId == null
                ? new Object[]{userId, normalizedSymbol}
                : new Object[]{userId, normalizedSymbol, runId};
        return jdbc.query(query, (result, row) -> new StockForecastView(
                result.getObject("id", UUID.class),
                result.getObject("stock_analysis_run_id", UUID.class),
                result.getObject("input_snapshot_id", UUID.class),
                result.getString("symbol"),
                result.getObject("prediction_id", UUID.class),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                decode(result.getString("response"))), arguments)
                .stream()
                .findFirst()
                .orElseThrow(() -> new StockForecastException(StockForecastException.Code.NOT_FOUND));
    }

    private StockForecastView persist(
            UUID forecastId,
            UUID userId,
            GenerateCommand command,
            StockAnalysisWorkflowService.StockAnalysisView analysis,
            StockForecastCoreContract.Response response,
            String responseJson,
            Instant evaluatedAt
    ) {
        var predictionId = createLedgerPrediction(
                forecastId, userId, command, analysis.result(), response, evaluatedAt);
        var createdAt = now();
        jdbc.update("""
                INSERT INTO stock_forecasts (
                    id, user_id, stock_analysis_run_id, input_snapshot_id, symbol,
                    schema_version, status, model_version, contract_version, as_of,
                    evaluated_at, confidence, missing_data, response, prediction_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                """, forecastId, userId, analysis.runId(), analysis.inputSnapshotId(), analysis.symbol(),
                response.schemaVersion(), response.status().name(), response.modelVersion(),
                response.contractVersion(), timestamp(response.asOf()), timestamp(evaluatedAt),
                response.confidence(), encode(response.missingData()), responseJson,
                predictionId, createdAt);
        return new StockForecastView(
                forecastId, analysis.runId(), analysis.inputSnapshotId(), analysis.symbol(),
                predictionId, createdAt.toInstant(), response);
    }

    private UUID createLedgerPrediction(
            UUID forecastId,
            UUID userId,
            GenerateCommand command,
            StockAnalysisCoreContract.Response analysis,
            StockForecastCoreContract.Response response,
            Instant evaluatedAt
    ) {
        var predictions = predictionProvider.getIfAvailable();
        if (predictions == null) {
            return null;
        }
        var probability = metricDecimal(response, "forecast.d1_up_probability");
        var baseline = StockForecastCoreContract.quoteBaseline(analysis);
        if (probability == null || baseline == null) {
            return null;
        }
        var direction = probability.compareTo(new BigDecimal("0.5")) >= 0
                ? PredictedDirection.UP
                : PredictedDirection.DOWN;
        var prediction = predictions.createFromForecast(
                userId,
                command.connectionId(),
                "stock-forecast-" + forecastId,
                analysis.symbol(), baseline.currency(), direction,
                command.modelVersion(), command.contractVersion(), baseline.price(),
                evaluatedAt);
        return prediction.id();
    }

    private java.util.Optional<StockForecastView> findBySnapshot(
            UUID userId, UUID snapshotId, String modelVersion, String contractVersion) {
        return jdbc.query("""
                SELECT id, stock_analysis_run_id, input_snapshot_id, symbol, prediction_id,
                       created_at, response::text
                  FROM stock_forecasts
                 WHERE user_id = ? AND input_snapshot_id = ?
                   AND model_version = ? AND contract_version = ?
                """, (result, row) -> new StockForecastView(
                result.getObject("id", UUID.class),
                result.getObject("stock_analysis_run_id", UUID.class),
                result.getObject("input_snapshot_id", UUID.class),
                result.getString("symbol"),
                result.getObject("prediction_id", UUID.class),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                decode(result.getString("response"))), userId, snapshotId, modelVersion, contractVersion)
                .stream().findFirst();
    }

    private StockForecastCoreContract.Response call(StockForecastCoreContract.Request request) {
        try {
            var body = restClient.post()
                    .uri("/internal/v4/stock-forecasts")
                    .headers(headers -> {
                        var correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (correlationId != null) {
                            headers.set(CorrelationIdFilter.HEADER, correlationId);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw new StockForecastException(StockForecastException.Code.CONTRACT_ERROR);
            }
            return objectMapper.readValue(body, StockForecastCoreContract.Response.class);
        } catch (StockForecastException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new StockForecastException(
                    exception.getStatusCode().is4xxClientError()
                            ? StockForecastException.Code.CONTRACT_ERROR
                            : StockForecastException.Code.UPSTREAM_UNAVAILABLE);
        } catch (ResourceAccessException exception) {
            throw new StockForecastException(
                    isTimeout(exception)
                            ? StockForecastException.Code.TIMEOUT
                            : StockForecastException.Code.UPSTREAM_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new StockForecastException(StockForecastException.Code.UPSTREAM_UNAVAILABLE);
        } catch (JacksonException exception) {
            throw new StockForecastException(StockForecastException.Code.CONTRACT_ERROR);
        }
    }

    private void validate(StockForecastCoreContract.Request request,
                          StockForecastCoreContract.Response response) {
        if (response == null
                || !request.requestId().equals(response.requestId())
                || !StockForecastCoreContract.SCHEMA_VERSION.equals(response.schemaVersion())
                || !request.analysis().inputSnapshotId().equals(response.inputSnapshotId())
                || !request.analysis().symbol().equals(response.symbol())
                || !request.analysis().asOf().equals(response.asOf())
                || !request.evaluatedAt().equals(response.evaluatedAt())
                || !request.modelVersion().equals(response.modelVersion())
                || !request.contractVersion().equals(response.contractVersion())
                || response.confidence() == null
                || response.confidence().signum() < 0
                || response.confidence().compareTo(BigDecimal.ONE) > 0
                || response.missingData() == null
                || response.forecasts() == null
                || !StockForecastCoreContract.FORECAST_ORDER.equals(
                response.forecasts().stream().map(StockForecastCoreContract.Metric::name).toList())) {
            throw new StockForecastException(StockForecastException.Code.CONTRACT_ERROR);
        }
        for (var metric : response.forecasts()) {
            if (invalidMetric(metric, response.evaluatedAt())) {
                throw new StockForecastException(StockForecastException.Code.CONTRACT_ERROR);
            }
            if ("forecast.d1_up_probability".equals(metric.name())) {
                var value = metricDecimal(metric);
                if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                    throw new StockForecastException(StockForecastException.Code.CONTRACT_ERROR);
                }
            }
        }
        var expectedMissing = unique(response.forecasts().stream()
                .flatMap(metric -> metric.missingData().stream()));
        var expectedStatus = expectedMissing.isEmpty()
                ? StockAnalysisCoreContract.Status.COMPLETED
                : StockAnalysisCoreContract.Status.DEGRADED;
        if (!expectedMissing.equals(response.missingData()) || response.status() != expectedStatus) {
            throw new StockForecastException(StockForecastException.Code.CONTRACT_ERROR);
        }
    }

    private boolean invalidMetric(StockForecastCoreContract.Metric metric, Instant evaluatedAt) {
        var missing = metric != null && metric.missingData() != null && !metric.missingData().isEmpty();
        var nullValue = metric == null || metric.value() == null || metric.value().isNull();
        var value = metric == null ? null : metricDecimal(metric);
        var sourceAsOfs = metric == null || metric.provenance() == null
                ? List.<Instant>of()
                : metric.provenance().stream().map(StockAnalysisCoreContract.Provenance::asOf)
                .filter(Objects::nonNull).distinct().toList();
        var temporalMismatch = !missing && (metric.asOf() == null || sourceAsOfs.size() != 1
                || !metric.asOf().equals(sourceAsOfs.getFirst())
                || sourceAsOfs.getFirst().isAfter(evaluatedAt)
                || Duration.between(sourceAsOfs.getFirst(), evaluatedAt).compareTo(Duration.ofDays(1)) > 0);
        return metric == null
                || metric.name() == null
                || metric.unit() == null || metric.unit().isBlank()
                || metric.provenance() == null
                || metric.missingData() == null
                || metric.provenance().stream().anyMatch(item -> item == null
                || item.provider() == null
                || item.field() == null || item.field().isBlank()
                || item.collectedAt() == null
                || (!missing && item.asOf() == null))
                || (missing ? !nullValue || metric.asOf() != null
                : nullValue || value == null || metric.asOf() == null || temporalMismatch);
    }

    private BigDecimal metricDecimal(StockForecastCoreContract.Response response, String name) {
        return response.forecasts().stream()
                .filter(metric -> name.equals(metric.name()))
                .findFirst().map(this::metricDecimal).orElse(null);
    }

    private BigDecimal metricDecimal(StockForecastCoreContract.Metric metric) {
        if (metric.value() == null || metric.value().isNull()) {
            return null;
        }
        try {
            return new BigDecimal(metric.value().asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private StockForecastCoreContract.Response decode(String json) {
        try {
            return objectMapper.readValue(json, StockForecastCoreContract.Response.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored stock forecast is invalid", exception);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new StockForecastException(StockForecastException.Code.CONTRACT_ERROR);
        }
    }

    private static List<String> unique(java.util.stream.Stream<String> values) {
        var result = new ArrayList<String>();
        values.forEach(value -> {
            if (!result.contains(value)) {
                result.add(value);
            }
        });
        return result;
    }

    private static GenerateCommand normalize(GenerateCommand command) {
        if (command == null
                || blank(command.modelVersion()) || blank(command.contractVersion())
                || command.modelVersion().length() > 50 || command.contractVersion().length() > 50) {
            throw new StockForecastException(StockForecastException.Code.INVALID_INPUT);
        }
        return new GenerateCommand(
                command.connectionId(), command.modelVersion().trim(), command.contractVersion().trim());
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new StockForecastException(StockForecastException.Code.INVALID_INPUT);
        }
        return symbol.toUpperCase();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireId(UUID userId) {
        if (userId == null) {
            throw new StockForecastException(StockForecastException.Code.INVALID_INPUT);
        }
    }

    private static boolean isTimeout(Throwable exception) {
        for (var current = exception; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static SqlParameterValue timestamp(Instant value) {
        return new SqlParameterValue(
                Types.TIMESTAMP_WITH_TIMEZONE,
                OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public record GenerateCommand(UUID connectionId, String modelVersion, String contractVersion) {
    }

    public record StockForecastView(
            UUID id,
            UUID stockAnalysisRunId,
            UUID inputSnapshotId,
            String symbol,
            UUID predictionId,
            Instant createdAt,
            StockForecastCoreContract.Response result
    ) {
    }
}
