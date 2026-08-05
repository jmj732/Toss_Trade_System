package com.jmj.trade.release;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.security.AccessTokenService;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.SellableQuantitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.CashBalanceStatus;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.MoneyByCurrency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the full advertised pipeline (bearer -> connection -> sync -> analysis -> event ->
 * order propose -> approve -> paper execution), then exercises the duplicate/idempotency and
 * crash-recovery edges of that same chain.
 * No production behavior is asserted beyond what {@code PaperOrderWorkflowApiIntegrationTest},
 * {@code EventIntelligenceIntegrationTest}, and {@code AccountSyncServiceIntegrationTest} already
 * cover in isolation; this test's value is that every step operates on entities produced by the
 * previous real step instead of hand-inserted fixtures.
 */
@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "analysis.service.connect-timeout=PT0.3S",
                "analysis.service.read-timeout=PT0.3S",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import(ReleaseWorkflowE2EIntegrationTest.PipelineBrokerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReleaseWorkflowE2EIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-28T00:00:00Z");
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
    private PipelineBrokerAdapter broker;

    @Autowired
    private AccessTokenService accessTokens;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbc.execute("""
                TRUNCATE paper_order_workflow_commands,
                         pre_trade_risk_decisions,
                         order_submission_outbox_events,
                         order_submission_audit_logs,
                         reconciliation_checks,
                         submission_attempts,
                         submission_idempotency_keys,
                         order_intent_outbox_events,
                         order_intent_audit_logs,
                         execution_snapshots,
                         broker_orders,
                         real_order_daily_reservations, real_order_account_allowlist, order_intents,
                         broker_accounts,
                         event_review_commands,
                         event_reviews,
                         event_analysis_comparisons,
                         intelligence_events,
                         analysis_results,
                         analysis_runs,
                         account_capacity_snapshots,
                         position_snapshots,
                         account_snapshots,
                         account_sync_runs,
                         broker_connections,
                         users
                CASCADE
                """);
        broker.reset();
        ANALYSIS.resetAll();
        stubAnalysisSuccess();
    }

    @AfterAll
    static void stopWireMock() {
        ANALYSIS.stop();
    }

    @Test
    void deniesUnauthenticatedSessionBeforeAnyPipelineStep() throws Exception {
        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void runsFullPipelineFromLoginThroughPaperExecution() throws Exception {
        var csrf = bootstrapSession();

        var connectionId = createAndVerifyConnection(csrf);
        assertThat(broker.calls).containsExactly("accounts");

        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/portfolio-syncs", connectionId))
                .andExpect(status().isOk());
        assertThat(broker.calls).containsExactly(
                "accounts", "accounts", "account", "positions", "capacity:KRW", "capacity:USD");

        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("COMPLETED"));

        var eventId = createEvent(csrf, connectionId);
        reviewEvent(csrf, connectionId, eventId, "CONFIRMED", 0L, "review-1");

        var orderId = proposeOrder(csrf, connectionId, "propose-1");
        mockMvc.perform(get("/api/v1/paper-orders/{id}", orderId)
                        .header("Authorization", csrf.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"));

        approveOrder(csrf, orderId, "approve-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.riskDecisions.length()").value(2))
                .andExpect(jsonPath("$.riskDecisions[0].outcome").value("APPROVED"))
                .andExpect(jsonPath("$.riskDecisions[1].outcome").value("APPROVED"));
        assertThat(broker.quoteCalls).hasValue(1);
        assertThat(count("SELECT count(*) FROM broker_orders")).isEqualTo(1);
    }

    @Test
    void duplicateRequestsAtEveryMutatingStepProduceExactlyOneEffect() throws Exception {
        var csrf = bootstrapSession();
        var connectionId = createAndVerifyConnection(csrf);

        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/portfolio-syncs", connectionId))
                .andExpect(status().isOk());
        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId))
                .andExpect(status().isOk());

        var eventId = createEvent(csrf, connectionId);
        reviewEvent(csrf, connectionId, eventId, "CONFIRMED", 0L, "review-dup")
                .andExpect(status().isOk());
        reviewEvent(csrf, connectionId, eventId, "CONFIRMED", 0L, "review-dup")
                .andExpect(status().isOk());
        assertThat(count(
                "SELECT count(*) FROM event_review_commands WHERE idempotency_key = 'review-dup'"))
                .isEqualTo(1);

        var orderId = proposeOrder(csrf, connectionId, "propose-dup");
        var replayedOrderId = proposeOrder(csrf, connectionId, "propose-dup");
        assertThat(replayedOrderId).isEqualTo(orderId);
        assertThat(count("SELECT count(*) FROM order_intents")).isEqualTo(1);

        approveOrder(csrf, orderId, "approve-dup").andExpect(status().isOk());
        approveOrder(csrf, orderId, "approve-dup")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(count("SELECT count(*) FROM broker_orders")).isEqualTo(1);
        assertThat(broker.quoteCalls).hasValue(1);
    }

    @Test
    void oversizedOrderIsBlockedByRiskWithoutPaperExecution() throws Exception {
        var csrf = bootstrapSession();
        var connectionId = createAndVerifyConnection(csrf);
        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/portfolio-syncs", connectionId))
                .andExpect(status().isOk());

        var orderId = idFrom(mockMvc.perform(csrf.post("/api/v1/paper-orders")
                        .header("Idempotency-Key", "propose-oversized")
                        .content(proposalJson(connectionId, "AAPL", "1000")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        approveOrder(csrf, UUID.fromString(orderId), "approve-oversized", "1000", "100000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.riskDecisions[0].outcome").value("BLOCKED"));
        assertThat(broker.quoteCalls).hasValue(1);
        assertThat(count("SELECT count(*) FROM broker_orders")).isZero();
    }

    @Test
    void crashedSyncRowIsRecoveredAutomaticallyByARealRestartedSync() throws Exception {
        var csrf = bootstrapSession();
        var connectionId = createAndVerifyConnection(csrf);
        var abandonedRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision, status, started_at
                ) VALUES (?, ?, ?, 1, 'RUNNING', ?)
                """, abandonedRunId, USER_ID, connectionId, minutesAgo(30));

        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/portfolio-syncs", connectionId))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM account_sync_runs WHERE id = ?", abandonedRunId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
        assertThat(countWhere("account_sync_runs", "status = 'SUCCEEDED'")).isEqualTo(1);
    }

    @Test
    void crashedAnalysisRowIsRecoveredAutomaticallyByARealRestartedAnalysis() throws Exception {
        var csrf = bootstrapSession();
        var connectionId = createAndVerifyConnection(csrf);
        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/portfolio-syncs", connectionId))
                .andExpect(status().isOk());
        var syncRunId = jdbc.queryForObject("""
                SELECT id FROM account_sync_runs WHERE broker_connection_id = ? AND status = 'SUCCEEDED'
                """, UUID.class, connectionId);
        var abandonedRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_runs (
                    id, user_id, broker_connection_id, input_sync_run_id, status, started_at
                ) VALUES (?, ?, ?, ?, 'RUNNING', ?)
                """, abandonedRunId, USER_ID, connectionId, syncRunId, minutesAgo(30));

        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM analysis_runs WHERE id = ?", abandonedRunId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
        assertThat(countWhere("analysis_runs", "status = 'SUCCEEDED'")).isEqualTo(1);
    }

    private Bearer bootstrapSession() throws Exception {
        var access = accessTokens.issue(USER_ID, UUID.randomUUID(), Instant.now());
        var body = mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", "Bearer " + access.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("csrfHeaderName", "csrfToken");
        return new Bearer(access.value());
    }

    private UUID createAndVerifyConnection(Bearer csrf) throws Exception {
        var created = mockMvc.perform(csrf.post("/api/v1/broker-connections/toss")
                        .content(credentialsJson("client-id", "client-secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andReturn().getResponse().getContentAsString();
        var connectionId = UUID.fromString(idFrom(created));

        mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/verify", connectionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        return connectionId;
    }

    private UUID createEvent(Bearer csrf, UUID connectionId) throws Exception {
        var body = mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/events", connectionId)
                        .content("""
                                {
                                  "source":"NEWS",
                                  "sourceEventId":"evt-1",
                                  "type":"EARNINGS",
                                  "summary":"AAPL beats consensus",
                                  "affectedSymbols":["AAPL"],
                                  "occurredAt":"2026-07-28T00:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(idFrom(body));
    }

    private ResultActions reviewEvent(
            Bearer csrf,
            UUID connectionId,
            UUID eventId,
            String targetStatus,
            long expectedVersion,
            String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(csrf.post(
                        "/api/v1/broker-connections/{connectionId}/events/{eventId}/review",
                        connectionId, eventId)
                .header("Idempotency-Key", idempotencyKey)
                .content("""
                        {"status":"%s","expectedVersion":%d}
                        """.formatted(targetStatus, expectedVersion)));
    }

    private UUID proposeOrder(Bearer csrf, UUID connectionId, String idempotencyKey) throws Exception {
        var body = mockMvc.perform(csrf.post("/api/v1/paper-orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .content(proposalJson(connectionId, "AAPL", "1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(idFrom(body));
    }

    private ResultActions approveOrder(
            Bearer csrf,
            UUID orderId,
            String idempotencyKey
    ) throws Exception {
        return approveOrder(csrf, orderId, idempotencyKey, "1", "100");
    }

    private ResultActions approveOrder(
            Bearer csrf,
            UUID orderId,
            String idempotencyKey,
            String displayedQuantity,
            String displayedMaxLoss
    ) throws Exception {
        var stepUpToken = "stepup-" + idempotencyKey;
        insertStepUpToken(orderId, stepUpToken);
        return mockMvc.perform(csrf.post("/api/v1/paper-orders/{id}/approve", orderId)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Step-Up-Token", stepUpToken)
                .content("""
                        {
                          "channel":"WEB",
                          "displayedQuantity":%s,
                          "displayedMaxLoss":%s,
                          "displayedCurrency":"USD",
                          "proposalVersion":null
                        }
                        """.formatted(displayedQuantity, displayedMaxLoss)));
    }

    private void insertStepUpToken(UUID orderId, String rawToken) {
        var now = Instant.now();
        jdbc.update("""
                INSERT INTO order_approval_step_up_tokens (
                    token_hash, user_id, order_intent_id, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, ?, NULL)
                ON CONFLICT (token_hash) DO NOTHING
                """,
                com.jmj.trade.order.OrderApprovalStepUpService.sha256Hex(rawToken),
                USER_ID,
                orderId,
                OffsetDateTime.ofInstant(now.minusSeconds(1), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC));
    }

    private void stubAnalysisSuccess() {
        ANALYSIS.stubFor(post("/internal/v1/portfolio-analyses")
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "requestId":"{{jsonPath request.body '$.requestId'}}",
                                  "schemaVersion":"1",
                                  "asOf":"{{jsonPath request.body '$.asOf'}}",
                                  "status":"COMPLETED",
                                  "quality":{"stale":false,"partial":false,"unknownFields":[]},
                                  "positions":[{
                                    "symbol":"AAPL",
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

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long countWhere(String table, String predicate) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class);
    }

    private static OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }

    private static String proposalJson(UUID connectionId, String symbol, String quantity) {
        return """
                {
                  "connectionId":"%s",
                  "side":"BUY",
                  "type":"MARKET",
                  "symbol":"%s",
                  "quantity":%s,
                  "limitPrice":null,
                  "currency":"USD",
                  "channel":"WEB"
                }
                """.formatted(connectionId, symbol, quantity);
    }

    private static String credentialsJson(String clientId, String clientSecret) {
        return """
                {"clientId":"%s","clientSecret":"%s"}
                """.formatted(clientId, clientSecret);
    }

    private static String idFrom(String json) {
        return field(json, "id");
    }

    private static String field(String json, String name) {
        var marker = "\"" + name + "\":\"";
        var start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError(name + " missing: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    /** Wraps every request with the same short-lived bearer token as the dashboard. */
    private final class Bearer {
        private final String token;

        private Bearer(String token) {
            this.token = token;
        }

        private String authorization() {
            return "Bearer " + token;
        }

        MockHttpServletRequestBuilder post(String urlTemplate, Object... vars) {
            return MockMvcRequestBuilders.post(urlTemplate, vars)
                    .header("Authorization", authorization())
                    .contentType(MediaType.APPLICATION_JSON);
        }
    }

    @TestConfiguration
    static class PipelineBrokerConfiguration {

        @Bean
        PipelineBrokerAdapter brokerAdapter() {
            return new PipelineBrokerAdapter();
        }
    }

    static final class PipelineBrokerAdapter implements BrokerAdapter {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final AtomicInteger quoteCalls = new AtomicInteger();
        private BrokerAccountRef account;

        void reset() {
            calls.clear();
            quoteCalls.set(0);
            account = null;
        }

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            calls.add("accounts");
            if (account == null) {
                account = new BrokerAccountRef(
                        connection.brokerConnectionId(), "12345678", "GENERAL", "****5678");
            }
            return new BrokerResponse<>(List.of(new BrokerAccountView(account, "Primary")), metadata());
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            calls.add("account");
            return new BrokerResponse<>(new AccountSnapshot(
                    account,
                    money("10000"), money("10000"), money("9990"),
                    money("20"), money("19"),
                    new BigDecimal("0.20"), new BigDecimal("0.19"),
                    money("1"), new BigDecimal("0.01"),
                    CashBalanceStatus.KNOWN, OBSERVED_AT), metadata());
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            calls.add("positions");
            return new BrokerResponse<>(List.of(new Position(
                    account, "AAPL", "Apple", "US", BigDecimal.ONE, Currency.USD,
                    new BigDecimal("100"), new BigDecimal("120"), new BigDecimal("100"),
                    new BigDecimal("120"), new BigDecimal("119"), new BigDecimal("20"),
                    new BigDecimal("19"), new BigDecimal("0.20"), new BigDecimal("0.19"),
                    BigDecimal.ONE, new BigDecimal("0.01"), BigDecimal.ONE, null, OBSERVED_AT
            )), metadata());
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(
                BrokerAccountRef account,
                Currency currency
        ) {
            calls.add("capacity:" + currency);
            return new BrokerResponse<>(
                    new AccountCapacitySnapshot(account, currency, new BigDecimal("100000"), OBSERVED_AT),
                    metadata());
        }

        @Override
        public BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol) {
            return new BrokerResponse<>(
                    SellableQuantitySnapshot.unknown(account, symbol, OBSERVED_AT),
                    metadata());
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            quoteCalls.incrementAndGet();
            return new BrokerResponse<>(
                    new Quote(connection, symbol, Currency.USD,
                            new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("100"),
                            OBSERVED_AT, OBSERVED_AT),
                    metadata());
        }

        private static MoneyByCurrency money(String amount) {
            return new MoneyByCurrency(Map.of(Currency.USD, new BigDecimal(amount)));
        }

        private static BrokerCallMetadata metadata() {
            return new BrokerCallMetadata("request", OBSERVED_AT, Optional.empty());
        }
    }
}
