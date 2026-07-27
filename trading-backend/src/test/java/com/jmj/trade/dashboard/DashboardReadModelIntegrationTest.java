package com.jmj.trade.dashboard;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.analysis.PortfolioAnalysisWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TradingBackendApplication.class)
class DashboardReadModelIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime TIME =
            OffsetDateTime.of(2026, 7, 28, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE broker_connections, users CASCADE");
    }

    @Test
    void readsLatestLedgersWithPerSectionQualityAndSetCollections() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var analyzedSnapshot = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysis(connectionId, USER_ID, analyzedSnapshot, TIME.plusSeconds(2));
        var latestSnapshot = insertPortfolio(
                connectionId, USER_ID, TIME.plusMinutes(1), false);
        insertFailedRun(connectionId, USER_ID, TIME.plusMinutes(2));

        var pendingEventA = insertEvent(connectionId, USER_ID, "event-a", TIME);
        var pendingEventB = insertEvent(
                connectionId, USER_ID, "event-b", TIME.plusSeconds(1));
        var handledEvent = insertEvent(
                connectionId, USER_ID, "event-c", TIME.plusSeconds(2));
        jdbc.update("""
                INSERT INTO event_reviews (
                    event_id, user_id, broker_connection_id, status, version, reviewed_at
                ) VALUES (?, ?, ?, 'CONFIRMED', 1, ?)
                """, handledEvent, USER_ID, connectionId, TIME.plusSeconds(3));

        insertOrder(connectionId, USER_ID, "AAPL", "PROPOSED");
        insertOrder(connectionId, USER_ID, "MSFT", "PROPOSED");
        insertOrder(connectionId, USER_ID, "NVDA", "APPROVED");

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolio.data.syncRunId")
                        .value(latestSnapshot.toString()))
                .andExpect(jsonPath("$.portfolio.stale").value(true))
                .andExpect(jsonPath("$.portfolio.unknown").value(true))
                .andExpect(jsonPath("$.portfolio.unknownFields[0]")
                        .value("account.cashBalance"))
                .andExpect(jsonPath("$.portfolio.unavailable").value(false))
                .andExpect(jsonPath("$.analysis.data.inputSnapshotId")
                        .value(analyzedSnapshot.toString()))
                .andExpect(jsonPath("$.analysis.stale").value(true))
                .andExpect(jsonPath("$.analysis.unknown").value(true))
                .andExpect(jsonPath("$.analysis.unavailable").value(false))
                .andExpect(jsonPath("$.pendingEvents.data[*].id", containsInAnyOrder(
                        pendingEventA.toString(), pendingEventB.toString())))
                .andExpect(jsonPath("$.pendingEvents.unavailable").value(false))
                .andExpect(jsonPath("$.pendingOrderProposals.data[*].symbol",
                        containsInAnyOrder("AAPL", "MSFT")))
                .andExpect(jsonPath("$.pendingOrderProposals.unavailable").value(false));
    }

    @Test
    void marksMissingSnapshotAndAnalysisUnavailableWithoutWritingState() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var eventsBefore = count("intelligence_events");
        var ordersBefore = count("order_intents");

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolio.unavailable").value(true))
                .andExpect(jsonPath("$.portfolio.unavailableReason")
                        .value("PORTFOLIO_SNAPSHOT_NOT_FOUND"))
                .andExpect(jsonPath("$.analysis.unavailable").value(true))
                .andExpect(jsonPath("$.analysis.unavailableReason")
                        .value("ANALYSIS_RESULT_NOT_FOUND"))
                .andExpect(jsonPath("$.pendingEvents.data").isEmpty())
                .andExpect(jsonPath("$.pendingOrderProposals.data").isEmpty());

        org.assertj.core.api.Assertions.assertThat(count("intelligence_events"))
                .isEqualTo(eventsBefore);
        org.assertj.core.api.Assertions.assertThat(count("order_intents"))
                .isEqualTo(ordersBefore);
    }

    @Test
    void hidesAnotherUsersDashboard() throws Exception {
        var connectionId = insertConnection(USER_ID);

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
    }

    @Test
    void queryCountDoesNotGrowWithEventOrProposalRows() {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysis(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2));
        insertEvent(connectionId, USER_ID, "event-0", TIME);
        insertOrder(connectionId, USER_ID, "SYM0", "PROPOSED");
        var countingJdbc = new CountingJdbcTemplate(dataSource);
        var service = new DashboardReadModelService(
                countingJdbc,
                objectMapper,
                new PortfolioReadService(countingJdbc, objectMapper),
                new PortfolioAnalysisWorkflowService(
                        countingJdbc,
                        transactionManager,
                        objectMapper,
                        "http://localhost:8000",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)));

        service.read(USER_ID, connectionId);
        var oneRowQueries = countingJdbc.count();

        for (var index = 1; index < 20; index++) {
            insertEvent(connectionId, USER_ID, "event-" + index, TIME.plusSeconds(index));
            insertOrder(connectionId, USER_ID, "SYM" + index, "PROPOSED");
        }
        countingJdbc.reset();

        service.read(USER_ID, connectionId);

        org.assertj.core.api.Assertions.assertThat(oneRowQueries).isEqualTo(9);
        org.assertj.core.api.Assertions.assertThat(countingJdbc.count())
                .isEqualTo(oneRowQueries);
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

    private UUID insertPortfolio(
            UUID connectionId,
            UUID userId,
            OffsetDateTime time,
            boolean includeUsd
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
                    '{"USD":100}'::jsonb, '{"USD":120}'::jsonb, '{"USD":119}'::jsonb,
                    '{"USD":20}'::jsonb, '{"USD":19}'::jsonb, '{"USD":1}'::jsonb,
                    0.20, 0.19, 0.01, 'UNKNOWN', ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId, time, time);
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
                """, UUID.randomUUID(), runId, userId, connectionId, time, time);
        insertCapacity(runId, userId, connectionId, "KRW", time);
        if (includeUsd) {
            insertCapacity(runId, userId, connectionId, "USD", time);
        }
        return runId;
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

    private void insertFailedRun(UUID connectionId, UUID userId, OffsetDateTime time) {
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, error_code, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'FAILED', 'BROKER_TEMPORARY', ?, ?)
                """, UUID.randomUUID(), userId, connectionId, time, time.plusSeconds(1));
    }

    private void insertAnalysis(
            UUID connectionId,
            UUID userId,
            UUID snapshotId,
            OffsetDateTime time
    ) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_runs (
                    id, user_id, broker_connection_id, input_sync_run_id,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, ?)
                """, runId, userId, connectionId, snapshotId, time, time.plusSeconds(1));
        jdbc.update("""
                INSERT INTO analysis_results (
                    id, analysis_run_id, user_id, broker_connection_id, input_sync_run_id,
                    schema_version, result_status, response, created_at
                ) VALUES (?, ?, ?, ?, ?, '1', 'DEGRADED', CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), runId, userId, connectionId, snapshotId, """
                {
                  "requestId":"%s",
                  "schemaVersion":"1",
                  "asOf":"2026-07-28T00:00:00Z",
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
                    "profitLoss":20,
                    "concentration":1
                  }]
                }
                """.formatted(runId), time.plusSeconds(1));
    }

    private UUID insertEvent(
            UUID connectionId,
            UUID userId,
            String sourceEventId,
            OffsetDateTime time
    ) {
        var eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO intelligence_events (
                    id, user_id, broker_connection_id, source, source_event_id,
                    event_type, summary, affected_symbols, occurred_at, collected_at
                ) VALUES (?, ?, ?, 'manual', ?, 'EARNINGS', 'summary',
                          '["NVDA"]'::jsonb, ?, ?)
                """, eventId, userId, connectionId, sourceEventId, time, time);
        return eventId;
    }

    private void insertOrder(
            UUID connectionId,
            UUID userId,
            String symbol,
            String status
    ) {
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?) ON CONFLICT DO NOTHING",
                connectionId);
        jdbc.update("""
                INSERT INTO order_intents (
                    id, broker_account_id, user_id, broker_connection_id, quantity,
                    side, order_type, symbol, limit_price, trading_currency, status, version
                ) VALUES (?, ?, ?, ?, 1, 'BUY', 'MARKET', ?, NULL, 'USD', ?, 0)
                """, UUID.randomUUID(), connectionId, userId, connectionId, symbol, status);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {

        private final AtomicInteger queries = new AtomicInteger();

        private CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> T query(
                PreparedStatementCreator creator,
                PreparedStatementSetter setter,
                ResultSetExtractor<T> extractor
        ) {
            queries.incrementAndGet();
            return super.query(creator, setter, extractor);
        }

        private int count() {
            return queries.get();
        }

        private void reset() {
            queries.set(0);
        }
    }
}
