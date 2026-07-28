package com.jmj.trade.analysis;

import com.jmj.trade.observability.CorrelationIdFilter;
import org.slf4j.MDC;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public final class PortfolioAnalysisWorkflowService {

    private static final String SCHEMA_VERSION = "1";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Duration staleAfter;

    public PortfolioAnalysisWorkflowService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            @Value("${analysis.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${analysis.service.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${analysis.service.read-timeout:PT5S}") Duration readTimeout,
            @Value("${portfolio.analysis.stale-after:PT15M}") Duration staleAfter
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.staleAfter = positive(staleAfter, "staleAfter");
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

    public AnalysisView execute(UUID userId, UUID connectionId) {
        return execute(userId, connectionId, null).current();
    }

    public EventAnalysis executeForEvent(UUID userId, UUID connectionId, UUID eventId) {
        requireId(eventId, "eventId");
        return execute(userId, connectionId, eventId);
    }

    private EventAnalysis execute(UUID userId, UUID connectionId, UUID eventId) {
        requireId(userId, "userId");
        requireId(connectionId, "connectionId");
        var started = transaction.execute(status -> start(userId, connectionId, eventId));
        try {
            var response = call(started.request());
            validate(started.request(), response);
            var responseJson = encode(response);
            var current = transaction.execute(status -> complete(started, response, responseJson));
            return new EventAnalysis(started.previousAnalysis(), current);
        } catch (RuntimeException exception) {
            try {
                fail(started, errorCode(exception));
            } catch (RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    public AnalysisView latest(UUID userId, UUID connectionId) {
        return latestOptional(userId, connectionId).orElseThrow(() ->
                new PortfolioAnalysisException(PortfolioAnalysisException.Code.RESULT_NOT_FOUND));
    }

    public Optional<AnalysisView> latestOptional(UUID userId, UUID connectionId) {
        requireId(userId, "userId");
        requireId(connectionId, "connectionId");
        if (!ownedConnection(userId, connectionId)) {
            throw new PortfolioAnalysisException(PortfolioAnalysisException.Code.NOT_FOUND);
        }
        return jdbc.query("""
                SELECT run.id, run.input_sync_run_id, run.completed_at, result.response::text
                  FROM analysis_runs run
                  JOIN analysis_results result
                    ON result.analysis_run_id = run.id
                   AND result.user_id = run.user_id
                   AND result.broker_connection_id = run.broker_connection_id
                  JOIN account_sync_runs input
                    ON input.id = run.input_sync_run_id
                   AND input.user_id = run.user_id
                   AND input.broker_connection_id = run.broker_connection_id
                  JOIN broker_connections connection
                    ON connection.id = run.broker_connection_id
                   AND connection.user_id = run.user_id
                   AND connection.credential_revision = input.credential_revision
                 WHERE run.user_id = ?
                   AND run.broker_connection_id = ?
                   AND run.status = 'SUCCEEDED'
                 ORDER BY run.completed_at DESC, run.id DESC
                 LIMIT 1
                """, (resultSet, rowNum) -> new AnalysisView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("input_sync_run_id", UUID.class),
                resultSet.getObject("completed_at", OffsetDateTime.class).toInstant(),
                decode(resultSet.getString("response"))
        ), userId, connectionId).stream().findFirst();
    }

    public Optional<EventAnalysis> completedEventAnalysis(
            UUID userId,
            UUID connectionId,
            UUID eventId
    ) {
        requireId(userId, "userId");
        requireId(connectionId, "connectionId");
        requireId(eventId, "eventId");
        if (!ownedConnection(userId, connectionId)) {
            throw new PortfolioAnalysisException(PortfolioAnalysisException.Code.NOT_FOUND);
        }
        return jdbc.query("""
                SELECT run.id, run.input_sync_run_id, run.completed_at,
                       run.previous_analysis_run_id, result.response::text
                  FROM analysis_runs run
                  JOIN analysis_results result
                    ON result.analysis_run_id = run.id
                   AND result.user_id = run.user_id
                   AND result.broker_connection_id = run.broker_connection_id
                 WHERE run.user_id = ?
                   AND run.broker_connection_id = ?
                   AND run.trigger_event_id = ?
                   AND run.status = 'SUCCEEDED'
                """, (resultSet, rowNum) -> new StoredEventAnalysis(
                new AnalysisView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("input_sync_run_id", UUID.class),
                        resultSet.getObject("completed_at", OffsetDateTime.class).toInstant(),
                        decode(resultSet.getString("response"))),
                resultSet.getObject("previous_analysis_run_id", UUID.class)
        ), userId, connectionId, eventId).stream().findFirst().map(stored ->
                new EventAnalysis(
                        stored.previousRunId() == null
                                ? null
                                : analysisById(userId, connectionId, stored.previousRunId()),
                        stored.current()));
    }

    private Start start(UUID userId, UUID connectionId, UUID eventId) {
        var previous = eventId == null ? null : latestOptional(userId, connectionId).orElse(null);
        var selection = jdbc.query("""
                SELECT latest.id AS latest_id,
                       success.id AS success_id,
                       success.completed_at
                  FROM broker_connections connection
                  LEFT JOIN LATERAL (
                      SELECT run.id
                        FROM account_sync_runs run
                       WHERE run.user_id = connection.user_id
                         AND run.broker_connection_id = connection.id
                         AND run.credential_revision = connection.credential_revision
                       ORDER BY run.started_at DESC, run.id DESC
                       LIMIT 1
                  ) latest ON true
                  LEFT JOIN LATERAL (
                      SELECT run.id, run.completed_at
                        FROM account_sync_runs run
                       WHERE run.user_id = connection.user_id
                         AND run.broker_connection_id = connection.id
                         AND run.credential_revision = connection.credential_revision
                         AND run.status = 'SUCCEEDED'
                       ORDER BY run.completed_at DESC, run.id DESC
                       LIMIT 1
                  ) success ON true
                 WHERE connection.id = ?
                   AND connection.user_id = ?
                   AND connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                """, (resultSet, rowNum) -> new Selection(
                resultSet.getObject("latest_id", UUID.class),
                resultSet.getObject("success_id", UUID.class),
                resultSet.getObject("completed_at", OffsetDateTime.class)
        ), connectionId, userId).stream().findFirst().orElseThrow(() ->
                new PortfolioAnalysisException(PortfolioAnalysisException.Code.NOT_FOUND));
        if (selection.successId() == null) {
            throw new PortfolioAnalysisException(PortfolioAnalysisException.Code.SNAPSHOT_NOT_FOUND);
        }

        jdbc.update("""
                UPDATE analysis_runs
                   SET status = 'FAILED',
                       error_code = 'FAILED_STALE',
                       completed_at = CURRENT_TIMESTAMP
                 WHERE user_id = ?
                   AND broker_connection_id = ?
                   AND status = 'RUNNING'
                   AND started_at < CURRENT_TIMESTAMP
                       - CAST(? AS bigint) * INTERVAL '1 millisecond'
                """, userId, connectionId, staleAfter.toMillis());

        var runId = UUID.randomUUID();
        var inserted = jdbc.update("""
                INSERT INTO analysis_runs (
                    id, user_id, broker_connection_id, input_sync_run_id,
                    trigger_event_id, previous_analysis_run_id, status, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                ON CONFLICT DO NOTHING
                """, runId, userId, connectionId, selection.successId(), eventId,
                previous == null ? null : previous.runId(), now());
        if (inserted != 1) {
            throw new PortfolioAnalysisException(PortfolioAnalysisException.Code.ALREADY_RUNNING);
        }

        var accountStatuses = jdbc.queryForList("""
                SELECT cash_balance_status
                  FROM account_snapshots
                 WHERE sync_run_id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                """, String.class, selection.successId(), userId, connectionId);
        var capacityCount = jdbc.queryForObject("""
                SELECT count(DISTINCT currency)
                  FROM account_capacity_snapshots
                 WHERE sync_run_id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                   AND currency IN ('KRW', 'USD')
                """, Integer.class, selection.successId(), userId, connectionId);
        var quality = new PortfolioAnalysisContract.Quality(
                !selection.successId().equals(selection.latestId()),
                accountStatuses.size() != 1 || capacityCount == null || capacityCount != 2,
                accountStatuses.size() == 1 && "UNKNOWN".equals(accountStatuses.getFirst())
                        ? List.of("account.cashBalance")
                        : List.of());
        var positions = jdbc.query("""
                SELECT symbol, currency, market_value_amount, profit_loss_amount
                  FROM position_snapshots
                 WHERE sync_run_id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                 ORDER BY symbol, id
                """, (resultSet, rowNum) -> new PortfolioAnalysisContract.PositionInput(
                resultSet.getString("symbol"),
                PortfolioAnalysisContract.Currency.valueOf(resultSet.getString("currency")),
                resultSet.getBigDecimal("market_value_amount"),
                resultSet.getBigDecimal("profit_loss_amount")
        ), selection.successId(), userId, connectionId);
        var request = new PortfolioAnalysisContract.Request(
                runId,
                SCHEMA_VERSION,
                selection.completedAt().toInstant(),
                quality,
                List.copyOf(positions));
        return new Start(runId, userId, connectionId, selection.successId(), previous, request);
    }

    private AnalysisView analysisById(UUID userId, UUID connectionId, UUID runId) {
        return jdbc.query("""
                SELECT run.id, run.input_sync_run_id, run.completed_at, result.response::text
                  FROM analysis_runs run
                  JOIN analysis_results result
                    ON result.analysis_run_id = run.id
                   AND result.user_id = run.user_id
                   AND result.broker_connection_id = run.broker_connection_id
                 WHERE run.id = ?
                   AND run.user_id = ?
                   AND run.broker_connection_id = ?
                   AND run.status = 'SUCCEEDED'
                """, (resultSet, rowNum) -> new AnalysisView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("input_sync_run_id", UUID.class),
                resultSet.getObject("completed_at", OffsetDateTime.class).toInstant(),
                decode(resultSet.getString("response"))
        ), runId, userId, connectionId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("stored previous analysis result is missing"));
    }

    private PortfolioAnalysisContract.Response call(PortfolioAnalysisContract.Request request) {
        try {
            var body = restClient.post()
                    .uri("/internal/v1/portfolio-analyses")
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
                throw new PortfolioAnalysisException(PortfolioAnalysisException.Code.CONTRACT_ERROR);
            }
            return objectMapper.readValue(body, PortfolioAnalysisContract.Response.class);
        } catch (PortfolioAnalysisException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new PortfolioAnalysisException(
                    exception.getStatusCode().is4xxClientError()
                            ? PortfolioAnalysisException.Code.CONTRACT_ERROR
                            : PortfolioAnalysisException.Code.UPSTREAM_UNAVAILABLE,
                    exception);
        } catch (ResourceAccessException exception) {
            throw new PortfolioAnalysisException(
                    isTimeout(exception)
                            ? PortfolioAnalysisException.Code.TIMEOUT
                            : PortfolioAnalysisException.Code.UPSTREAM_UNAVAILABLE,
                    exception);
        } catch (RestClientException exception) {
            throw new PortfolioAnalysisException(
                    PortfolioAnalysisException.Code.UPSTREAM_UNAVAILABLE,
                    exception);
        } catch (JacksonException exception) {
            throw new PortfolioAnalysisException(
                    PortfolioAnalysisException.Code.CONTRACT_ERROR,
                    exception);
        }
    }

    private AnalysisView complete(
            Start started,
            PortfolioAnalysisContract.Response response,
            String responseJson
    ) {
        var completedAt = now();
        var updated = jdbc.update("""
                UPDATE analysis_runs
                   SET status = 'SUCCEEDED',
                       completed_at = ?
                 WHERE id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                   AND status = 'RUNNING'
                """, completedAt, started.runId(), started.userId(), started.connectionId());
        if (updated != 1) {
            throw new IllegalStateException("analysis run completion lost");
        }
        jdbc.update("""
                INSERT INTO analysis_results (
                    id, analysis_run_id, user_id, broker_connection_id, input_sync_run_id,
                    schema_version, result_status, response, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), started.runId(), started.userId(), started.connectionId(),
                started.snapshotId(), response.schemaVersion(), response.status().name(),
                responseJson, completedAt);
        return new AnalysisView(
                started.runId(),
                started.snapshotId(),
                completedAt.toInstant(),
                response);
    }

    private void fail(Start started, String errorCode) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE analysis_runs
                   SET status = 'FAILED',
                       error_code = ?,
                       completed_at = ?
                 WHERE id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                   AND status = 'RUNNING'
                """, errorCode, now(), started.runId(), started.userId(), started.connectionId()));
    }

    private boolean ownedConnection(UUID userId, UUID connectionId) {
        return !jdbc.queryForList("""
                SELECT 1
                  FROM broker_connections
                 WHERE id = ?
                   AND user_id = ?
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                """, Integer.class, connectionId, userId).isEmpty();
    }

    private void validate(
            PortfolioAnalysisContract.Request request,
            PortfolioAnalysisContract.Response response
    ) {
        if (response == null
                || !request.requestId().equals(response.requestId())
                || !request.schemaVersion().equals(response.schemaVersion())
                || !request.asOf().equals(response.asOf())
                || !request.quality().equals(response.quality())
                || response.positions() == null
                || response.currencyTotals() == null
                || response.positions().size() != request.positions().size()) {
            contractError();
        }
        var degraded = request.quality().stale()
                || request.quality().partial()
                || !request.quality().unknownFields().isEmpty();
        if (response.status() != (degraded
                ? PortfolioAnalysisContract.Status.DEGRADED
                : PortfolioAnalysisContract.Status.COMPLETED)) {
            contractError();
        }
        for (int index = 0; index < request.positions().size(); index++) {
            var input = request.positions().get(index);
            var output = response.positions().get(index);
            if (output == null
                    || !input.symbol().equals(output.symbol())
                    || input.currency() != output.currency()
                    || !same(input.marketValue(), output.marketValue())
                    || !same(input.profitLoss(), output.profitLoss())
                    || !ratio(output.weight())) {
                contractError();
            }
        }
        var expectedCurrencies = new HashSet<PortfolioAnalysisContract.Currency>();
        request.positions().forEach(position -> expectedCurrencies.add(position.currency()));
        var actualCurrencies = new HashSet<PortfolioAnalysisContract.Currency>();
        for (var total : response.currencyTotals()) {
            if (total == null
                    || total.currency() == null
                    || total.marketValue() == null
                    || total.marketValue().signum() < 0
                    || total.profitLoss() == null
                    || !ratio(total.concentration())
                    || !actualCurrencies.add(total.currency())) {
                contractError();
            }
        }
        if (!expectedCurrencies.equals(actualCurrencies)) {
            contractError();
        }
    }

    private String encode(PortfolioAnalysisContract.Response response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new PortfolioAnalysisException(
                    PortfolioAnalysisException.Code.CONTRACT_ERROR,
                    exception);
        }
    }

    private PortfolioAnalysisContract.Response decode(String json) {
        try {
            return objectMapper.readValue(json, PortfolioAnalysisContract.Response.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored analysis result is invalid", exception);
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

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static boolean ratio(BigDecimal value) {
        return value != null
                && value.compareTo(BigDecimal.ZERO) >= 0
                && value.compareTo(BigDecimal.ONE) <= 0;
    }

    private static void contractError() {
        throw new PortfolioAnalysisException(PortfolioAnalysisException.Code.CONTRACT_ERROR);
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof PortfolioAnalysisException analysisException) {
            return "ANALYSIS_" + analysisException.code().name();
        }
        return "INTERNAL_ERROR";
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireId(UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private record Selection(UUID latestId, UUID successId, OffsetDateTime completedAt) {
    }

    private record Start(
            UUID runId,
            UUID userId,
            UUID connectionId,
            UUID snapshotId,
            AnalysisView previousAnalysis,
            PortfolioAnalysisContract.Request request
    ) {
    }

    private record StoredEventAnalysis(AnalysisView current, UUID previousRunId) {
    }

    public record EventAnalysis(AnalysisView previous, AnalysisView current) {
    }

    public record AnalysisView(
            UUID runId,
            UUID inputSnapshotId,
            Instant completedAt,
            PortfolioAnalysisContract.Response result
    ) {
    }
}
