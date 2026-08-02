package com.jmj.trade.analysis;

import com.jmj.trade.marketdata.StockAnalysisInput;
import com.jmj.trade.marketdata.StockAnalysisInputAssembler;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.observability.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
            var response = call(new StockAnalysisContract.Request(started.runId(), input));
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
                input.collectedAt(), createdAt);
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

    private StockAnalysisContract.Response call(StockAnalysisContract.Request request) {
        try {
            var response = restClient.post()
                    .uri("/internal/v2/stock-analysis-inputs")
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
            return objectMapper.readValue(response, StockAnalysisContract.Response.class);
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
            StockAnalysisContract.Response response,
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
            StockAnalysisContract.Response response
    ) {
        if (response == null
                || !runId.equals(response.requestId())
                || !StockAnalysisContract.SCHEMA_VERSION.equals(response.schemaVersion())
                || !input.snapshotId().equals(response.inputSnapshotId())
                || !input.symbol().equals(response.symbol())
                || response.observations() == null
                || !input.observations().equals(response.observations())) {
            throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
        }
        var expectedMissing = input.observations().stream()
                .flatMap(item -> item.missingData().stream()
                        .map(reason -> item.provider() + ":" + item.field() + ":" + reason))
                .toList();
        var expectedStatus = input.degraded()
                ? StockAnalysisContract.Status.DEGRADED
                : StockAnalysisContract.Status.COMPLETED;
        if (response.status() != expectedStatus || !expectedMissing.equals(response.missingData())) {
            throw new StockAnalysisException(StockAnalysisException.Code.CONTRACT_ERROR);
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
            StockAnalysisContract.Response result
    ) {
    }
}
