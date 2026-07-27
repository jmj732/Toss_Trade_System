package com.jmj.trade.broker.connection;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerConnectionSchemaTest extends PostgresIntegrationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 27, 9, 0, 0, 0, ZoneOffset.UTC);

    private Flyway flyway;

    @BeforeEach
    void prepareCleanDatabase() {
        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();

        flyway.clean();
    }

    @Test
    void migratesEmptyDatabaseThroughV4() {
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
    }

    @Test
    void migratesIncrementallyFromV3ToV4() throws SQLException {
        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .target("3")
                .cleanDisabled(false)
                .load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");

        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThatCode(() -> execute("SELECT 1 FROM broker_connections WHERE false"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidActiveConnection() throws SQLException {
        flyway.migrate();
        var userId = insertUser();

        assertThatCode(() -> insertConnection(userId, "UNVERIFIED", ciphertext(), nonce(), 1, 1, null, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidActiveSecretShape() throws SQLException {
        flyway.migrate();
        var userId = insertUser();

        assertThatThrownBy(() -> insertConnection(userId, "UNVERIFIED", ciphertext(), nonce(), null, 1, null, 0))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertConnection(userId, "UNVERIFIED", ciphertext(), new byte[11], 1, 1, null, 0))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertConnection(userId, "UNVERIFIED", null, nonce(), 1, 1, null, 0))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertConnection(userId, "UNVERIFIED", new byte[16], nonce(), 1, 1, null, 0))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertConnection(userId, "UNVERIFIED", ciphertext(), nonce(), 1, 1, NOW, 0))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsDeletedRowsThatDoNotScrubSecretsOrDeletedAt() throws SQLException {
        flyway.migrate();
        var userId = insertUser();

        assertThatThrownBy(() -> insertConnection(userId, "DELETED", ciphertext(), null, null, 1, NOW, 0))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertConnection(userId, "DELETED", null, nonce(), null, 1, NOW, 0))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertConnection(userId, "DELETED", null, null, 1, 1, NOW, 0))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertConnection(userId, "DELETED", null, null, null, 1, null, 0))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void enforcesOneActiveBrokerConnectionPerUserAndBroker() throws SQLException {
        flyway.migrate();
        var userId = insertUser();
        var firstId = insertConnection(userId, "ACTIVE", ciphertext(), nonce(), 1, 1, null, 0);

        assertThatThrownBy(() -> insertConnection(userId, "UNVERIFIED", ciphertext(), nonce(), 1, 1, null, 0))
                .isInstanceOf(SQLException.class);

        execute("""
                UPDATE broker_connections
                   SET status = 'DELETED',
                       credential_ciphertext = NULL,
                       credential_nonce = NULL,
                       credential_key_version = NULL,
                       last_validated_at = NULL,
                       deleted_at = ?
                 WHERE id = ?
                """, NOW, firstId);

        assertThatCode(() -> insertConnection(userId, "UNVERIFIED", ciphertext(), nonce(), 1, 1, null, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveCredentialRevisionAndNegativeVersion() throws SQLException {
        flyway.migrate();
        var userId = insertUser();

        assertThatThrownBy(() -> insertConnection(userId, "UNVERIFIED", ciphertext(), nonce(), 1, 0, null, 0))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertConnection(userId, "UNVERIFIED", ciphertext(), nonce(), 1, 1, null, -1))
                .isInstanceOf(SQLException.class);
    }

    private UUID insertUser() throws SQLException {
        var id = UUID.randomUUID();
        execute("INSERT INTO users (id) VALUES (?)", id);
        return id;
    }

    private UUID insertConnection(
            UUID userId,
            String status,
            byte[] ciphertext,
            byte[] nonce,
            Integer keyVersion,
            long revision,
            OffsetDateTime deletedAt,
            long version
    ) throws SQLException {
        var id = UUID.randomUUID();
        execute("""
                INSERT INTO broker_connections (
                    id,
                    user_id,
                    broker_type,
                    status,
                    credential_ciphertext,
                    credential_nonce,
                    credential_key_version,
                    credential_revision,
                    created_at,
                    updated_at,
                    deleted_at,
                    version
                )
                VALUES (?, ?, 'TOSS_INVEST', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, status, ciphertext, nonce, keyVersion, revision, NOW, NOW, deletedAt, version);
        return id;
    }

    private void execute(String sql, Object... args) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < args.length; i++) {
                if (args[i] == null) {
                    statement.setNull(i + 1, Types.NULL);
                } else {
                    statement.setObject(i + 1, args[i]);
                }
            }
            statement.execute();
        }
    }

    private byte[] ciphertext() {
        return new byte[17];
    }

    private byte[] nonce() {
        return new byte[12];
    }
}
