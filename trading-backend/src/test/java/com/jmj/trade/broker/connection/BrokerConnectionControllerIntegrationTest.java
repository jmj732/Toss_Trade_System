package com.jmj.trade.broker.connection;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.SellableQuantitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.CashBalanceStatus;
import com.jmj.trade.broker.MoneyByCurrency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.security.AccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@Import(BrokerConnectionControllerIntegrationTest.TestBrokerAdapterConfiguration.class)
class BrokerConnectionControllerIntegrationTest extends PostgresIntegrationTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String CANARY_ID = "controller-canary-client-id";
    private static final String CANARY_SECRET = "controller-canary-client-secret";
    private static final OffsetDateTime SNAPSHOT_TIME =
            OffsetDateTime.of(2026, 7, 28, 0, 0, 0, 0, ZoneOffset.UTC);

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingBrokerAdapter brokerAdapter;

    @Autowired
    private AccessTokenService accessTokens;

    @BeforeEach
    void cleanConnections() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbcTemplate.execute("TRUNCATE broker_connections, users CASCADE");
        brokerAdapter.reset();
    }

    private String authorization(String userId) {
        return "Bearer " + accessTokens.issue(
                UUID.fromString(userId), UUID.randomUUID(), Instant.now()).value();
    }

    @Test
    void unauthenticatedRequestsAreRejectedAtSecurityBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", "Bearer malformed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void basicAndFormLoginAreUnavailable() throws Exception {
        mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(httpBasic(USER_ID, "password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", "http://localhost:3000/login"));
    }

    @Test
    void nonUuidPrincipalGetsStableForbiddenCodeWithoutUsingRequestBodyAsUserSource() throws Exception {
        var body = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user("not-a-uuid"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(CANARY_ID, CANARY_SECRET);
        assertThat(userRows()).isZero();
    }

    @Test
    void createReplaceVerifyAndDeleteUsePrincipalUuidAndNeverReturnCredentialHints() throws Exception {
        var created = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerType").value("TOSS_INVEST"))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.credentialRevision").value(1))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain(CANARY_ID, CANARY_SECRET, "secret", "client");
        var connectionId = idFrom(created);

        var replaced = mockMvc.perform(put("/api/v1/broker-connections/{id}/credentials", connectionId)
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("replacement-client", "replacement-secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialRevision").value(2))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(replaced).doesNotContain("replacement-client", "replacement-secret", "secret", "client");

        mockMvc.perform(post("/api/v1/broker-connections/{id}/verify", connectionId)
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").doesNotExist());
        assertThat(brokerAdapter.connectionRefs()).containsExactly(new BrokerConnectionRef(UUID.fromString(connectionId)));

        mockMvc.perform(delete("/api/v1/broker-connections/{id}", connectionId)
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(ownerFor(connectionId)).isEqualTo(UUID.fromString(USER_ID));
    }

    @Test
    void crossUserReplaceVerifyAndDeleteMatchMissing404() throws Exception {
        var connectionId = idFrom(mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("owner-client", "owner-secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(put("/api/v1/broker-connections/{id}/credentials", connectionId)
                        .header("Authorization", authorization(OTHER_USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/broker-connections/{id}/verify", connectionId)
                        .header("Authorization", authorization(OTHER_USER_ID))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/broker-connections/{id}", connectionId)
                        .header("Authorization", authorization(OTHER_USER_ID))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
    }

    @Test
    void publicErrorsMapStableCodesWithoutSecrets() throws Exception {
        var duplicate = post("/api/v1/broker-connections/toss")
                .header("Authorization", authorization(USER_ID))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(CANARY_ID, CANARY_SECRET));
        mockMvc.perform(duplicate).andExpect(status().isOk());
        assertSecretFree(mockMvc.perform(duplicate)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_ALREADY_EXISTS"))
                .andReturn().getResponse().getContentAsString());

        brokerAdapter.respondWithBrokerException(new BrokerException(
                BrokerErrorCategory.RATE_LIMITED,
                429,
                "rate-limited",
                "request-1",
                Duration.ofSeconds(1),
                true,
                "broker message " + CANARY_SECRET));
        var connectionId = jdbcTemplate.queryForObject("SELECT id FROM broker_connections LIMIT 1", UUID.class);
        assertSecretFree(mockMvc.perform(post("/api/v1/broker-connections/{id}/verify", connectionId)
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("BROKER_RATE_LIMITED"))
                .andReturn().getResponse().getContentAsString());

        var missingSecretBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(UUID.randomUUID().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("", CANARY_SECRET)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertSecretFree(missingSecretBody);
    }

    @Test
    void requestValidationAndMalformedJsonReturnSecretFreeValidationFailure() throws Exception {
        var nullBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(UUID.randomUUID().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":null,\"clientSecret\":\"" + CANARY_SECRET + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(nullBody).doesNotContain(CANARY_SECRET, "clientSecret");

        var oversizedSecret = "x".repeat(4097);
        var oversizedBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(UUID.randomUUID().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, oversizedSecret)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(oversizedSecret.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(4096);
        assertThat(oversizedBody).doesNotContain(CANARY_ID, oversizedSecret, "clientSecret");

        var malformedBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(UUID.randomUUID().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"" + CANARY_ID + "\",\"clientSecret\":"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(malformedBody).doesNotContain(CANARY_ID, "clientSecret");
    }

    @Test
    void unexpectedIllegalStateIsNotMappedAsCredentialUnavailable() {
        assertThat(exceptionHandlerTypes()).doesNotContain(IllegalStateException.class);
    }

    @Test
    void readsLatestSuccessfulPortfolioWithSeparatedBuyingPowerAndExplicitUnknowns() throws Exception {
        var connectionId = insertActiveConnection(UUID.fromString(USER_ID));
        var previousRunId = insertSuccessfulPortfolio(connectionId, UUID.fromString(USER_ID), true);

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio", connectionId)
                        .with(user(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncRunId").value(org.hamcrest.Matchers.not(previousRunId.toString())))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.staleReason").doesNotExist())
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.missingSections").isEmpty())
                .andExpect(jsonPath("$.account.displayAccountNumber").value("****5678"))
                .andExpect(jsonPath("$.account.cashBalanceStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.positions").isEmpty())
                .andExpect(jsonPath("$.buyingPower.KRW.cashBuyingPower").value(1000))
                .andExpect(jsonPath("$.buyingPower.USD.cashBuyingPower").value(1000))
                .andExpect(jsonPath("$.unknownFields[0]").value("account.cashBalance"));
        assertThat(brokerAdapter.connectionRefs()).containsExactly(new BrokerConnectionRef(connectionId));
    }

    @Test
    void fallsBackToPreviousSuccessWhenLatestRunFailed() throws Exception {
        var userId = UUID.fromString(USER_ID);
        var connectionId = insertActiveConnection(userId);
        var successfulRunId = insertSuccessfulPortfolio(connectionId, userId, true);
        insertRun(
                connectionId,
                userId,
                UUID.randomUUID(),
                "FAILED",
                "BROKER_TEMPORARY",
                SNAPSHOT_TIME.plusMinutes(1),
                SNAPSHOT_TIME.plusMinutes(2));
        brokerAdapter.respondWithBrokerException(new BrokerException(
                BrokerErrorCategory.TEMPORARY,
                503,
                "temporary failure",
                "portfolio-fallback",
                Duration.ZERO,
                true,
                "temporary broker failure"));

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio", connectionId)
                        .with(user(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncRunId").value(successfulRunId.toString()))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.staleReason").value("LATEST_SYNC_FAILED"))
                .andExpect(jsonPath("$.partial").value(false));
    }

    @Test
    void marksMissingUsdBuyingPowerAsPartialWithoutInventingAValue() throws Exception {
        var userId = UUID.fromString(USER_ID);
        var connectionId = insertActiveConnection(userId);
        insertSuccessfulPortfolio(connectionId, userId, false);
        brokerAdapter.respondWithBrokerException(new BrokerException(
                BrokerErrorCategory.TEMPORARY,
                503,
                "temporary failure",
                "portfolio-partial",
                Duration.ZERO,
                true,
                "temporary broker failure"));

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio", connectionId)
                        .with(user(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.staleReason").value("LATEST_SYNC_FAILED"))
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.missingSections[0]").value("BUYING_POWER_USD"))
                .andExpect(jsonPath("$.buyingPower.KRW").exists())
                .andExpect(jsonPath("$.buyingPower.USD").doesNotExist());
    }

    @Test
    void crossOwnerAndMissingSnapshotReturnIndistinguishableOwnershipSafeErrors() throws Exception {
        var userId = UUID.fromString(USER_ID);
        var connectionId = insertActiveConnection(userId);
        insertSuccessfulPortfolio(connectionId, userId, true);

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio", connectionId)
                        .with(user(OTHER_USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));

        var emptyConnectionId = insertActiveConnection(UUID.fromString(OTHER_USER_ID));
        brokerAdapter.respondWithBrokerException(new BrokerException(
                BrokerErrorCategory.TEMPORARY,
                503,
                "temporary failure",
                "portfolio-empty",
                Duration.ZERO,
                true,
                "temporary broker failure"));
        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio", emptyConnectionId)
                        .with(user(OTHER_USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTFOLIO_SNAPSHOT_NOT_FOUND"));
    }

    @Test
    void manualPortfolioSyncIsCsrfProtectedAndOwned() throws Exception {
        var connectionId = insertActiveConnection(UUID.fromString(USER_ID));

        mockMvc.perform(post(
                        "/api/v1/broker-connections/{id}/portfolio-syncs",
                        connectionId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(
                        "/api/v1/broker-connections/{id}/portfolio-syncs",
                        connectionId)
                        .header("Authorization", authorization(OTHER_USER_ID))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));

        mockMvc.perform(post(
                        "/api/v1/broker-connections/{id}/portfolio-syncs",
                        connectionId)
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM account_snapshots",
                Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM account_capacity_snapshots",
                Integer.class)).isEqualTo(2);

        brokerAdapter.respondWithNoAccounts();
        mockMvc.perform(post(
                        "/api/v1/broker-connections/{id}/portfolio-syncs",
                        connectionId)
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_ACCOUNT_COUNT_UNSUPPORTED"));
    }

    private void assertSecretFree(String body) {
        assertThat(body).doesNotContain(CANARY_ID, CANARY_SECRET, "broker message");
    }

    private int userRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
    }

    private UUID ownerFor(String connectionId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM broker_connections WHERE id = ?",
                UUID.class,
                UUID.fromString(connectionId));
    }

    private UUID insertActiveConnection(UUID userId) {
        var connectionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbcTemplate.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], SNAPSHOT_TIME, SNAPSHOT_TIME);
        return connectionId;
    }

    private UUID insertSuccessfulPortfolio(UUID connectionId, UUID userId, boolean includeUsd) {
        var runId = UUID.randomUUID();
        insertRun(
                connectionId,
                userId,
                runId,
                "SUCCEEDED",
                null,
                SNAPSHOT_TIME,
                SNAPSHOT_TIME.plusSeconds(1));
        jdbcTemplate.update("""
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
        jdbcTemplate.update("""
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
        if (includeUsd) {
            insertCapacity(runId, userId, connectionId, "USD", "1000");
        }
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
        jdbcTemplate.update("""
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
        jdbcTemplate.update("""
                INSERT INTO account_capacity_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, currency,
                    cash_buying_power, observed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS numeric), ?, ?)
                """, UUID.randomUUID(), runId, userId, connectionId, currency,
                amount, SNAPSHOT_TIME, SNAPSHOT_TIME);
    }

    private static String credentialsJson(String clientId, String clientSecret) {
        return """
                {"clientId":"%s","clientSecret":"%s"}
                """.formatted(clientId, clientSecret);
    }

    private static String idFrom(String json) {
        var marker = "\"id\":\"";
        var start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("response id missing: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    @TestConfiguration
    static class TestBrokerAdapterConfiguration {

        @Bean
        RecordingBrokerAdapter brokerAdapter() {
            return new RecordingBrokerAdapter();
        }
    }

    static final class RecordingBrokerAdapter implements BrokerAdapter {
        private final java.util.ArrayList<BrokerConnectionRef> connectionRefs = new java.util.ArrayList<>();
        private BrokerException brokerException;
        private boolean noAccounts;

        List<BrokerConnectionRef> connectionRefs() {
            return List.copyOf(connectionRefs);
        }

        void respondWithBrokerException(BrokerException brokerException) {
            this.brokerException = brokerException;
        }

        void respondWithNoAccounts() {
            noAccounts = true;
        }

        void reset() {
            connectionRefs.clear();
            brokerException = null;
            noAccounts = false;
        }

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            connectionRefs.add(connection);
            if (brokerException != null) {
                throw brokerException;
            }
            if (noAccounts) {
                return new BrokerResponse<>(List.of(), metadata());
            }
            var account = account(connection);
            return new BrokerResponse<>(
                    List.of(new BrokerAccountView(account, "Primary")),
                    metadata());
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            return new BrokerResponse<>(new AccountSnapshot(
                    account,
                    money("100"),
                    money("120"),
                    money("119"),
                    money("20"),
                    money("19"),
                    new BigDecimal("0.20"),
                    new BigDecimal("0.19"),
                    money("1"),
                    new BigDecimal("0.01"),
                    CashBalanceStatus.UNKNOWN,
                    Instant.now()), metadata());
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            return new BrokerResponse<>(List.of(), metadata());
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(BrokerAccountRef account, Currency currency) {
            return new BrokerResponse<>(
                    new AccountCapacitySnapshot(account, currency, new BigDecimal("1000"), Instant.now()),
                    metadata());
        }

        @Override
        public BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol) {
            return new BrokerResponse<>(
                    SellableQuantitySnapshot.unknown(account, symbol, Instant.now()),
                    metadata());
        }

        private static BrokerAccountRef account(BrokerConnectionRef connection) {
            return new BrokerAccountRef(
                    connection.brokerConnectionId(),
                    "account-1",
                    "GENERAL",
                    "****5678");
        }

        private static MoneyByCurrency money(String amount) {
            return new MoneyByCurrency(Map.of(Currency.USD, new BigDecimal(amount)));
        }

        private static BrokerCallMetadata metadata() {
            return new BrokerCallMetadata(
                    "controller-request",
                    Instant.now(),
                    Optional.empty());
        }
    }

    private static List<Class<? extends Throwable>> exceptionHandlerTypes() {
        return java.util.Arrays.stream(BrokerConnectionErrorHandler.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(annotation -> java.util.Arrays.stream(annotation.value()))
                .toList();
    }
}
