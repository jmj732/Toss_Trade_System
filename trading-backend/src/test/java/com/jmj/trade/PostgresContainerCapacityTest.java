package com.jmj.trade;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fix for the CI failure this delta addresses: ~18 distinct
 * {@code @SpringBootTest} configurations across the suite each hold their own HikariCP pool
 * against the one shared {@link PostgresIntegrationTest#POSTGRES} container, and their
 * combined connection count can exceed Postgres's own default {@code max_connections=100}.
 */
class PostgresContainerCapacityTest extends PostgresIntegrationTest {

    // Postgres's own built-in default, independent of anything this suite configures — the
    // number the fix in PostgresIntegrationTest raises past.
    private static final int POSTGRES_DEFAULT_MAX_CONNECTIONS = 100;

    // Comfortably exceeds POSTGRES_DEFAULT_MAX_CONNECTIONS. Without the raised
    // max_connections in PostgresIntegrationTest, opening this many reliably fails partway
    // through with "FATAL: sorry, too many clients already" — the exact CI failure this
    // delta fixes, reproduced directly rather than depending on how many other Spring test
    // contexts happen to be cached at the same moment.
    private static final int CONNECTIONS_TO_OPEN = 120;

    @Test
    void maxConnectionsIsRaisedAboveThePostgresDefault() throws Exception {
        try (var connection = rawConnection();
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("SHOW max_connections")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isGreaterThan(POSTGRES_DEFAULT_MAX_CONNECTIONS);
        }
    }

    @Test
    void reproducesAndSurvivesAConnectionCountThatWouldExhaustThePostgresDefault() throws Exception {
        var connections = new ArrayList<Connection>();
        try {
            for (var i = 0; i < CONNECTIONS_TO_OPEN; i++) {
                connections.add(rawConnection());
            }
            for (var connection : connections) {
                assertThat(connection.isValid(2)).isTrue();
            }
            // Confirms these connections are real, server-visible backends (not just
            // client-side handles that happened not to throw yet) — a check the loop above
            // can't already guarantee on its own.
            try (var probe = rawConnection();
                    var statement = probe.createStatement();
                    var resultSet = statement.executeQuery(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isGreaterThan(CONNECTIONS_TO_OPEN);
            }
        } finally {
            for (var connection : connections) {
                closeQuietly(connection);
            }
        }
    }

    private static Connection rawConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // A close() failure must never abort the loop: doing so would leak every remaining
    // connection as a live Postgres backend for the rest of the JVM run — precisely the
    // exhaustion this test suite exists to guard against.
    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best-effort cleanup; nothing meaningful to do if a connection refuses to close.
        }
    }
}
