package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentDecisionTargetQuantityMigrationTest extends PostgresIntegrationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void cleanSchema() {
        flyway().clean();
    }

    @Test
    void v63AddsNullableTargetsAndPreservesExistingDecisionRows() throws SQLException {
        var beforeV63 = flyway("62");
        beforeV63.migrate();
        var userId = insertUser();
        var decisionId = UUID.randomUUID();
        insertLegacyDecision(decisionId, userId);

        assertThat(beforeV63.info().current().getVersion().getVersion()).isEqualTo("62");
        var current = flyway();
        current.migrate();

        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("63");
        assertThat(columnIsNullable("target_remaining_quantity")).isTrue();
        assertThat(columnIsNullable("target_broker_connection_id")).isTrue();
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement("""
                     SELECT asset, action, reference_price, target_remaining_quantity, target_broker_connection_id
                       FROM investment_decision_ledger
                      WHERE decision_id = ?
                     """)) {
            statement.setObject(1, decisionId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("asset")).isEqualTo("LUNR");
                assertThat(result.getString("action")).isEqualTo("HOLD");
                assertThat(result.getBigDecimal("reference_price")).isEqualByComparingTo("10.35");
                assertThat(result.getBigDecimal("target_remaining_quantity")).isNull();
                assertThat(result.getObject("target_broker_connection_id")).isNull();
            }
        }
    }

    @Test
    void allowsOnlyActionCompatibleTargetsOwnedByTheDecisionUser() throws SQLException {
        flyway().migrate();
        var userId = insertUser();
        var connectionId = insertBrokerConnection(userId);

        insertDecision(UUID.randomUUID(), userId, "REDUCE", new BigDecimal("0.000001"), connectionId);
        insertDecision(UUID.randomUUID(), userId, "EXIT", BigDecimal.ZERO, connectionId);
        insertDecision(UUID.randomUUID(), userId, "HOLD", null, null);

        assertConstraintViolation("ck_investment_decision_target_quantity",
                () -> insertDecision(UUID.randomUUID(), userId, "ADD", BigDecimal.ONE, connectionId));
        assertConstraintViolation("ck_investment_decision_target_quantity",
                () -> insertDecision(UUID.randomUUID(), userId, "REDUCE", new BigDecimal("-0.1"), connectionId));
        assertConstraintViolation("ck_investment_exit_target_zero",
                () -> insertDecision(UUID.randomUUID(), userId, "EXIT", BigDecimal.ONE, connectionId));
        assertConstraintViolation("ck_investment_reduce_target_positive",
                () -> insertDecision(UUID.randomUUID(), userId, "REDUCE", BigDecimal.ZERO, connectionId));
        assertConstraintViolation("ck_investment_target_account_pair",
                () -> insertDecision(UUID.randomUUID(), userId, "REDUCE", BigDecimal.ONE, null));
        assertConstraintViolation("ck_investment_target_account_pair",
                () -> insertDecision(UUID.randomUUID(), userId, "REDUCE", null, connectionId));

        var otherUserId = insertUser();
        var otherConnectionId = insertBrokerConnection(otherUserId);
        assertConstraintViolation("fk_investment_decision_target_connection",
                () -> insertDecision(UUID.randomUUID(), userId, "REDUCE", BigDecimal.ONE, otherConnectionId));
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
    }

    private Flyway flyway(String targetVersion) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion(targetVersion))
                .cleanDisabled(false)
                .load();
    }

    private UUID insertUser() throws SQLException {
        var userId = UUID.randomUUID();
        execute("INSERT INTO users (id) VALUES (?)", userId);
        return userId;
    }

    private UUID insertBrokerConnection(UUID userId) throws SQLException {
        var connectionId = UUID.randomUUID();
        execute("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?)
                """, connectionId, userId, new byte[17], new byte[12], NOW, NOW);
        return connectionId;
    }

    private void insertLegacyDecision(UUID decisionId, UUID userId) throws SQLException {
        execute("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session,
                    horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                    risk_policy_check, created_at
                ) VALUES (?, ?, ?, 'LUNR', 'HOLD', 10.35, 'LIVE_REGULAR', '12M', 'Legacy thesis',
                          'Legacy invalidation', 'Review next session', 0.8, '{}'::jsonb, ?)
                """, decisionId, userId, NOW, NOW);
    }

    private void insertDecision(
            UUID decisionId,
            UUID userId,
            String action,
            BigDecimal targetQuantity,
            UUID targetConnectionId
    ) throws SQLException {
        execute("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session,
                    horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                    risk_policy_check, created_at, target_remaining_quantity, target_broker_connection_id
                ) VALUES (?, ?, ?, 'LUNR', ?, 10.35, 'LIVE_REGULAR', '12M', 'Thesis', 'Invalidation',
                          'Review next session', 0.8, '{}'::jsonb, ?, ?, ?)
                """, decisionId, userId, NOW, action, NOW, targetQuantity, targetConnectionId);
    }

    private boolean columnIsNullable(String columnName) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement("""
                     SELECT is_nullable
                       FROM information_schema.columns
                      WHERE table_schema = 'public'
                        AND table_name = 'investment_decision_ledger'
                        AND column_name = ?
                     """)) {
            statement.setString(1, columnName);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return "YES".equals(result.getString("is_nullable"));
            }
        }
    }

    private void assertConstraintViolation(String constraintName, SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(constraintName);
    }

    private void execute(String sql, Object... args) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            statement.execute();
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
