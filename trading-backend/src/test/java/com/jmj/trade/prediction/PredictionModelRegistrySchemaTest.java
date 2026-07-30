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

class PredictionModelRegistrySchemaTest extends PostgresIntegrationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 30, 0, 0, 0, 0, ZoneOffset.UTC);

    private Flyway flyway;

    @BeforeEach
    void prepareCleanDatabase() {
        flyway = flyway(null);
        flyway.clean();
    }

    @Test
    void v20BackfillsDistinctExistingPredictionVersionsAsActive() throws SQLException {
        flyway = flyway("19");
        flyway.migrate();
        var owner = insertOwnerAndConnection();
        insertPrediction(owner, "AAPL", "model-v1", "contract-v1");
        insertPrediction(owner, "MSFT", "model-v1", "contract-v1");

        flyway = flyway("20");
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("20");
        assertThat(queryLong("""
                SELECT count(*)
                  FROM prediction_model_versions
                 WHERE user_id = ?
                   AND model_version = 'model-v1'
                   AND contract_version = 'contract-v1'
                   AND status = 'ACTIVE'
                """, owner.userId())).isEqualTo(1);
    }

    @Test
    void versionIdentityAndDeprecationAreImmutable() throws SQLException {
        flyway.migrate();
        var userId = insertUser();
        var id = insertVersion(userId, "model-v1", "contract-v1");

        assertThatThrownBy(() -> execute(
                "UPDATE prediction_model_versions SET model_version = 'model-v2' WHERE id = ?", id))
                .isInstanceOf(SQLException.class);
        assertThatCode(() -> execute("""
                UPDATE prediction_model_versions
                   SET status = 'DEPRECATED', deprecated_at = ?
                 WHERE id = ?
                """, NOW.plusDays(1), id)).doesNotThrowAnyException();
        assertThatThrownBy(() -> execute("""
                UPDATE prediction_model_versions
                   SET deprecated_at = ?
                 WHERE id = ?
                """, NOW.plusDays(2), id)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                UPDATE prediction_model_versions
                   SET status = 'ACTIVE', deprecated_at = NULL
                 WHERE id = ?
                """, id)).isInstanceOf(SQLException.class);
    }

    @Test
    void usedVersionCannotBeDeletedButUnusedVersionCan() throws SQLException {
        flyway.migrate();
        var owner = insertOwnerAndConnection();
        var used = insertVersion(owner.userId(), "used", "v1");
        var unused = insertVersion(owner.userId(), "unused", "v1");
        insertPrediction(owner, "AAPL", "used", "v1");

        assertThatThrownBy(() ->
                execute("DELETE FROM prediction_model_versions WHERE id = ?", used))
                .isInstanceOf(SQLException.class);
        assertThatCode(() ->
                execute("DELETE FROM prediction_model_versions WHERE id = ?", unused))
                .doesNotThrowAnyException();
    }

    @Test
    void v21ScopesClientRequestIdUniquenessByUser() throws SQLException {
        flyway.migrate();
        var firstOwner = insertOwnerAndConnection();
        insertVersion(firstOwner.userId(), "model-v1", "contract-v1");
        insertPrediction(firstOwner, "AAPL", "model-v1", "contract-v1");
        insertPrediction(firstOwner, "MSFT", "model-v1", "contract-v1");
        var firstOwnerPredictions = predictionIds(firstOwner.userId());

        execute("UPDATE analysis_predictions SET client_request_id = 'request-1' WHERE id = ?",
                firstOwnerPredictions.get(0));
        assertThatThrownBy(() -> execute(
                "UPDATE analysis_predictions SET client_request_id = 'request-1' WHERE id = ?",
                firstOwnerPredictions.get(1)))
                .isInstanceOf(SQLException.class);

        var secondOwner = insertOwnerAndConnection();
        insertVersion(secondOwner.userId(), "model-v1", "contract-v1");
        insertPrediction(secondOwner, "GOOG", "model-v1", "contract-v1");
        assertThatCode(() -> execute("""
                UPDATE analysis_predictions
                   SET client_request_id = 'request-1'
                 WHERE user_id = ?
                """, secondOwner.userId())).doesNotThrowAnyException();
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private UUID insertUser() throws SQLException {
        var userId = UUID.randomUUID();
        execute("INSERT INTO users (id) VALUES (?)", userId);
        return userId;
    }

    private Owner insertOwnerAndConnection() throws SQLException {
        var userId = insertUser();
        var connectionId = UUID.randomUUID();
        execute("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], NOW, NOW);
        return new Owner(userId, connectionId);
    }

    private UUID insertVersion(UUID userId, String modelVersion, String contractVersion)
            throws SQLException {
        var id = UUID.randomUUID();
        execute("""
                INSERT INTO prediction_model_versions (
                    id, user_id, model_version, contract_version, status, created_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """, id, userId, modelVersion, contractVersion, NOW);
        return id;
    }

    private void insertPrediction(
            Owner owner,
            String symbol,
            String modelVersion,
            String contractVersion
    ) throws SQLException {
        execute("""
                INSERT INTO analysis_predictions (
                    id, user_id, broker_connection_id, symbol, currency, predicted_direction,
                    model_version, contract_version, baseline_price, predicted_at, created_at
                ) VALUES (?, ?, ?, ?, 'USD', 'UP', ?, ?, 100, ?, ?)
                """, UUID.randomUUID(), owner.userId(), owner.connectionId(), symbol,
                modelVersion, contractVersion, NOW, NOW);
    }

    private long queryLong(String sql, Object... args) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private java.util.List<UUID> predictionIds(UUID userId) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement("""
                     SELECT id
                       FROM analysis_predictions
                      WHERE user_id = ?
                      ORDER BY symbol
                     """)) {
            statement.setObject(1, userId);
            try (var result = statement.executeQuery()) {
                var ids = new java.util.ArrayList<UUID>();
                while (result.next()) {
                    ids.add(result.getObject(1, UUID.class));
                }
                return ids;
            }
        }
    }

    private void execute(String sql, Object... args) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.execute();
        }
    }

    private void bind(java.sql.PreparedStatement statement, Object... args) throws SQLException {
        for (var i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private record Owner(UUID userId, UUID connectionId) {
    }
}
