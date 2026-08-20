package com.jmj.trade.analysis;

import com.jmj.trade.marketdata.StockAnalysisInput;
import com.jmj.trade.marketdata.StockAnalysisInputAssembler;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.observability.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Service;
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
import java.math.RoundingMode;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public final class StockAnalysisWorkflowService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final StockAnalysisInputAssembler assembler;
    private final StockAnalysisSnapshotHasher hasher;
    private final Clock clock;
    private final Duration runningTimeout;

    public StockAnalysisWorkflowService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            StockDataProviderRegistry providers,
            @Value("${analysis.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${analysis.service.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${analysis.service.read-timeout:PT5S}") Duration readTimeout,
            @Value("${stock-analysis.running-timeout:PT5M}") Duration runningTimeout
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.assembler = new StockAnalysisInputAssembler(providers, Clock.systemUTC());
        this.hasher = new StockAnalysisSnapshotHasher(objectMapper);
        this.clock = Clock.systemUTC();
        this.runningTimeout = positive(runningTimeout, "runningTimeout");
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

    public StockAnalysisView execute(UUID userId, String symbol, Map<String, String> identifiers) {
        requireId(userId, "userId");
        var normalizedSymbol = normalizeSymbol(symbol);
        var reserved = transaction.execute(status -> reserve(userId, normalizedSymbol));
        var started = reserved;
        try {
            var input = assembler.assemble(normalizedSymbol, identifiers);
            var payload = hasher.canonicalJson(input);
            var payloadHash = hasher.hash(input);
            started = transaction.execute(status -> attach(reserved, input, payload, payloadHash));
            var response = call(new StockAnalysisCoreContract.Request(started.runId(), input));
            validate(started.runId(), input, response);
            var responseJson = encode(response);
            var completed = started;
            return transaction.execute(status -> complete(completed, response, responseJson));
        } catch (RuntimeException exception) {
            try {
                fail(started, errorCode(exception));
            } catch (RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    private Started reserve(UUID userId, String symbol) {
        if (!userExists(userId)) {
            throw new StockAnalysisException(StockAnalysisException.Code.NOT_FOUND);
        }
        var createdAt = now();
        jdbc.update("""
                UPDATE stock_analysis_runs
                   SET status = 'FAILED', error_code = 'STOCK_ANALYSIS_STALE', completed_at = ?
                 WHERE user_id = ? AND symbol = ? AND status = 'RUNNING' AND started_at < ?
                """, createdAt, userId, symbol, createdAt.minus(runningTimeout));
        var runId = UUID.randomUUID();
        var runInserted = jdbc.update("""
                INSERT INTO stock_analysis_runs (
                    id, user_id, symbol, status, started_at
                ) VALUES (?, ?, ?, 'RUNNING', ?)
                ON CONFLICT DO NOTHING
                """, runId, userId, symbol, createdAt);
        if (runInserted != 1) {
            throw new StockAnalysisException(StockAnalysisException.Code.ALREADY_RUNNING);
        }
        return new Started(runId, userId, null, symbol, createdAt);
    }

    private Started attach(
            Started reserved,
            StockAnalysisInput input,
            String payload,
            String payloadHash
    ) {
        var createdAt = now();
        var snapshotInserted = jdbc.update("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash, collected_at, created_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """, input.snapshotId(), reserved.userId(), reserved.symbol(), input.schemaVersion(), payload, payloadHash,
                timestamp(input.collectedAt()), createdAt);
        if (snapshotInserted != 1) {
            throw new IllegalStateException("stock analysis input snapshot insert failed");
        }
        var runUpdated = jdbc.update("""
                UPDATE stock_analysis_runs
                   SET input_snapshot_id = ?
                 WHERE id = ? AND user_id = ? AND status = 'RUNNING' AND input_snapshot_id IS NULL
                """, input.snapshotId(), reserved.runId(), reserved.userId());
        if (runUpdated != 1) {
            throw new IllegalStateException("stock analysis run reservation lost");
        }
        return new Started(
                reserved.runId(), reserved.userId(), input.snapshotId(), reserved.symbol(), reserved.startedAt());
    }

    public StockAnalysisView latest(UUID userId, String symbol) {
        requireId(userId, "userId");
        var normalizedSymbol = normalizeSymbol(symbol);
        if (!userExists(userId)) {
            throw new StockAnalysisException(StockAnalysisException.Code.NOT_FOUND);
        }
        return jdbc.query("""
                SELECT run.id, run.input_snapshot_id, run.symbol, run.completed_at, result.response::text
                  FROM stock_analysis_runs run
                  JOIN stock_analysis_results result
                    ON result.stock_analysis_run_id = run.id
                   AND result.user_id = run.user_id
                   AND result.input_snapshot_id = run.input_snapshot_id
                 WHERE run.user_id = ?
                   AND run.symbol = ?
                   AND run.status = 'SUCCEEDED'
                 ORDER BY run.completed_at DESC, run.id DESC
                 LIMIT 1
                """, (resultSet, rowNum) -> new StockAnalysisView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("input_snapshot_id", UUID.class),
                resultSet.getString("symbol"),
                resultSet.getObject("completed_at", OffsetDateTime.class).toInstant(),
                decode(resultSet.getString("response"))
        ), userId, normalizedSymbol).stream().findFirst().orElseThrow(() ->
                new StockAnalysisException(StockAnalysisException.Code.RESULT_NOT_FOUND));
    }

    public List<StockAnalysisHistoryView> history(UUID userId, String symbol, int limit) {
        requireId(userId, "userId");
        var normalizedSymbol = normalizeSymbol(symbol);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (!userExists(userId)) {
            throw new StockAnalysisException(StockAnalysisException.Code.NOT_FOUND);
        }
        return jdbc.query("""
                SELECT run.id, run.input_snapshot_id, run.symbol, run.status, run.error_code,
                       run.started_at, run.completed_at, result.response::text
                  FROM stock_analysis_runs run
                  LEFT JOIN stock_analysis_results result
                    ON result.stock_analysis_run_id = run.id
                   AND result.user_id = run.user_id
                   AND result.input_snapshot_id = run.input_snapshot_id
                 WHERE run.user_id = ? AND run.symbol = ?
                 ORDER BY COALESCE(run.completed_at, run.started_at) DESC, run.id DESC
                 LIMIT ?
                """, (resultSet, rowNumber) -> historyView(resultSet),
                userId, normalizedSymbol, limit);
    }

    public StockAnalysisHistoryView run(UUID userId, String symbol, UUID runId) {
        requireId(userId, "userId");
        var normalizedSymbol = normalizeSymbol(symbol);
        if (!userExists(userId)) {
            throw new StockAnalysisException(StockAnalysisException.Code.NOT_FOUND);
        }
        return jdbc.query("""
                SELECT run.id, run.input_snapshot_id, run.symbol, run.status, run.error_code,
                       run.started_at, run.completed_at, result.response::text
                  FROM stock_analysis_runs run
                  LEFT JOIN stock_analysis_results result
                    ON result.stock_analysis_run_id = run.id
                   AND result.user_id = run.user_id
                   AND result.input_snapshot_id = run.input_snapshot_id
                 WHERE run.user_id = ? AND run.symbol = ? AND run.id = ?
                """, (resultSet, rowNumber) -> historyView(resultSet),
                userId, normalizedSymbol, runId).stream().findFirst().orElseThrow(() ->
                new StockAnalysisException(StockAnalysisException.Code.RESULT_NOT_FOUND));
    }

    private StockAnalysisHistoryView historyView(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        var response = resultSet.getString("response");
        var startedAt = resultSet.getObject("started_at", OffsetDateTime.class);
        var completedAt = resultSet.getObject("completed_at", OffsetDateTime.class);
        return new StockAnalysisHistoryView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("input_snapshot_id", UUID.class),
                resultSet.getString("symbol"),
                resultSet.getString("status"),
                resultSet.getString("error_code"),
                startedAt == null ? null : startedAt.toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                response == null ? null : decode(response));
    }

    private StockAnalysisCoreContract.Response call(StockAnalysisCoreContract.Request request) {
        try {
            var response = restClient.post()
                    .uri("/internal/v3/stock-analyses")
                    .headers(headers -> {
                        var correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (correlationId != null) {
                            headers.set(CorrelationIdFilter.HEADER, correlationId);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(String.class);
            if (response == null || response.isBlank()) {
                throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
            }
            return objectMapper.readValue(response, StockAnalysisCoreContract.Response.class);
        } catch (StockAnalysisException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new StockAnalysisException(
                    exception.getStatusCode().is4xxClientError()
                            ? StockAnalysisException.Code.CONTRACT_ERROR
                            : StockAnalysisException.Code.UPSTREAM_UNAVAILABLE);
        } catch (ResourceAccessException exception) {
            throw new StockAnalysisException(
                    isTimeout(exception)
                            ? StockAnalysisException.Code.TIMEOUT
                            : StockAnalysisException.Code.UPSTREAM_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new StockAnalysisException(StockAnalysisException.Code.UPSTREAM_UNAVAILABLE);
        } catch (JacksonException exception) {
            throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
        }
    }

    private StockAnalysisView complete(
            Started started,
            StockAnalysisCoreContract.Response response,
            String responseJson
    ) {
        var completedAt = now();
        var updated = jdbc.update("""
                UPDATE stock_analysis_runs
                   SET status = 'SUCCEEDED', completed_at = ?
                 WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, completedAt, started.runId(), started.userId());
        if (updated != 1) {
            throw new IllegalStateException("stock analysis run completion lost");
        }
        jdbc.update("""
                INSERT INTO stock_analysis_results (
                    id, stock_analysis_run_id, user_id, input_snapshot_id,
                    schema_version, result_status, response, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), started.runId(), started.userId(), started.snapshotId(),
                response.schemaVersion(), response.status().name(), responseJson, completedAt);
        return new StockAnalysisView(
                started.runId(),
                started.snapshotId(),
                started.symbol(),
                completedAt.toInstant(),
                response);
    }

    private void fail(Started started, String errorCode) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE stock_analysis_runs
                   SET status = 'FAILED', error_code = ?, completed_at = ?
                 WHERE id = ? AND user_id = ? AND status = 'RUNNING'
                """, errorCode, now(), started.runId(), started.userId()));
    }

    private void validate(
            UUID runId,
            StockAnalysisInput input,
            StockAnalysisCoreContract.Response response
    ) {
        if (response == null
                || !runId.equals(response.requestId())
                || !StockAnalysisCoreContract.SCHEMA_VERSION.equals(response.schemaVersion())
                || !input.snapshotId().equals(response.inputSnapshotId())
                || !input.symbol().equals(response.symbol())
                || !input.collectedAt().equals(response.asOf())
                || response.observations() == null
                || !input.observations().equals(response.observations())
                || response.missingData() == null
                || response.analyzers() == null
                || response.analyzers().size() != StockAnalysisCoreContract.ANALYZER_ORDER.size()) {
            throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
        }
        for (int index = 0; index < response.analyzers().size(); index++) {
            var analyzer = response.analyzers().get(index);
            if (analyzer == null
                    || !StockAnalysisCoreContract.ANALYZER_ORDER.get(index).equals(analyzer.analyzer())
                    || analyzer.confidence() == null
                    || analyzer.confidence().signum() < 0
                    || analyzer.confidence().compareTo(BigDecimal.ONE) > 0
                    || analyzer.missingData() == null
                    || analyzer.metrics() == null
                    || analyzer.metrics().stream().anyMatch(this::invalidMetric)) {
                throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
            }
            var expectedMetrics = StockAnalysisCoreContract.metricOrder(analyzer.analyzer());
            if (!expectedMetrics.equals(analyzer.metrics().stream()
                    .map(StockAnalysisCoreContract.Metric::name).toList())) {
                throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
            }
            var expectedMissing = unique(analyzer.metrics().stream()
                    .flatMap(metric -> metric.missingData().stream()));
            var expectedConfidence = new BigDecimal(
                    analyzer.metrics().stream().filter(metric -> metric.missingData().isEmpty()).count())
                    .divide(new BigDecimal(analyzer.metrics().size()), 10, RoundingMode.HALF_EVEN);
            if (!expectedMissing.equals(analyzer.missingData())
                    || analyzer.confidence().compareTo(expectedConfidence) != 0) {
                throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
            }
        }
        var analyzerDegraded = response.analyzers().stream()
                .anyMatch(analyzer -> !analyzer.missingData().isEmpty());
        var expectedStatus = input.degraded()
                || analyzerDegraded
                ? StockAnalysisCoreContract.Status.DEGRADED
                : StockAnalysisCoreContract.Status.COMPLETED;
        var expectedMissing = new ArrayList<String>();
        input.observations().forEach(observation -> observation.missingData().forEach(reason ->
                addUnique(expectedMissing, observation.provider() + ":" + observation.field() + ":" + reason)));
        response.analyzers().forEach(analyzer -> analyzer.missingData().forEach(reason ->
                addUnique(expectedMissing, analyzer.analyzer() + ":" + reason)));
        if (response.status() != expectedStatus
                || (expectedStatus == StockAnalysisCoreContract.Status.COMPLETED
                ? !response.missingData().isEmpty()
                : !expectedMissing.equals(response.missingData()))) {
            throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
        }
        if (invalidDecision(response.decision()) || invalidPositionPlan(response.positionPlan())) {
            throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
        }
    }

    /**
     * decision 은 nullable 이다(v3 응답에는 없고, 판단 근거가 없으면 서비스가 null 을 낸다).
     * 존재할 때만 구조와 신뢰도 범위를 검사한다.
     */
    private boolean invalidDecision(StockAnalysisCoreContract.Decision decision) {
        if (decision == null) {
            return false;
        }
        return decision.action() == null
                || decision.ruleVersion() == null
                || decision.ruleVersion().isBlank()
                || decision.basis() == null
                || decision.missingData() == null
                || decision.basis().stream().anyMatch(item -> item == null
                || item.metric() == null
                || item.metric().isBlank()
                || item.contribution() == null)
                || (decision.confidence() != null
                && (decision.confidence().signum() < 0
                || decision.confidence().compareTo(BigDecimal.ONE) > 0));
    }

    /** positionPlan 도 nullable 이다. 존재하면 손절가는 반드시 진입가보다 낮아야 한다. */
    private boolean invalidPositionPlan(StockAnalysisCoreContract.PositionPlan plan) {
        if (plan == null) {
            return false;
        }
        return plan.ruleVersion() == null
                || plan.ruleVersion().isBlank()
                || plan.missingData() == null
                || (plan.entry() != null && plan.stop() != null
                && plan.stop().compareTo(plan.entry()) >= 0);
    }

    private boolean invalidMetric(StockAnalysisCoreContract.Metric metric) {
        var missingData = metric == null ? null : metric.missingData();
        var missing = missingData != null && !missingData.isEmpty();
        var nullValue = metric == null || metric.value() == null || metric.value().isNull();
        return metric == null
                || metric.name() == null
                || metric.name().isBlank()
                || metric.unit() == null
                || metric.provenance() == null
                || missingData == null
                || metric.provenance().stream().anyMatch(provenance -> provenance == null
                || provenance.provider() == null
                || provenance.field() == null
                || provenance.collectedAt() == null
                || (!missing && provenance.asOf() == null))
                || (missing ? !nullValue || metric.asOf() != null : nullValue || metric.asOf() == null);
    }

    private static List<String> unique(java.util.stream.Stream<String> values) {
        var result = new ArrayList<String>();
        values.forEach(value -> addUnique(result, value));
        return result;
    }

    private static void addUnique(List<String> values, String value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private StockAnalysisCoreContract.Response decode(String json) {
        try {
            return objectMapper.readValue(json, StockAnalysisCoreContract.Response.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored stock analysis result is invalid", exception);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
        }
    }

    private boolean userExists(UUID userId) {
        return !jdbc.queryForList("SELECT 1 FROM users WHERE id = ?", Integer.class, userId).isEmpty();
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new StockAnalysisException(StockAnalysisException.Code.INVALID_SYMBOL);
        }
        return symbol.toUpperCase();
    }

    private static String errorCode(RuntimeException exception) {
        return exception instanceof StockAnalysisException stock
                ? "STOCK_ANALYSIS_" + stock.code().name()
                : "INTERNAL_ERROR";
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
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireId(UUID value, String name) {
        if (value == null) {
            throw new StockAnalysisException(StockAnalysisException.Code.INVALID_USER);
        }
    }

    private record Started(
            UUID runId,
            UUID userId,
            UUID snapshotId,
            String symbol,
            OffsetDateTime startedAt
    ) {
    }

    public record StockAnalysisView(
            UUID runId,
            UUID inputSnapshotId,
            String symbol,
            Instant completedAt,
            StockAnalysisCoreContract.Response result
    ) {
    }

    public record StockAnalysisHistoryView(
            UUID runId,
            UUID inputSnapshotId,
            String symbol,
            String status,
            String errorCode,
            Instant startedAt,
            Instant completedAt,
            StockAnalysisCoreContract.Response result
    ) {
    }
}
