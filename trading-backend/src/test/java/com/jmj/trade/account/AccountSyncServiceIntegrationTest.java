package com.jmj.trade.account;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.broker.AccountCapacitySnapshot;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountSyncServiceIntegrationTest extends PostgresIntegrationTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-07-28T00:00:00Z");
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private RecordingBrokerAdapter broker;
    private AccountSyncTransactions transactions;
    private AccountSyncService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        broker = new RecordingBrokerAdapter();
        transactions = new AccountSyncTransactions(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Duration.ofMinutes(15));
        service = new AccountSyncService(transactions, broker);
    }

    @Test
    void persistsOneAtomicSnapshotAndQueriesBothBuyingPowerCurrenciesOutsideTransactions() {
        var owner = insertOwnerAndConnection();
        broker.account = account(owner.connectionId());

        var result = service.sync(owner.userId(), owner.connectionId());

        assertThat(broker.calls).containsExactly(
                "accounts", "account", "positions", "capacity:KRW", "capacity:USD");
        assertThat(broker.transactionStates).containsOnly(false);
        assertThat(count("account_snapshots")).isEqualTo(1);
        assertThat(count("position_snapshots")).isEqualTo(1);
        assertThat(count("account_capacity_snapshots")).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT currency FROM account_capacity_snapshots ORDER BY currency",
                String.class)).containsExactly("KRW", "USD");
        assertThat(runStatus(result.runId())).isEqualTo("SUCCEEDED");
        assertThat(service.latestSuccessful(owner.userId(), owner.connectionId()))
                .contains(result);
    }

    @Test
    void partialBrokerFailureLeavesNoSnapshotsAndMarksRunFailed() {
        var owner = insertOwnerAndConnection();
        broker.account = account(owner.connectionId());
        broker.failOn = "capacity:USD";

        assertThatThrownBy(() -> service.sync(owner.userId(), owner.connectionId()))
                .isInstanceOf(BrokerException.class);

        assertThat(count("account_snapshots")).isZero();
        assertThat(count("position_snapshots")).isZero();
        assertThat(count("account_capacity_snapshots")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM account_sync_runs",
                String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM account_sync_runs",
                String.class)).isEqualTo("BROKER_TEMPORARY");
    }

    @Test
    void existingRunningSyncBlocksBeforeBrokerCalls() {
        var owner = insertOwnerAndConnection();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision, status, started_at
                ) VALUES (?, ?, ?, 1, 'RUNNING', ?)
                """, UUID.randomUUID(), owner.userId(), owner.connectionId(),
                OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> service.sync(owner.userId(), owner.connectionId()))
                .isInstanceOfSatisfying(AccountSyncException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AccountSyncException.Code.SYNC_ALREADY_RUNNING));
        assertThat(broker.calls).isEmpty();
    }

    @Test
    void staleRunningSyncIsFailedBeforeNewSyncWithoutDeletingSuccessfulSnapshots() {
        var owner = insertOwnerAndConnection();
        broker.account = account(owner.connectionId());
        var first = service.sync(owner.userId(), owner.connectionId());
        var staleRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision, status, started_at
                ) VALUES (?, ?, ?, 1, 'RUNNING', ?)
                """, staleRunId, owner.userId(), owner.connectionId(),
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(16));

        var second = service.sync(owner.userId(), owner.connectionId());

        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM account_sync_runs WHERE id = ?",
                staleRunId)).containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
        assertThat(count("account_snapshots")).isEqualTo(2);
        assertThat(count("position_snapshots")).isEqualTo(2);
        assertThat(count("account_capacity_snapshots")).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM account_snapshots WHERE sync_run_id = ?",
                Long.class, first.runId())).isEqualTo(1);
        assertThat(service.latestSuccessful(owner.userId(), owner.connectionId())).contains(second);
    }

    @Test
    void concurrentStaleRecoveryStartsExactlyOneNewRun() throws Exception {
        var owner = insertOwnerAndConnection();
        var staleRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision, status, started_at
                ) VALUES (?, ?, ?, 1, 'RUNNING', ?)
                """, staleRunId, owner.userId(), owner.connectionId(),
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(16));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> startSync(ready, start, owner));
            var second = executor.submit(() -> startSync(ready, start, owner));
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("STARTED", "SYNC_ALREADY_RUNNING");
        }

        assertThat(jdbc.queryForMap(
                "SELECT status, error_code FROM account_sync_runs WHERE id = ?",
                staleRunId)).containsEntry("status", "FAILED")
                .containsEntry("error_code", "FAILED_STALE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM account_sync_runs
                 WHERE user_id = ?
                   AND broker_connection_id = ?
                   AND status = 'RUNNING'
                """, Long.class, owner.userId(), owner.connectionId())).isEqualTo(1);
    }

    @Test
    void unexpectedRunIntegrityFailureIsNotReportedAsConcurrentSync() {
        var owner = insertOwnerAndConnection();
        jdbc.execute("""
                ALTER TABLE account_sync_runs
                ADD CONSTRAINT ck_test_reject_running
                CHECK (status <> 'RUNNING') NOT VALID
                """);

        assertThatThrownBy(() -> transactions.start(owner.userId(), owner.connectionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void credentialRevisionChangeDiscardsFetchedDataAndHidesOldLatestSnapshot() {
        var owner = insertOwnerAndConnection();
        broker.account = account(owner.connectionId());
        var first = service.sync(owner.userId(), owner.connectionId());
        assertThat(service.latestSuccessful(owner.userId(), owner.connectionId())).contains(first);

        broker.afterCall = call -> {
            if (call.equals("positions")) {
                jdbc.update("""
                        UPDATE broker_connections
                           SET credential_revision = credential_revision + 1,
                               updated_at = ?
                         WHERE id = ?
                        """, NOW.plusSeconds(1), owner.connectionId());
            }
        };

        assertThatThrownBy(() -> service.sync(owner.userId(), owner.connectionId()))
                .isInstanceOfSatisfying(AccountSyncException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AccountSyncException.Code.CREDENTIAL_REVISION_CHANGED));

        assertThat(count("account_snapshots")).isEqualTo(1);
        assertThat(count("position_snapshots")).isEqualTo(1);
        assertThat(count("account_capacity_snapshots")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM account_sync_runs ORDER BY started_at DESC, id DESC LIMIT 1",
                String.class)).isEqualTo("FAILED");
        assertThat(service.latestSuccessful(owner.userId(), owner.connectionId())).isEmpty();
    }

    @Test
    void rejectsZeroOrMultipleAccountsWithoutSnapshots() {
        var owner = insertOwnerAndConnection();
        broker.accounts = List.of();

        assertThatThrownBy(() -> service.sync(owner.userId(), owner.connectionId()))
                .isInstanceOfSatisfying(AccountSyncException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AccountSyncException.Code.ACCOUNT_COUNT_UNSUPPORTED));
        assertThat(count("account_snapshots")).isZero();
    }

    private Owner insertOwnerAndConnection() {
        var userId = UUID.randomUUID();
        var connectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], NOW, NOW);
        return new Owner(userId, connectionId);
    }

    private BrokerAccountRef account(UUID connectionId) {
        return new BrokerAccountRef(connectionId, "12345678", "GENERAL", "****5678");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject(
                "SELECT status FROM account_sync_runs WHERE id = ?",
                String.class,
                runId);
    }

    private String startSync(CountDownLatch ready, CountDownLatch start, Owner owner)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            transactions.start(owner.userId(), owner.connectionId());
            return "STARTED";
        } catch (AccountSyncException exception) {
            return exception.code().name();
        }
    }

    private static BrokerResponse<AccountSnapshot> snapshot(BrokerAccountRef account) {
        return new BrokerResponse<>(
                new AccountSnapshot(
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
                        OBSERVED_AT),
                metadata());
    }

    private static BrokerResponse<List<Position>> positions(BrokerAccountRef account) {
        return new BrokerResponse<>(List.of(new Position(
                account, "NVDA", "NVIDIA", "US", BigDecimal.ONE, Currency.USD,
                new BigDecimal("100"), new BigDecimal("120"), new BigDecimal("100"),
                new BigDecimal("120"), new BigDecimal("119"), new BigDecimal("20"),
                new BigDecimal("19"), new BigDecimal("0.20"), new BigDecimal("0.19"),
                BigDecimal.ONE, new BigDecimal("0.01"), BigDecimal.ONE, null, OBSERVED_AT
        )), metadata());
    }

    private static MoneyByCurrency money(String amount) {
        return new MoneyByCurrency(Map.of(Currency.USD, new BigDecimal(amount)));
    }

    private static BrokerCallMetadata metadata() {
        return new BrokerCallMetadata("request", OBSERVED_AT, Optional.empty());
    }

    private record Owner(UUID userId, UUID connectionId) {
    }

    private final class RecordingBrokerAdapter implements BrokerAdapter {

        private final List<String> calls = new ArrayList<>();
        private final List<Boolean> transactionStates = new ArrayList<>();
        private List<BrokerAccountView> accounts;
        private BrokerAccountRef account;
        private String failOn;
        private java.util.function.Consumer<String> afterCall = ignored -> {
        };

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            record("accounts");
            var value = accounts == null
                    ? List.of(new BrokerAccountView(account, "Primary"))
                    : accounts;
            return new BrokerResponse<>(value, metadata());
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            record("account");
            return snapshot(account);
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            record("positions");
            return positions(account);
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(
                BrokerAccountRef account,
                Currency currency
        ) {
            record("capacity:" + currency);
            return new BrokerResponse<>(
                    new AccountCapacitySnapshot(account, currency, new BigDecimal("1000"), OBSERVED_AT),
                    metadata());
        }

        private void record(String call) {
            calls.add(call);
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (call.equals(failOn)) {
                throw new BrokerException(
                        BrokerErrorCategory.TEMPORARY,
                        null, null, null, null, true,
                        "temporary broker failure");
            }
            afterCall.accept(call);
        }
    }
}
