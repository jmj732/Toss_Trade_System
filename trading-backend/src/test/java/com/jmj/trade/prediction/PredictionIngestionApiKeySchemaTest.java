package com.jmj.trade.prediction;

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

class PredictionIngestionApiKeySchemaTest extends PostgresIntegrationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 30, 0, 0, 0, 0, ZoneOffset.UTC);

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
    void v24AddsImmutableExpiredLifecycleAndKeepsRejectionAuditAppendOnly() throws SQLException {
        flyway.migrate();
        var userId = UUID.randomUUID();
        execute("INSERT INTO users (id) VALUES (?)", userId);
        execute("""
                INSERT INTO prediction_model_versions (
                    id, user_id, model_version, contract_version, status, created_at
                ) VALUES (?, ?, 'model-v1', 'contract-v1', 'ACTIVE', ?)
                """, UUID.randomUUID(), userId, NOW);
        var firstId = UUID.randomUUID();
        execute("""
                INSERT INTO prediction_ingestion_api_keys (
                    id, user_id, model_version, contract_version, key_hash, key_prefix,
                    status, created_at, expires_at
                ) VALUES (?, ?, 'model-v1', 'contract-v1', ?, 'tpik_12345678', 'ACTIVE', ?, ?)
                """, firstId, userId, "a".repeat(64), NOW, NOW.plusHours(1));

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("31");
        assertThatThrownBy(() -> execute("""
                UPDATE prediction_ingestion_api_keys
                   SET expires_at = ?
                 WHERE id = ?
                """, NOW.plusDays(31), firstId)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                INSERT INTO prediction_ingestion_api_keys (
                    id, user_id, model_version, contract_version, key_hash, key_prefix,
                    status, created_at, expires_at
                ) VALUES (?, ?, 'model-v1', 'contract-v1', ?, 'tpik_badtime1', 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), userId, "b".repeat(64), NOW, NOW))
                .isInstanceOf(SQLException.class);
        execute("""
                INSERT INTO prediction_ingestion_api_key_rejections (
                    id, key_id, user_id, key_prefix, reason, occurred_at
                ) VALUES (?, ?, ?, 'tpik_12345678', 'EXPIRED', ?)
                """, UUID.randomUUID(), firstId, userId, NOW.plusSeconds(1));
        assertThatThrownBy(() -> execute("""
                UPDATE prediction_ingestion_api_key_rejections
                   SET reason = 'RATE_LIMITED'
                 WHERE key_id = ?
                """, firstId)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                DELETE FROM prediction_ingestion_api_key_rejections
                 WHERE key_id = ?
                """, firstId)).isInstanceOf(SQLException.class);
        assertThat(columnNames("prediction_ingestion_api_key_rejections"))
                .containsExactlyInAnyOrder(
                        "id", "key_id", "user_id", "key_prefix", "reason", "occurred_at")
                .doesNotContain("key_hash", "api_key", "payload");
        assertThatCode(() -> execute("""
                UPDATE prediction_ingestion_api_keys
                   SET status = 'EXPIRED'
                 WHERE id = ?
                """, firstId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> execute("""
                UPDATE prediction_ingestion_api_keys
                   SET status = 'ACTIVE'
                 WHERE id = ?
                """, firstId)).isInstanceOf(SQLException.class);

        var revokedId = UUID.randomUUID();
        execute("""
                INSERT INTO prediction_ingestion_api_keys (
                    id, user_id, model_version, contract_version, key_hash, key_prefix,
                    status, created_at, expires_at
                ) VALUES (?, ?, 'model-v1', 'contract-v1', ?, 'tpik_87654321', 'ACTIVE', ?, ?)
                """, revokedId, userId, "c".repeat(64), NOW, NOW.plusDays(30));
        assertThatCode(() -> execute("""
                UPDATE prediction_ingestion_api_keys
                   SET status = 'REVOKED', revoked_at = ?
                 WHERE id = ?
                """, NOW.plusSeconds(1), revokedId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> execute("""
                INSERT INTO prediction_ingestion_api_keys (
                    id, user_id, model_version, contract_version, key_hash, key_prefix,
                    status, created_at
                ) VALUES (?, ?, 'model-v1', 'contract-v1', ?, 'tpik_duplicate', 'ACTIVE', ?)
                """, UUID.randomUUID(), userId, "a".repeat(64), NOW))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                UPDATE prediction_ingestion_api_keys
                   SET status = 'ACTIVE', revoked_at = NULL
                 WHERE id = ?
                """, revokedId)).isInstanceOf(SQLException.class);
    }

    private java.util.List<String> columnNames(String table) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement("""
                     SELECT column_name
                       FROM information_schema.columns
                      WHERE table_schema = 'public'
                        AND table_name = ?
                      ORDER BY ordinal_position
                     """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                var columns = new java.util.ArrayList<String>();
                while (result.next()) {
                    columns.add(result.getString(1));
                }
                return columns;
            }
        }
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
}
