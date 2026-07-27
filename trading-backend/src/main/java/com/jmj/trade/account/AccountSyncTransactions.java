package com.jmj.trade.account;

import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.MoneyByCurrency;
import com.jmj.trade.broker.Position;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public final class AccountSyncTransactions {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Duration staleAfter;

    public AccountSyncTransactions(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            Duration staleAfter
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
    }

    SyncTarget start(UUID userId, UUID connectionId) {
        requireId(userId, "userId");
        requireId(connectionId, "connectionId");
        return transaction.execute(status -> {
            var revisions = jdbc.queryForList("""
                    SELECT credential_revision
                      FROM broker_connections
                     WHERE id = ?
                       AND user_id = ?
                       AND status = 'ACTIVE'
                       AND deleted_at IS NULL
                    """, Long.class, connectionId, userId);
            if (revisions.size() != 1) {
                throw new AccountSyncException(AccountSyncException.Code.NOT_FOUND);
            }

            jdbc.update("""
                    UPDATE account_sync_runs
                       SET status = 'FAILED',
                           error_code = 'FAILED_STALE',
                           completed_at = CURRENT_TIMESTAMP
                     WHERE user_id = ?
                       AND broker_connection_id = ?
                       AND status = 'RUNNING'
                       AND started_at < CURRENT_TIMESTAMP
                           - CAST(? AS bigint) * INTERVAL '1 millisecond'
                    """, userId, connectionId, staleAfter.toMillis());

            var target = new SyncTarget(UUID.randomUUID(), userId, connectionId, revisions.getFirst());
            var inserted = jdbc.update("""
                    INSERT INTO account_sync_runs (
                        id, user_id, broker_connection_id, credential_revision,
                        status, started_at
                    ) VALUES (?, ?, ?, ?, 'RUNNING', ?)
                    ON CONFLICT (user_id, broker_connection_id)
                    WHERE status = 'RUNNING'
                    DO NOTHING
                    """, target.runId(), userId, connectionId, target.credentialRevision(), now());
            if (inserted != 1) {
                throw new AccountSyncException(AccountSyncException.Code.SYNC_ALREADY_RUNNING);
            }
            return target;
        });
    }

    AccountSyncResult complete(
            SyncTarget target,
            BrokerAccountRef account,
            AccountSnapshot snapshot,
            List<Position> positions,
            List<AccountCapacitySnapshot> capacities
    ) {
        return transaction.execute(status -> {
            var completedAt = now();
            var updated = jdbc.update("""
                    UPDATE account_sync_runs run
                       SET status = 'SUCCEEDED',
                           completed_at = ?
                     WHERE run.id = ?
                       AND run.status = 'RUNNING'
                       AND EXISTS (
                           SELECT 1
                             FROM broker_connections connection
                            WHERE connection.id = run.broker_connection_id
                              AND connection.user_id = run.user_id
                              AND connection.credential_revision = run.credential_revision
                              AND connection.status = 'ACTIVE'
                              AND connection.deleted_at IS NULL
                       )
                    """, completedAt, target.runId());
            if (updated != 1) {
                throw new AccountSyncException(AccountSyncException.Code.CREDENTIAL_REVISION_CHANGED);
            }

            insertAccount(target, account, snapshot, completedAt);
            positions.forEach(position -> insertPosition(target, position, completedAt));
            capacities.forEach(capacity -> insertCapacity(target, capacity, completedAt));
            return new AccountSyncResult(target.runId(), completedAt.toInstant());
        });
    }

    void fail(SyncTarget target, String errorCode) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE account_sync_runs
                   SET status = 'FAILED',
                       error_code = ?,
                       completed_at = ?
                 WHERE id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                   AND status = 'RUNNING'
                """, errorCode, now(), target.runId(), target.userId(), target.connectionId()));
    }

    Optional<AccountSyncResult> latestSuccessful(UUID userId, UUID connectionId) {
        requireId(userId, "userId");
        requireId(connectionId, "connectionId");
        return jdbc.query("""
                SELECT run.id, run.completed_at
                  FROM account_sync_runs run
                  JOIN broker_connections connection
                    ON connection.id = run.broker_connection_id
                   AND connection.user_id = run.user_id
                 WHERE run.user_id = ?
                   AND run.broker_connection_id = ?
                   AND run.status = 'SUCCEEDED'
                   AND run.credential_revision = connection.credential_revision
                   AND connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                 ORDER BY run.completed_at DESC, run.id DESC
                 LIMIT 1
                """, (resultSet, rowNum) -> new AccountSyncResult(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("completed_at", OffsetDateTime.class).toInstant()
        ), userId, connectionId).stream().findFirst();
    }

    private void insertAccount(
            SyncTarget target,
            BrokerAccountRef account,
            AccountSnapshot snapshot,
            OffsetDateTime createdAt
    ) {
        jdbc.update("""
                INSERT INTO account_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, account_type,
                    display_account_number, total_purchase_amounts, market_value_amounts,
                    market_value_after_cost_amounts, profit_loss_amounts,
                    profit_loss_after_cost_amounts, daily_profit_loss_amounts,
                    profit_loss_rate, profit_loss_rate_after_cost, daily_profit_loss_rate,
                    cash_balance_status, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                    ?, ?, ?, ?, ?, ?
                )
                """,
                UUID.randomUUID(), target.runId(), target.userId(), target.connectionId(),
                account.accountType(), account.displayAccountNumber(),
                json(snapshot.totalPurchaseAmount()),
                json(snapshot.marketValueAmount()),
                json(snapshot.marketValueAmountAfterCost()),
                json(snapshot.profitLossAmount()),
                json(snapshot.profitLossAmountAfterCost()),
                json(snapshot.dailyProfitLossAmount()),
                snapshot.profitLossRate(),
                snapshot.profitLossRateAfterCost(),
                snapshot.dailyProfitLossRate(),
                snapshot.cashBalanceStatus().name(),
                offset(snapshot.observedAt()),
                createdAt);
    }

    private void insertPosition(SyncTarget target, Position position, OffsetDateTime createdAt) {
        jdbc.update("""
                INSERT INTO position_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, symbol, name,
                    market_country, quantity, currency, average_price, last_price,
                    purchase_amount, market_value_amount, market_value_after_cost,
                    profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                    profit_loss_rate_after_cost, daily_profit_loss_amount,
                    daily_profit_loss_rate, commission, tax, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                UUID.randomUUID(), target.runId(), target.userId(), target.connectionId(),
                position.symbol(), position.name(), position.marketCountry(), position.quantity(),
                position.currency().name(), position.averagePrice(), position.lastPrice(),
                position.purchaseAmount(), position.marketValueAmount(),
                position.marketValueAmountAfterCost(), position.profitLossAmount(),
                position.profitLossAmountAfterCost(), position.profitLossRate(),
                position.profitLossRateAfterCost(), position.dailyProfitLossAmount(),
                position.dailyProfitLossRate(), position.commission(), position.tax(),
                offset(position.observedAt()), createdAt);
    }

    private void insertCapacity(
            SyncTarget target,
            AccountCapacitySnapshot capacity,
            OffsetDateTime createdAt
    ) {
        jdbc.update("""
                INSERT INTO account_capacity_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, currency,
                    cash_buying_power, observed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), target.runId(), target.userId(), target.connectionId(),
                capacity.currency().name(), capacity.cashBuyingPower(),
                offset(capacity.observedAt()), createdAt);
    }

    private static String json(MoneyByCurrency money) {
        return money.amounts().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> "\"" + entry.getKey().name() + "\":" + entry.getValue().toPlainString())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireId(UUID id, String fieldName) {
        if (id == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    record SyncTarget(UUID runId, UUID userId, UUID connectionId, long credentialRevision) {
    }
}
