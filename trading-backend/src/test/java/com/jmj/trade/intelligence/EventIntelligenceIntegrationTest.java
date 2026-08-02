package com.jmj.trade.intelligence;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.intelligence.ingestion.MarketEvent;
import com.jmj.trade.intelligence.ingestion.MarketEventProviderId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "analysis.service.connect-timeout=PT0.3S",
                "analysis.service.read-timeout=PT0.3S"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventIntelligenceIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime TIME =
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
    private EventIntelligenceService eventIntelligenceService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
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
    void storesReadsAndDeduplicatesOwnedEvents() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var eventId = postEvent(connectionId, "wire-42");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/events/{eventId}",
                        connectionId, eventId).with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("manual"))
                .andExpect(jsonPath("$.sourceEventId").value("wire-42"))
                .andExpect(jsonPath("$.affectedSymbols[0]").value("NVDA"))
                .andExpect(jsonPath("$.occurredAt").value("2026-07-28T00:00:00Z"))
                .andExpect(jsonPath("$.collectedAt").exists())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.reviewVersion").value(0))
                .andExpect(jsonPath("$.analysisComparison").doesNotExist());

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/events", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(eventId.toString()))
                .andExpect(jsonPath("$[0].comparisonAvailable").value(false));

        mockMvc.perform(post("/api/v1/broker-connections/{connectionId}/events", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("wire-42")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_EXISTS"));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/events/{eventId}",
                        connectionId, eventId).with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM intelligence_events", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void notificationOutboxFailureRollsBackTheEventInsertToo() {
        var connectionId = insertConnection(USER_ID);
        jdbc.execute("""
                CREATE FUNCTION fail_notification_outbox_insert()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced notification outbox failure';
                END;
                $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_fail_notification_outbox_insert
                BEFORE INSERT ON notification_outbox_events
                FOR EACH ROW
                EXECUTE FUNCTION fail_notification_outbox_insert()
                """);
        try {
            assertThatThrownBy(() -> eventIntelligenceService.create(
                    USER_ID,
                    connectionId,
                    new EventIntelligenceService.CreateEvent(
                            "manual", "wire-rollback", "EARNINGS", "NVIDIA earnings",
                            List.of("NVDA"), Instant.parse("2026-07-28T00:00:00Z"))))
                    .isInstanceOf(RuntimeException.class);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM intelligence_events", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM notification_outbox_events", Integer.class))
                    .isZero();
        } finally {
            // notification_outbox_events is shared by every other test in the notification
            // feature, so this table-wide trigger must not outlive this test.
            jdbc.execute("DROP TRIGGER trg_fail_notification_outbox_insert ON notification_outbox_events");
            jdbc.execute("DROP FUNCTION fail_notification_outbox_insert()");
        }
    }

    @Test
    void reanalyzesLatestPortfolioAndStoresAppendOnlyComparison() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccessfulPortfolio(connectionId, USER_ID, "120", TIME);
        stubAnalysis();
        var previousRunId = postAnalysis(connectionId);
        var eventId = postEvent(connectionId, "earnings-1");
        insertSuccessfulPortfolio(connectionId, USER_ID, "150", TIME.plusMinutes(1));

        var result = mockMvc.perform(post(
                                "/api/v1/broker-connections/{connectionId}/events/{eventId}/reanalyze",
                                connectionId, eventId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.previousAnalysisRunId").value(previousRunId))
                .andExpect(jsonPath("$.comparison.baselineAvailable").value(true))
                .andExpect(jsonPath("$.comparison.positions[0].symbol").value("NVDA"))
                .andExpect(jsonPath("$.comparison.positions[0].marketValueChange").value(30))
                .andExpect(jsonPath("$.comparison.currencyTotals[0].marketValueChange").value(30))
                .andReturn().getResponse().getContentAsString();

        var newRunId = jsonString(result, "newAnalysisRunId");
        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/events/{eventId}/comparison",
                                connectionId, eventId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newAnalysisRunId").value(newRunId));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/events/{eventId}",
                        connectionId, eventId).with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.analysisComparison.newAnalysisRunId").value(newRunId))
                .andExpect(jsonPath("$.analysisComparison.comparison.positions[0].symbol")
                        .value("NVDA"));
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/events", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].comparisonAvailable").value(true));

        ANALYSIS.verify(2, postRequestedFor(urlEqualTo("/internal/v1/portfolio-analyses")));
        jdbc.execute("ALTER TABLE event_analysis_comparisons DISABLE TRIGGER "
                + "trg_event_comparisons_append_only");
        try {
            jdbc.update("DELETE FROM event_analysis_comparisons WHERE event_id = ?", eventId);
        } finally {
            jdbc.execute("ALTER TABLE event_analysis_comparisons ENABLE TRIGGER "
                    + "trg_event_comparisons_append_only");
        }
        ANALYSIS.resetRequests();
        mockMvc.perform(post(
                                "/api/v1/broker-connections/{connectionId}/events/{eventId}/reanalyze",
                                connectionId, eventId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newAnalysisRunId").value(newRunId));
        ANALYSIS.verify(0, postRequestedFor(urlEqualTo("/internal/v1/portfolio-analyses")));

        mockMvc.perform(post(
                                "/api/v1/broker-connections/{connectionId}/events/{eventId}/reanalyze",
                                connectionId, eventId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_ANALYZED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_intents", Integer.class))
                .isZero();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE event_analysis_comparisons SET comparison = '{}'::jsonb WHERE event_id = ?",
                eventId)).hasMessageContaining("append-only");
    }

    @Test
    void automatedMacroEventUsesTheExistingReviewAndReanalyzeFlow() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccessfulPortfolio(connectionId, USER_ID, "120", TIME);
        stubAnalysis();
        postAnalysis(connectionId);

        assertThat(eventIntelligenceService.ingest(
                USER_ID,
                connectionId,
                new MarketEvent(
                        MarketEventProviderId.FRED,
                        "CPIAUCSL:2026-07-28:2026-08-01:120.1",
                        "FRED_OBSERVATION",
                        "CPI observation",
                        TIME.toInstant(),
                        List.of(),
                        List.of(new EventIntelligenceService.MacroScope(
                                "FRED", "CPIAUCSL", "2026-07", "2026-08-01")))))
                .isTrue();
        var eventId = jdbc.queryForObject(
                "SELECT id FROM intelligence_events WHERE source = 'FRED'", UUID.class);
        insertSuccessfulPortfolio(connectionId, USER_ID, "150", TIME.plusMinutes(1));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/events/{eventId}",
                        connectionId, eventId).with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.macroScope[0].provider").value("FRED"))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"));

        mockMvc.perform(post(
                                "/api/v1/broker-connections/{connectionId}/events/{eventId}/reanalyze",
                                connectionId, eventId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.comparison.baselineAvailable").value(true));
    }

    @Test
    void reviewCommandsAreOwnedIdempotentAndOptimisticallyConcurrent() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var eventId = postEvent(connectionId, "review-1");

        var first = CompletableFuture.supplyAsync(() ->
                postReview(connectionId, eventId, "review-a", "CONFIRMED", 0));
        var second = CompletableFuture.supplyAsync(() ->
                postReview(connectionId, eventId, "review-b", "HELD", 0));
        var attempts = List.of(
                first.get(2, TimeUnit.SECONDS),
                second.get(2, TimeUnit.SECONDS));

        assertThat(attempts).extracting(ReviewAttempt::status)
                .containsExactlyInAnyOrder(200, 409);
        var accepted = attempts.stream().filter(attempt -> attempt.status() == 200)
                .findFirst().orElseThrow();

        mockMvc.perform(post(
                                "/api/v1/broker-connections/{connectionId}/events/{eventId}/review",
                                connectionId, eventId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", accepted.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson(accepted.reviewStatus(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value(accepted.reviewStatus()));

        mockMvc.perform(post(
                                "/api/v1/broker-connections/{connectionId}/events/{eventId}/review",
                                connectionId, eventId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", accepted.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewJson("IGNORED", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_REVIEW_CONFLICT"));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/events/{eventId}",
                        connectionId, eventId).with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_review_commands", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_intents", Integer.class))
                .isZero();
    }

    @Test
    void sameIdempotencyKeyCannotRaceAcrossEvents() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var firstEvent = postEvent(connectionId, "review-race-1");
        var secondEvent = postEvent(connectionId, "review-race-2");

        var first = CompletableFuture.supplyAsync(() ->
                postReview(connectionId, firstEvent, "shared-review", "CONFIRMED", 0));
        var second = CompletableFuture.supplyAsync(() ->
                postReview(connectionId, secondEvent, "shared-review", "IGNORED", 0));

        assertThat(List.of(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS)))
                .extracting(ReviewAttempt::status)
                .containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_review_commands", Integer.class))
                .isEqualTo(1);
    }

    private UUID postEvent(UUID connectionId, String sourceEventId) throws Exception {
        var body = mockMvc.perform(post("/api/v1/broker-connections/{connectionId}/events", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(sourceEventId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonString(body, "id"));
    }

    private String postAnalysis(UUID connectionId) throws Exception {
        var body = mockMvc.perform(post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonString(body, "runId");
    }

    private ReviewAttempt postReview(
            UUID connectionId,
            UUID eventId,
            String key,
            String reviewStatus,
            int expectedVersion
    ) {
        try {
            var status = mockMvc.perform(post(
                                    "/api/v1/broker-connections/{connectionId}/events/{eventId}/review",
                                    connectionId, eventId)
                            .with(user(USER_ID.toString()))
                            .with(csrf())
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reviewJson(reviewStatus, expectedVersion)))
                    .andReturn().getResponse().getStatus();
            return new ReviewAttempt(key, reviewStatus, status);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void stubAnalysis() {
        ANALYSIS.stubFor(post("/internal/v1/portfolio-analyses")
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "requestId":"{{jsonPath request.body '$.requestId'}}",
                                  "schemaVersion":"1",
                                  "asOf":"{{jsonPath request.body '$.asOf'}}",
                                  "status":"DEGRADED",
                                  "quality":{{jsonPath request.body '$.quality'}},
                                  "positions":[{
                                    "symbol":"NVDA",
                                    "currency":"USD",
                                    "marketValue":{{jsonPath request.body '$.positions[0].marketValue'}},
                                    "profitLoss":20,
                                    "weight":1
                                  }],
                                  "currencyTotals":[{
                                    "currency":"USD",
                                    "marketValue":{{jsonPath request.body '$.positions[0].marketValue'}},
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
                """, connectionId, userId, new byte[17], new byte[12], TIME, TIME);
        return connectionId;
    }

    private void insertSuccessfulPortfolio(
            UUID connectionId,
            UUID userId,
            String marketValue,
            OffsetDateTime time
    ) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, userId, connectionId, time, time.plusSeconds(1));
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
                    '{"USD":100}'::jsonb, jsonb_build_object('USD', CAST(? AS numeric)),
                    '{"USD":119}'::jsonb, '{"USD":20}'::jsonb, '{"USD":19}'::jsonb,
                    '{"USD":1}'::jsonb, 0.20, 0.19, 0.01, 'UNKNOWN', ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId, marketValue, time, time);
        jdbc.update("""
                INSERT INTO position_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, symbol, name,
                    market_country, quantity, currency, average_price, last_price,
                    purchase_amount, market_value_amount, market_value_after_cost,
                    profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                    profit_loss_rate_after_cost, daily_profit_loss_amount,
                    daily_profit_loss_rate, commission, tax, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'NVDA', 'NVIDIA', 'US', 1, 'USD', 100, CAST(? AS numeric),
                    100, CAST(? AS numeric), 119, 20, 19, 0.20, 0.19, 1, 0.01, 1, NULL, ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId,
                marketValue, marketValue, time, time);
        insertCapacity(runId, userId, connectionId, "KRW", time);
        insertCapacity(runId, userId, connectionId, "USD", time);
    }

    private void insertCapacity(
            UUID runId,
            UUID userId,
            UUID connectionId,
            String currency,
            OffsetDateTime time
    ) {
        jdbc.update("""
                INSERT INTO account_capacity_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, currency,
                    cash_buying_power, observed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, 1000, ?, ?)
                """, UUID.randomUUID(), runId, userId, connectionId, currency, time, time);
    }

    private static String eventJson(String sourceEventId) {
        return """
                {
                  "source":"manual",
                  "sourceEventId":"%s",
                  "type":"EARNINGS",
                  "summary":"NVIDIA earnings",
                  "affectedSymbols":[" nvda "],
                  "occurredAt":"2026-07-28T00:00:00Z"
                }
                """.formatted(sourceEventId);
    }

    private static String reviewJson(String status, int expectedVersion) {
        return """
                {"status":"%s","expectedVersion":%d}
                """.formatted(status, expectedVersion);
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

    private record ReviewAttempt(String key, String reviewStatus, int status) {
    }
}
