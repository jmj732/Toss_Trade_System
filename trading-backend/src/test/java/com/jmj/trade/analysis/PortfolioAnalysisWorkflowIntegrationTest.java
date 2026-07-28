package com.jmj.trade.analysis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.observability.CorrelationIdFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "analysis.service.connect-timeout=PT0.5S",
                "analysis.service.read-timeout=PT0.5S"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PortfolioAnalysisWorkflowIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime SNAPSHOT_TIME =
            OffsetDateTime.of(2026, 7, 28, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final WireMockServer ANALYSIS =
            new WireMockServer(options().dynamicPort().globalTemplating(true));

    static {
        ANALYSIS.start();
    }

    @DynamicPropertySource
    static void analysisProperties(DynamicPropertyRegistry registry) {
        registry.add("analysis.service.base-url", ANALYSIS::baseUrl);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private PortfolioAnalysisWorkflowService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE broker_connections, users CASCADE");
        ANALYSIS.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        ANALYSIS.stop();
    }

    @Test
    void analyzesPreviousSuccessAsExplicitlyStaleAndReturnsLatestStoredResult() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertSuccessfulPortfolio(connectionId, USER_ID);
        insertRun(connectionId, USER_ID, UUID.randomUUID(), "FAILED", "BROKER_TEMPORARY",
                SNAPSHOT_TIME.plusMinutes(1), SNAPSHOT_TIME.plusMinutes(2));
        stubSuccess(0);

        var body = mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(USER_ID.toString()))
                        .header("X-Correlation-Id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputSnapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.result.status").value("DEGRADED"))
                .andExpect(jsonPath("$.result.quality.stale").value(true))
                .andExpect(jsonPath("$.result.quality.partial").value(false))
                .andExpect(jsonPath("$.result.quality.unknownFields[0]").value("account.cashBalance"))
                .andExpect(jsonPath("$.result.positions[0].symbol").value("NVDA"))
                .andExpect(jsonPath("$.result.currencyTotals[0].currency").value("USD"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio-analyses/latest", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputSnapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.result.requestId").value(jsonString(body, "runId")));

        ANALYSIS.verify(1, postRequestedFor(urlEqualTo("/internal/v1/portfolio-analyses"))
                .withHeader("X-Correlation-Id", equalTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
                .withRequestBody(equalToJson("""
                        {
                          "schemaVersion":"1",
                          "asOf":"2026-07-28T00:00:01Z",
                          "quality":{
                            "stale":true,
                            "partial":false,
                            "unknownFields":["account.cashBalance"]
                          },
                          "positions":[{
                            "symbol":"NVDA",
                            "currency":"USD",
                            "marketValue":120,
                            "profitLoss":20
                          }]
                        }
                        """, true, true)));
    }

    @Test
    void commitsRunningReservationBeforeHttpAndBlocksConcurrentExecution() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccessfulPortfolio(connectionId, USER_ID);
        stubSuccess(150);

        var first = CompletableFuture.supplyAsync(() -> postAnalysis(connectionId));
        awaitRunning(connectionId);

        mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_ALREADY_RUNNING"));

        assertThat(first.get(2, TimeUnit.SECONDS)).contains("\"status\":\"DEGRADED\"");
        ANALYSIS.verify(1, postRequestedFor(urlEqualTo("/internal/v1/portfolio-analyses")));
    }

    @Test
    void timeoutAndContractFailuresAreRecordedWithoutHidingPreviousSuccess() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccessfulPortfolio(connectionId, USER_ID);
        stubSuccess(0);
        var successfulBody = postAnalysis(connectionId);
        var successfulRunId = jsonString(successfulBody, "runId");

        ANALYSIS.resetAll();
        ANALYSIS.stubFor(post("/internal/v1/portfolio-analyses")
                .willReturn(aResponse().withFixedDelay(800).withStatus(200)));
        mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("ANALYSIS_TIMEOUT"));

        ANALYSIS.resetAll();
        ANALYSIS.stubFor(post("/internal/v1/portfolio-analyses")
                .willReturn(aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "requestId":"{{jsonPath request.body '$.requestId'}}",
                                  "schemaVersion":"1",
                                  "asOf":"2026-07-28T00:00:01Z",
                                  "status":"DEGRADED",
                                  "quality":{
                                    "stale":false,
                                    "partial":false,
                                    "unknownFields":["account.cashBalance"]
                                  },
                                  "positions":[{
                                    "symbol":"NVDA",
                                    "currency":"USD",
                                    "marketValue":120,
                                    "profitLoss":20,
                                    "weight":1
                                  }],
                                  "currencyTotals":[{
                                    "currency":"USD",
                                    "marketValue":120,
                                    "profitLoss":null,
                                    "concentration":1
                                  }]
                                }
                                """)));
        mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ANALYSIS_CONTRACT_ERROR"));

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio-analyses/latest", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(successfulRunId));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM analysis_runs WHERE status = 'FAILED'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM analysis_results",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void ownershipAndMissingDataAreNotLeakedAndStoredHistoryIsAppendOnly() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccessfulPortfolio(connectionId, USER_ID);
        stubSuccess(0);
        var body = postAnalysis(connectionId);
        var runId = UUID.fromString(jsonString(body, "runId"));

        mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(OTHER_USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));

        var emptyConnection = insertConnection(OTHER_USER_ID);
        mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", emptyConnection)
                        .with(user(OTHER_USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_SNAPSHOT_NOT_FOUND"));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE analysis_results SET schema_version = '2' WHERE analysis_run_id = ?",
                runId)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE analysis_runs SET error_code = 'CHANGED' WHERE id = ?",
                runId)).hasMessageContaining("already finished");
    }

    @Test
    void staleRunningAnalysisIsFailedBeforeNewRunWithoutDeletingSuccessfulResults() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var syncRunId = insertSuccessfulPortfolio(connectionId, USER_ID);
        stubSuccess(0);
        var firstRunId = UUID.fromString(jsonString(postAnalysis(connectionId), "runId"));

        var abandonedRunId = UUID.randomUUID();
        insertRunningAnalysis(connectionId, USER_ID, abandonedRunId, syncRunId, minutesAgo(16));

        var secondRunId = UUID.fromString(jsonString(postAnalysis(connectionId), "runId"));

        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM analysis_runs WHERE id = ?", abandonedRunId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM analysis_runs WHERE id = ?", String.class, firstRunId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM analysis_results WHERE analysis_run_id = ?",
                Integer.class, firstRunId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM analysis_runs WHERE id = ?", String.class, secondRunId))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void runningAnalysisJustUnderStaleThresholdStillBlocksNewExecution() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var syncRunId = insertSuccessfulPortfolio(connectionId, USER_ID);
        var runningId = UUID.randomUUID();
        insertRunningAnalysis(connectionId, USER_ID, runningId, syncRunId, secondsAgo(899));

        mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_ALREADY_RUNNING"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM analysis_runs WHERE id = ?", String.class, runningId))
                .isEqualTo("RUNNING");
    }

    @Test
    void runningAnalysisJustOverStaleThresholdIsReaped() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var syncRunId = insertSuccessfulPortfolio(connectionId, USER_ID);
        stubSuccess(0);
        var runningId = UUID.randomUUID();
        insertRunningAnalysis(connectionId, USER_ID, runningId, syncRunId, secondsAgo(901));

        mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM analysis_runs WHERE id = ?", runningId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
    }

    @Test
    void concurrentExecutionAgainstOneStaleRunningRowStartsExactlyOneNewRun() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var syncRunId = insertSuccessfulPortfolio(connectionId, USER_ID);
        stubSuccess(0);
        var abandonedRunId = UUID.randomUUID();
        insertRunningAnalysis(connectionId, USER_ID, abandonedRunId, syncRunId, minutesAgo(16));

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> attemptExecute(connectionId, ready, start));
            Future<String> second = executor.submit(() -> attemptExecute(connectionId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCEEDED", "ALREADY_RUNNING");
        }

        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM analysis_runs WHERE id = ?", abandonedRunId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM analysis_runs
                 WHERE broker_connection_id = ? AND status = 'RUNNING'
                """, Integer.class, connectionId)).isZero();
    }

    @Test
    void reapPersistsEvenWhenTheNewAttemptsHttpCallThenFails() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var syncRunId = insertSuccessfulPortfolio(connectionId, USER_ID);
        stubSuccess(0);
        var firstRunId = UUID.fromString(jsonString(postAnalysis(connectionId), "runId"));

        var abandonedRunId = UUID.randomUUID();
        insertRunningAnalysis(connectionId, USER_ID, abandonedRunId, syncRunId, minutesAgo(16));
        ANALYSIS.resetAll();
        ANALYSIS.stubFor(post("/internal/v1/portfolio-analyses")
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANALYSIS_SERVICE_UNAVAILABLE"));

        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM analysis_runs WHERE id = ?", abandonedRunId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM analysis_runs
                 WHERE broker_connection_id = ? AND status = 'RUNNING'
                """, Integer.class, connectionId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM analysis_runs WHERE id = ?", String.class, firstRunId))
                .isEqualTo("SUCCEEDED");

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio-analyses/latest", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(firstRunId.toString()));
    }

    @Test
    void readingLatestResultNeverReapsAStaleRunningRow() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccessfulPortfolio(connectionId, USER_ID);
        stubSuccess(0);
        postAnalysis(connectionId);

        var syncRunId = jdbc.queryForObject("""
                SELECT id FROM account_sync_runs
                 WHERE broker_connection_id = ? AND status = 'SUCCEEDED'
                """, UUID.class, connectionId);
        var runningId = UUID.randomUUID();
        insertRunningAnalysis(connectionId, USER_ID, runningId, syncRunId, minutesAgo(16));

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio-analyses/latest", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM analysis_runs WHERE id = ?", String.class, runningId))
                .isEqualTo("RUNNING");
    }

    private String attemptExecute(UUID connectionId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            service.execute(USER_ID, connectionId);
            return "SUCCEEDED";
        } catch (PortfolioAnalysisException exception) {
            return exception.code().name();
        }
    }

    private String postAnalysis(UUID connectionId) {
        try {
            return mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                            .with(user(USER_ID.toString()))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void awaitRunning(UUID connectionId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var count = jdbc.queryForObject("""
                    SELECT count(*) FROM analysis_runs
                     WHERE broker_connection_id = ? AND status = 'RUNNING'
                    """, Integer.class, connectionId);
            if (count != null && count == 1) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("RUNNING analysis was not committed before the HTTP call");
    }

    private void stubSuccess(int delayMillis) {
        ANALYSIS.stubFor(post("/internal/v1/portfolio-analyses")
                .willReturn(aResponse()
                        .withFixedDelay(delayMillis)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "requestId":"{{jsonPath request.body '$.requestId'}}",
                                  "schemaVersion":"1",
                                  "asOf":"2026-07-28T00:00:01Z",
                                  "status":"DEGRADED",
                                  "quality":{
                                    "stale":{{jsonPath request.body '$.quality.stale'}},
                                    "partial":{{jsonPath request.body '$.quality.partial'}},
                                    "unknownFields":["account.cashBalance"]
                                  },
                                  "positions":[{
                                    "symbol":"NVDA",
                                    "currency":"USD",
                                    "marketValue":120,
                                    "profitLoss":20,
                                    "weight":1
                                  }],
                                  "currencyTotals":[{
                                    "currency":"USD",
                                    "marketValue":120,
                                    "profitLoss":20,
                                    "concentration":1
                                  }]
                                }
                                """)));
    }

    private UUID insertConnection(UUID userId) {
        var connectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], SNAPSHOT_TIME, SNAPSHOT_TIME);
        return connectionId;
    }

    private UUID insertSuccessfulPortfolio(UUID connectionId, UUID userId) {
        var runId = UUID.randomUUID();
        insertRun(connectionId, userId, runId, "SUCCEEDED", null,
                SNAPSHOT_TIME, SNAPSHOT_TIME.plusSeconds(1));
        jdbc.update("""
                INSERT INTO account_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, account_type,
                    display_account_number, total_purchase_amounts, market_value_amounts,
                    market_value_after_cost_amounts, profit_loss_amounts,
                    profit_loss_after_cost_amounts, daily_profit_loss_amounts,
                    profit_loss_rate, profit_loss_rate_after_cost, daily_profit_loss_rate,
                    cash_balance_status, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'GENERAL', '****5678',
                    '{"USD":100}'::jsonb, '{"USD":120}'::jsonb, '{"USD":119}'::jsonb,
                    '{"USD":20}'::jsonb, '{"USD":19}'::jsonb, '{"USD":1}'::jsonb,
                    0.20, 0.19, 0.01, 'UNKNOWN', ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId, SNAPSHOT_TIME, SNAPSHOT_TIME);
        jdbc.update("""
                INSERT INTO position_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, symbol, name,
                    market_country, quantity, currency, average_price, last_price,
                    purchase_amount, market_value_amount, market_value_after_cost,
                    profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                    profit_loss_rate_after_cost, daily_profit_loss_amount,
                    daily_profit_loss_rate, commission, tax, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'NVDA', 'NVIDIA', 'US', 1, 'USD', 100, 120,
                    100, 120, 119, 20, 19, 0.20, 0.19, 1, 0.01, 1, NULL, ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId, SNAPSHOT_TIME, SNAPSHOT_TIME);
        insertCapacity(runId, userId, connectionId, "KRW", "1000000");
        insertCapacity(runId, userId, connectionId, "USD", "1000");
        return runId;
    }

    private void insertRun(
            UUID connectionId,
            UUID userId,
            UUID runId,
            String status,
            String errorCode,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) {
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, error_code, started_at, completed_at
                ) VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                """, runId, userId, connectionId, status, errorCode, startedAt, completedAt);
    }

    private void insertCapacity(
            UUID runId,
            UUID userId,
            UUID connectionId,
            String currency,
            String amount
    ) {
        jdbc.update("""
                INSERT INTO account_capacity_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, currency,
                    cash_buying_power, observed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS numeric), ?, ?)
                """, UUID.randomUUID(), runId, userId, connectionId, currency,
                amount, SNAPSHOT_TIME, SNAPSHOT_TIME);
    }

    private void insertRunningAnalysis(
            UUID connectionId,
            UUID userId,
            UUID runId,
            UUID inputSyncRunId,
            OffsetDateTime startedAt
    ) {
        jdbc.update("""
                INSERT INTO analysis_runs (
                    id, user_id, broker_connection_id, input_sync_run_id, status, started_at
                ) VALUES (?, ?, ?, ?, 'RUNNING', ?)
                """, runId, userId, connectionId, inputSyncRunId, startedAt);
    }

    private static OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }

    private static OffsetDateTime secondsAgo(int seconds) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(seconds);
    }

    private static String jsonString(String json, String field) {
        var marker = "\"" + field + "\":\"";
        var start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError(field + " missing: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
