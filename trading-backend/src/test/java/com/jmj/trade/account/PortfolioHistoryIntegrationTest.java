package com.jmj.trade.account;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TradingBackendApplication.class)
class PortfolioHistoryIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime TIME =
            OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE account_capacity_snapshots, position_snapshots, account_snapshots, "
                + "account_sync_runs, broker_connections, users CASCADE");
    }

    @Test
    void returnsSeparateKrwAndUsdAmountsPerPointWithoutConversion() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccess(connectionId, USER_ID, TIME, "110", "10");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").value(false))
                .andExpect(jsonPath("$.data.points.length()").value(1))
                .andExpect(jsonPath("$.data.points[0].marketValueAmounts.KRW").value(130000))
                .andExpect(jsonPath("$.data.points[0].marketValueAmounts.USD").value(110))
                .andExpect(jsonPath("$.data.points[0].profitLossAmounts.KRW").value(13000))
                .andExpect(jsonPath("$.data.points[0].profitLossAmounts.USD").value(10))
                .andExpect(jsonPath("$.data.points[0].profitLossRate").value(0.2))
                .andExpect(jsonPath("$.data.points[0].dailyProfitLossRate").value(0.01));
    }

    @Test
    void periodFilterExcludesPointsOutsideTheRequestedRange() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccess(connectionId, USER_ID, TIME, "100", "10");
        insertSuccess(connectionId, USER_ID, TIME.plusDays(10), "110", "11");
        insertSuccess(connectionId, USER_ID, TIME.plusDays(20), "120", "12");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("from", TIME.plusDays(5).toInstant().toString())
                        .param("to", TIME.plusDays(15).toInstant().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points.length()").value(1))
                .andExpect(jsonPath("$.data.points[0].marketValueAmounts.USD").value(110))
                .andExpect(jsonPath("$.data.totalMatched").value(1))
                .andExpect(jsonPath("$.data.partial").value(false));
    }

    @Test
    void downsamplesToTheRequestedMaxPointsKeepingFirstAndLast() throws Exception {
        var connectionId = insertConnection(USER_ID);
        for (var i = 0; i < 10; i++) {
            insertSuccess(connectionId, USER_ID, TIME.plusDays(i), String.valueOf(100 + i), "10");
        }

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("maxPoints", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points.length()").value(3))
                .andExpect(jsonPath("$.data.totalMatched").value(10))
                .andExpect(jsonPath("$.data.returnedPoints").value(3))
                .andExpect(jsonPath("$.data.partial").value(true))
                .andExpect(jsonPath("$.data.points[0].marketValueAmounts.USD").value(100))
                .andExpect(jsonPath("$.data.points[2].marketValueAmounts.USD").value(109));
    }

    @Test
    void returnsEveryPointWithoutDownsamplingWhenWithinMaxPoints() throws Exception {
        var connectionId = insertConnection(USER_ID);
        for (var i = 0; i < 5; i++) {
            insertSuccess(connectionId, USER_ID, TIME.plusDays(i), String.valueOf(100 + i), "10");
        }

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("maxPoints", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points.length()").value(5))
                .andExpect(jsonPath("$.data.partial").value(false));
    }

    @Test
    void surfacesStaleWhenTheLatestSyncFailedAfterTheLastSuccess() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccess(connectionId, USER_ID, TIME, "100", "10");
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, error_code, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'FAILED', 'BROKER_TEMPORARY', ?, ?)
                """, UUID.randomUUID(), USER_ID, connectionId,
                TIME.plusDays(1), TIME.plusDays(1).plusSeconds(1));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.staleReason").value("LATEST_SYNC_FAILED"))
                .andExpect(jsonPath("$.data.points.length()").value(1));
    }

    @Test
    void surfacesUnknownWhenTheLatestSnapshotHasUnknownCashBalance() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccess(connectionId, USER_ID, TIME, "100", "10");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unknown").value(true))
                .andExpect(jsonPath("$.unknownFields[0]").value("account.cashBalance"));
    }

    @Test
    void surfacesStaleWhenEveryHistoricalRunPredatesACredentialRotation() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccess(connectionId, USER_ID, TIME, "100", "10");
        jdbc.update(
                "UPDATE broker_connections SET credential_revision = 2 WHERE id = ?", connectionId);

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").value(false))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.staleReason").value("CREDENTIAL_REVISION_CHANGED"))
                .andExpect(jsonPath("$.data.points.length()").value(1));
    }

    @Test
    void unavailableWhenTheConnectionHasNoSuccessfulSyncEver() throws Exception {
        var connectionId = insertConnection(USER_ID);

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").value(true))
                .andExpect(jsonPath("$.unavailableReason").value("PORTFOLIO_HISTORY_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void hidesAnotherUsersConnectionHistory() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccess(connectionId, USER_ID, TIME, "100", "10");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
    }

    @Test
    void rejectsMaxPointsOutsideTheAllowedRange() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccess(connectionId, USER_ID, TIME, "100", "10");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("maxPoints", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_HISTORY_INPUT_INVALID"));
    }

    @Test
    void rejectsFromAfterTo() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertSuccess(connectionId, USER_ID, TIME, "100", "10");

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/portfolio-history", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("from", TIME.plusDays(5).toInstant().toString())
                        .param("to", TIME.toInstant().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_HISTORY_INPUT_INVALID"));
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

    private void insertSuccess(
            UUID connectionId,
            UUID userId,
            OffsetDateTime time,
            String usdMarketValue,
            String usdProfitLoss
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
                    CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                    0.20, 0.19, 0.01, 'UNKNOWN', ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId,
                "{\"KRW\":100,\"USD\":%s}".formatted(usdMarketValue),
                "{\"KRW\":130000,\"USD\":%s}".formatted(usdMarketValue),
                "{\"KRW\":129000,\"USD\":%s}".formatted(usdMarketValue),
                "{\"KRW\":13000,\"USD\":%s}".formatted(usdProfitLoss),
                "{\"KRW\":12000,\"USD\":%s}".formatted(usdProfitLoss),
                "{\"KRW\":100,\"USD\":1}",
                time, time);
    }
}
