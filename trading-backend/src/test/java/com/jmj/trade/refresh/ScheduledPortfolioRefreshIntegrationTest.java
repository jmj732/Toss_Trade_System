package com.jmj.trade.refresh;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.CashBalanceStatus;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.MoneyByCurrency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.observability.CorrelationIdFilter;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "portfolio.refresh.enabled=true",
                "portfolio.refresh.interval=PT15M",
                "portfolio.refresh.initial-delay=PT30M",
                "portfolio.refresh.lock-ttl=PT10M",
                "portfolio.refresh.connection-stale-after=PT24H",
                "portfolio.refresh.max-consecutive-failures=2",
                "portfolio.refresh.batch-size=10",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import(ScheduledPortfolioRefreshIntegrationTest.RefreshBrokerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduledPortfolioRefreshIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-28T00:00:00Z");
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
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
    private ScheduledPortfolioRefreshService refreshService;

    @Autowired
    private ScheduledPortfolioRefreshScheduler scheduler;

    @Autowired
    private RefreshBrokerAdapter broker;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                TRUNCATE scheduled_refresh_leases,
                         analysis_results,
                         analysis_runs,
                         account_capacity_snapshots,
                         position_snapshots,
                         account_snapshots,
                         account_sync_runs,
                         broker_connections,
                         users CASCADE
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
    void schedulerBeanIsRegisteredWhileRefreshIsEnabled() {
        assertThat(scheduler).isNotNull();
    }

    @Test
    void refreshesDueConnectionWithSyncThenAnalysisAndCreatesNoOrders() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(20));

        var result = refreshService.refresh();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(broker.calls).containsExactly(
                "accounts", "account", "positions", "capacity:KRW", "capacity:USD");
        assertThat(countWhere("account_sync_runs", "status = 'SUCCEEDED'")).isEqualTo(2);
        assertThat(countWhere("analysis_runs", "status = 'SUCCEEDED'")).isEqualTo(1);
        assertThat(count("analysis_results")).isEqualTo(1);
        assertThat(count("order_intents")).isZero();
        assertThat(count("order_intent_outbox_events")).isZero();
        assertThat(count("pre_trade_risk_decisions")).isZero();
    }

    @Test
    void propagatesOneGeneratedCorrelationIdPerConnectionToAnalysisService() {
        var first = insertConnection(USER_ID, minutesAgo(1));
        var second = insertConnection(OTHER_USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, first, minutesAgo(20));
        insertSucceededSync(OTHER_USER_ID, second, minutesAgo(20));

        assertThat(refreshService.refresh().refreshed()).isEqualTo(2);

        var correlationIds = ANALYSIS.getAllServeEvents().stream()
                .map(event -> event.getRequest().getHeader(CorrelationIdFilter.HEADER))
                .toList();
        assertThat(correlationIds).hasSize(2).doesNotContainNull().doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(UUID_PATTERN.matcher(id).matches()).isTrue());
    }

    @Test
    void skipsConnectionSyncedInsideTheInterval() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(1));

        var result = refreshService.refresh();

        assertThat(result.refreshed()).isZero();
        assertThat(broker.calls).isEmpty();
        assertThat(count("account_sync_runs")).isEqualTo(1);
    }

    @Test
    void refreshesConnectionThatNeverSyncedSuccessfully() {
        insertConnection(USER_ID, minutesAgo(1));

        assertThat(refreshService.refresh().refreshed()).isEqualTo(1);
        assertThat(countWhere("account_sync_runs", "status = 'SUCCEEDED'")).isEqualTo(1);
    }

    @Test
    void skipsStaleAndNeverValidatedConnections() {
        var stale = insertConnection(USER_ID, minutesAgo(25 * 60));
        var neverValidated = insertConnection(OTHER_USER_ID, null);
        insertSucceededSync(USER_ID, stale, minutesAgo(20));
        insertSucceededSync(OTHER_USER_ID, neverValidated, minutesAgo(20));

        var result = refreshService.refresh();

        assertThat(result.refreshed()).isZero();
        assertThat(broker.calls).isEmpty();
    }

    @Test
    void skipsInactiveConnections() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(20));
        jdbc.update("UPDATE broker_connections SET status = 'INVALID' WHERE id = ?", connectionId);

        assertThat(refreshService.refresh().refreshed()).isZero();
        assertThat(broker.calls).isEmpty();
    }

    @Test
    void leavesManualRunningSyncUntouched() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(20));
        var manualRunId = UUID.randomUUID();
        insertSyncRun(USER_ID, connectionId, manualRunId, "RUNNING", null, minutesAgo(1), null);

        var result = refreshService.refresh();

        assertThat(result.refreshed()).isZero();
        assertThat(broker.calls).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM account_sync_runs WHERE id = ?", String.class, manualRunId))
                .isEqualTo("RUNNING");
        assertThat(count("account_sync_runs")).isEqualTo(2);
    }

    @Test
    void recoversConnectionWhoseRunningSyncWasAbandoned() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(60));
        var abandonedRunId = UUID.randomUUID();
        insertSyncRun(USER_ID, connectionId, abandonedRunId, "RUNNING", null, minutesAgo(30), null);

        var result = refreshService.refresh();

        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM account_sync_runs WHERE id = ?", abandonedRunId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
        assertThat(countWhere("account_sync_runs", "status = 'SUCCEEDED'")).isEqualTo(2);
    }

    @Test
    void leavesManualRunningAnalysisUntouched() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        var syncRunId = insertSucceededSync(USER_ID, connectionId, minutesAgo(20));
        jdbc.update("""
                INSERT INTO analysis_runs (
                    id, user_id, broker_connection_id, input_sync_run_id, status, started_at
                ) VALUES (?, ?, ?, ?, 'RUNNING', ?)
                """, UUID.randomUUID(), USER_ID, connectionId, syncRunId, minutesAgo(1));

        var result = refreshService.refresh();

        assertThat(result.refreshed()).isZero();
        assertThat(broker.calls).isEmpty();
        assertThat(countWhere("analysis_runs", "status = 'RUNNING'")).isEqualTo(1);
    }

    @Test
    void isolatesBrokerFailureAndKeepsRefreshingRemainingConnections() {
        var failing = insertConnection(USER_ID, minutesAgo(1));
        var healthy = insertConnection(OTHER_USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, failing, minutesAgo(20));
        insertSucceededSync(OTHER_USER_ID, healthy, minutesAgo(20));
        broker.failConnections.add(failing);

        var result = refreshService.refresh();

        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM account_sync_runs
                 WHERE broker_connection_id = ?
                 ORDER BY started_at DESC, id DESC LIMIT 1
                """, String.class, failing)).isEqualTo("FAILED");
        assertThat(countWhere("analysis_runs", "broker_connection_id = '" + healthy + "'"))
                .isEqualTo(1);
        assertThat(countWhere("analysis_runs", "broker_connection_id = '" + failing + "'"))
                .isZero();
    }

    @Test
    void isolatesAnalysisFailureWithoutDiscardingTheSuccessfulSync() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(20));
        ANALYSIS.resetAll();
        ANALYSIS.stubFor(post("/internal/v1/portfolio-analyses")
                .willReturn(aResponse().withStatus(503)));

        var result = refreshService.refresh();

        assertThat(result.refreshed()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(countWhere("account_sync_runs", "status = 'SUCCEEDED'")).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT status, error_code FROM analysis_runs"))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "ANALYSIS_UPSTREAM_UNAVAILABLE");
    }

    @Test
    void stopsRetryingAfterConsecutiveFailureLimit() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(60));
        insertSyncRun(USER_ID, connectionId, UUID.randomUUID(), "FAILED", "BROKER_TEMPORARY",
                minutesAgo(40), minutesAgo(40));
        insertSyncRun(USER_ID, connectionId, UUID.randomUUID(), "FAILED", "BROKER_TEMPORARY",
                minutesAgo(20), minutesAgo(20));

        var result = refreshService.refresh();

        assertThat(result.refreshed()).isZero();
        assertThat(broker.calls).isEmpty();
        assertThat(count("account_sync_runs")).isEqualTo(3);
    }

    @Test
    void retriesWhileBelowConsecutiveFailureLimit() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(60));
        insertSyncRun(USER_ID, connectionId, UUID.randomUUID(), "FAILED", "BROKER_TEMPORARY",
                minutesAgo(20), minutesAgo(20));

        assertThat(refreshService.refresh().refreshed()).isEqualTo(1);
    }

    @Test
    void countsFailuresOnlyAfterTheLastSuccessfulSync() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSyncRun(USER_ID, connectionId, UUID.randomUUID(), "FAILED", "BROKER_TEMPORARY",
                minutesAgo(80), minutesAgo(80));
        insertSyncRun(USER_ID, connectionId, UUID.randomUUID(), "FAILED", "BROKER_TEMPORARY",
                minutesAgo(70), minutesAgo(70));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(60));

        assertThat(refreshService.refresh().refreshed()).isEqualTo(1);
    }

    @Test
    void unexpiredLeaseHeldElsewhereBlocksTheSweep() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(20));
        insertLease(UUID.randomUUID(), minutesAgo(1), minutesFromNow(9));

        var result = refreshService.refresh();

        assertThat(result.lockAcquired()).isFalse();
        assertThat(result.refreshed()).isZero();
        assertThat(broker.calls).isEmpty();
        assertThat(count("account_sync_runs")).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsTakenOverAndReleasedAfterTheSweep() {
        var connectionId = insertConnection(USER_ID, minutesAgo(1));
        insertSucceededSync(USER_ID, connectionId, minutesAgo(20));
        insertLease(UUID.randomUUID(), minutesAgo(30), minutesAgo(20));

        var result = refreshService.refresh();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(count("scheduled_refresh_leases")).isZero();
    }

    @Test
    void leaseIsReleasedAfterASweepThatFoundNothingToRefresh() {
        assertThat(refreshService.refresh().lockAcquired()).isTrue();
        assertThat(count("scheduled_refresh_leases")).isZero();
    }

    @Test
    void limitsOneSweepToTheConfiguredBatchSize() {
        for (int index = 0; index < 12; index++) {
            var userId = UUID.randomUUID();
            var connectionId = insertConnection(userId, minutesAgo(1));
            insertSucceededSync(userId, connectionId, minutesAgo(20 + index));
        }

        assertThat(refreshService.refresh().refreshed()).isEqualTo(10);
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
                                  "quality":{
                                    "stale":false,
                                    "partial":false,
                                    "unknownFields":[]
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

    private UUID insertConnection(UUID userId, OffsetDateTime lastValidatedAt) {
        var connectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, last_validated_at,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], lastValidatedAt,
                CREATED_AT, CREATED_AT);
        return connectionId;
    }

    private UUID insertSucceededSync(UUID userId, UUID connectionId, OffsetDateTime completedAt) {
        var runId = UUID.randomUUID();
        insertSyncRun(userId, connectionId, runId, "SUCCEEDED", null, completedAt, completedAt);
        return runId;
    }

    private void insertSyncRun(
            UUID userId,
            UUID connectionId,
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

    private void insertLease(UUID owner, OffsetDateTime acquiredAt, OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO scheduled_refresh_leases (name, owner, acquired_at, expires_at)
                VALUES ('portfolio-refresh', ?, ?, ?)
                """, owner, acquiredAt, expiresAt);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long countWhere(String table, String predicate) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class);
    }

    private static OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }

    private static OffsetDateTime minutesFromNow(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(minutes);
    }

    private static BrokerCallMetadata metadata() {
        return new BrokerCallMetadata("request", OBSERVED_AT, Optional.empty());
    }

    private static MoneyByCurrency money(String amount) {
        return new MoneyByCurrency(Map.of(Currency.USD, new BigDecimal(amount)));
    }

    @TestConfiguration
    static class RefreshBrokerConfiguration {

        @Bean
        RefreshBrokerAdapter brokerAdapter() {
            return new RefreshBrokerAdapter();
        }
    }

    static final class RefreshBrokerAdapter implements BrokerAdapter {

        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final Set<UUID> failConnections = ConcurrentHashMap.newKeySet();

        void reset() {
            calls.clear();
            failConnections.clear();
        }

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            calls.add("accounts");
            failIfRequested(connection.brokerConnectionId());
            var account = account(connection.brokerConnectionId());
            return new BrokerResponse<>(List.of(new BrokerAccountView(account, "Primary")), metadata());
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            calls.add("account");
            failIfRequested(account.brokerConnectionId());
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
                    CashBalanceStatus.KNOWN,
                    OBSERVED_AT), metadata());
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            calls.add("positions");
            failIfRequested(account.brokerConnectionId());
            return new BrokerResponse<>(List.of(new Position(
                    account, "NVDA", "NVIDIA", "US", BigDecimal.ONE, Currency.USD,
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
            failIfRequested(account.brokerConnectionId());
            return new BrokerResponse<>(
                    new AccountCapacitySnapshot(account, currency, new BigDecimal("1000"), OBSERVED_AT),
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
            throw new UnsupportedOperationException();
        }

        private static BrokerAccountRef account(UUID connectionId) {
            return new BrokerAccountRef(connectionId, "12345678", "GENERAL", "****5678");
        }

        private void failIfRequested(UUID connectionId) {
            if (failConnections.contains(connectionId)) {
                throw new BrokerException(
                        BrokerErrorCategory.TEMPORARY,
                        null, null, null, null, true,
                        "temporary broker failure");
            }
        }
    }
}
