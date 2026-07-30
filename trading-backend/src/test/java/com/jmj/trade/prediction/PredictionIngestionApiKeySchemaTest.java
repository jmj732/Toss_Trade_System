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
    void v22StoresScopedHashOnlyKeysWithLifecycleConstraints() throws SQLException {
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
                    status, created_at
                ) VALUES (?, ?, 'model-v1', 'contract-v1', ?, 'tpik_12345678', 'ACTIVE', ?)
                """, firstId, userId, "a".repeat(64), NOW);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("22");
        assertThatCode(() -> execute("""
                UPDATE prediction_ingestion_api_keys
                   SET status = 'REVOKED', revoked_at = ?
                 WHERE id = ?
                """, NOW.plusSeconds(1), firstId)).doesNotThrowAnyException();
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
                """, firstId)).isInstanceOf(SQLException.class);
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
