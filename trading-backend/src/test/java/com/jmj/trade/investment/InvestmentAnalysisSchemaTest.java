package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentAnalysisSchemaTest extends PostgresIntegrationTest {

    private Flyway flyway;

    @BeforeEach
    void migrateFreshSchema() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void createsInvestmentAnalysisTablesAfterExistingMigrations() throws SQLException {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("56");
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            var tables = List.of("investment_price_snapshots", "fundamental_snapshots", "consensus_snapshots",
                    "investment_security_snapshots", "investment_thesis_states", "investment_decision_ledger",
                    "investment_pipeline_state", "investment_os_portfolio_snapshots");
            for (var table : tables) {
                try (var result = statement.executeQuery("SELECT 1 FROM " + table + " WHERE false")) {
                    assertThat(result.next()).isFalse();
                }
            }
        }
    }

    @Test
    void decisionLedgerRejectsMutation() throws SQLException {
        var userId = UUID.randomUUID();
        var decisionId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        try (Connection connection = POSTGRES.createConnection("")) {
            try (var insertUser = connection.prepareStatement("INSERT INTO users (id) VALUES (?)")) {
                insertUser.setObject(1, userId);
                insertUser.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO investment_decision_ledger (
                        decision_id, user_id, as_of, asset, action, reference_price, price_session,
                        horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                        risk_policy_check, created_at
                    ) VALUES (?, ?, ?, 'AAPL', 'ADD', 100, 'LIVE_REGULAR', '12M', 'thesis',
                              'invalidation', 'review', 0.8, '{}'::jsonb, ?)
                    """)) {
                insert.setObject(1, decisionId);
                insert.setObject(2, userId);
                insert.setObject(3, now);
                insert.setObject(4, now);
                insert.executeUpdate();
            }
            assertThatThrownBy(() -> {
                try (var update = connection.prepareStatement(
                        "UPDATE investment_decision_ledger SET action = 'HOLD' WHERE decision_id = ?")) {
                    update.setObject(1, decisionId);
                    update.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void portfolioSnapshotRejectsAcceptedAttemptWithoutPayload() throws SQLException {
        var userId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        try (Connection connection = POSTGRES.createConnection("")) {
            try (var insertUser = connection.prepareStatement("INSERT INTO users (id) VALUES (?)")) {
                insertUser.setObject(1, userId);
                insertUser.executeUpdate();
            }
            assertThatThrownBy(() -> {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO investment_os_portfolio_snapshots (
                            id, user_id, attempt_status, attempted_at, error_code, payload, created_at
                        ) VALUES (?, ?, 'SUCCEEDED', ?, NULL, NULL, ?)
                        """)) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, userId);
                    insert.setObject(3, now);
                    insert.setObject(4, now);
                    insert.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }
}
