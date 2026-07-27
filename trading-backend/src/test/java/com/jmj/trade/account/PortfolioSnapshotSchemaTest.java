package com.jmj.trade.account;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioSnapshotSchemaTest extends PostgresIntegrationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 28, 0, 0, 0, 0, ZoneOffset.UTC);

    private Flyway flyway;

    @BeforeEach
    void prepareCleanDatabase() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
    }

    @Test
    void migratesThroughV5AndCreatesSnapshotTables() {
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThatCode(() -> execute("SELECT 1 FROM account_sync_runs WHERE false"))
                .doesNotThrowAnyException();
        assertThatCode(() -> execute("SELECT 1 FROM account_snapshots WHERE false"))
                .doesNotThrowAnyException();
        assertThatCode(() -> execute("SELECT 1 FROM position_snapshots WHERE false"))
                .doesNotThrowAnyException();
        assertThatCode(() -> execute("SELECT 1 FROM account_capacity_snapshots WHERE false"))
                .doesNotThrowAnyException();
    }

    @Test
    void permitsOnlyOneRunningSyncPerOwnedConnection() throws SQLException {
        flyway.migrate();
        var owner = insertOwnerAndConnection();

        insertRun(owner.userId(), owner.connectionId(), UUID.randomUUID(), "RUNNING", null);

        assertThatThrownBy(() ->
                insertRun(owner.userId(), owner.connectionId(), UUID.randomUUID(), "RUNNING", null))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void accountSnapshotsAreAppendOnly() throws SQLException {
        flyway.migrate();
        var owner = insertOwnerAndConnection();
        var runId = UUID.randomUUID();
        insertRun(owner.userId(), owner.connectionId(), runId, "SUCCEEDED", NOW);
        var snapshotId = UUID.randomUUID();
        execute("""
                INSERT INTO account_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, account_type,
                    display_account_number, total_purchase_amounts, market_value_amounts,
                    market_value_after_cost_amounts, profit_loss_amounts,
                    profit_loss_after_cost_amounts, daily_profit_loss_amounts,
                    profit_loss_rate, profit_loss_rate_after_cost, daily_profit_loss_rate,
                    cash_balance_status, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'GENERAL', '****1234',
                    '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                    0, 0, 0, 'UNKNOWN', ?, ?
                )
                """, snapshotId, runId, owner.userId(), owner.connectionId(), NOW, NOW);

        assertThatThrownBy(() ->
                execute("UPDATE account_snapshots SET profit_loss_rate = 1 WHERE id = ?", snapshotId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() ->
                execute("DELETE FROM account_snapshots WHERE id = ?", snapshotId))
                .isInstanceOf(SQLException.class);
    }

    private Owner insertOwnerAndConnection() throws SQLException {
        var userId = UUID.randomUUID();
        var connectionId = UUID.randomUUID();
        execute("INSERT INTO users (id) VALUES (?)", userId);
        execute("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], NOW, NOW);
        return new Owner(userId, connectionId);
    }

    private void insertRun(
            UUID userId,
            UUID connectionId,
            UUID runId,
            String status,
            OffsetDateTime completedAt
    ) throws SQLException {
        execute("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, ?, ?, ?)
                """, runId, userId, connectionId, status, NOW, completedAt);
    }

    private void execute(String sql, Object... args) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        }
    }

    private record Owner(UUID userId, UUID connectionId) {
    }
}
