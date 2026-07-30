package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIntentLedgerSchemaTest extends PostgresIntegrationTest {

    private Flyway flyway;

    @BeforeEach
    void migrateFreshSchema() {
        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();

        flyway.clean();
        flyway.migrate();
    }

    @Test
    void flywayCreatesOrderLedgerSchema() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("23");
    }

    @Test
    void orderIntentUsesJpaOptimisticVersion() {
        assertThatCode(() -> {
            var type = Class.forName("com.jmj.trade.order.OrderIntent");
            var versionField = type.getDeclaredField("version");
            assertThat(versionField.isAnnotationPresent(jakarta.persistence.Version.class)).isTrue();
        }).doesNotThrowAnyException();
    }

    @Test
    void terminalStateCannotTransitionAgain() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, "PROPOSED", new BigDecimal("10"));
        terminate(intentId, "CANCELED", "USER_CANCELED", BigDecimal.ZERO, new BigDecimal("10"));

        assertThatThrownBy(() -> execute("""
                UPDATE order_intents
                   SET status = 'APPROVED',
                       terminal_reason = NULL,
                       terminal_at = NULL,
                       final_filled_quantity = NULL,
                       remaining_quantity = NULL
                 WHERE id = ?
                """, intentId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void terminalQuantitiesMustSumToIntentQuantity() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, "PROPOSED", new BigDecimal("10"));

        assertThatThrownBy(() -> terminate(
                intentId, "CANCELED", "USER_CANCELED", BigDecimal.ZERO, new BigDecimal("9")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void terminalMetadataMustBeStoredAtomically() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, "PROPOSED", new BigDecimal("10"));

        assertThatThrownBy(() -> execute("""
                UPDATE order_intents
                   SET status = 'CANCELED',
                       terminal_reason = 'USER_CANCELED'
                 WHERE id = ?
                """, intentId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void partialCompletionRequiresFillAndNoFillableBrokerOrder() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, "ACTIVE", new BigDecimal("10"));
        var brokerOrderId = insertBrokerOrder(accountId, intentId, "PENDING");
        insertExecutionSnapshot(brokerOrderId, new BigDecimal("4"));

        assertThatThrownBy(() -> terminate(
                intentId,
                "PARTIALLY_COMPLETED",
                "REMAINDER_CLOSED",
                new BigDecimal("4"),
                new BigDecimal("6")))
                .isInstanceOf(SQLException.class);

        execute("UPDATE broker_orders SET status = 'CANCELED' WHERE id = ?", brokerOrderId);
        terminate(
                intentId,
                "PARTIALLY_COMPLETED",
                "REMAINDER_CLOSED",
                new BigDecimal("4"),
                new BigDecimal("6"));

        assertThat(queryStatus(intentId)).isEqualTo("PARTIALLY_COMPLETED");
    }

    @Test
    void partialCompletionRequiresExecutionEvidence() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, "ACTIVE", new BigDecimal("10"));
        insertBrokerOrder(accountId, intentId, "CANCELED");

        assertThatThrownBy(() -> terminate(
                intentId,
                "PARTIALLY_COMPLETED",
                "REMAINDER_CLOSED",
                new BigDecimal("4"),
                new BigDecimal("6")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void partialCompletionPreventsBrokerOrderFromBecomingFillableAgain() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, "ACTIVE", new BigDecimal("10"));
        var brokerOrderId = insertBrokerOrder(accountId, intentId, "CANCELED");
        insertExecutionSnapshot(brokerOrderId, new BigDecimal("4"));
        terminate(
                intentId,
                "PARTIALLY_COMPLETED",
                "REMAINDER_CLOSED",
                new BigDecimal("4"),
                new BigDecimal("6"));

        assertThatThrownBy(() ->
                execute("UPDATE broker_orders SET status = 'PENDING' WHERE id = ?", brokerOrderId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void terminalIntentPreventsLaterExecutionEvidenceChanges() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, "ACTIVE", new BigDecimal("10"));
        var brokerOrderId = insertBrokerOrder(accountId, intentId, "CANCELED");
        insertExecutionSnapshot(brokerOrderId, new BigDecimal("4"));
        terminate(
                intentId,
                "PARTIALLY_COMPLETED",
                "REMAINDER_CLOSED",
                new BigDecimal("4"),
                new BigDecimal("6"));

        assertThatThrownBy(() -> insertExecutionSnapshot(brokerOrderId, new BigDecimal("5")))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> execute(
                "UPDATE execution_snapshots SET filled_quantity = 5 WHERE broker_order_id = ?",
                brokerOrderId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void brokerOrderCannotMoveToAnotherIntent() throws SQLException {
        var accountId = insertAccount();
        var terminalIntentId = insertIntent(accountId, "ACTIVE", new BigDecimal("10"));
        var otherIntentId = insertIntent(accountId, "ACTIVE", new BigDecimal("10"));
        var brokerOrderId = insertBrokerOrder(accountId, terminalIntentId, "CANCELED");
        insertExecutionSnapshot(brokerOrderId, new BigDecimal("4"));
        terminate(
                terminalIntentId,
                "PARTIALLY_COMPLETED",
                "REMAINDER_CLOSED",
                new BigDecimal("4"),
                new BigDecimal("6"));

        assertThatThrownBy(() -> execute(
                "UPDATE broker_orders SET order_intent_id = ? WHERE id = ?",
                otherIntentId,
                brokerOrderId))
                .isInstanceOf(SQLException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "REVALIDATING, BLOCKED",
            "REVALIDATING, EXPIRED",
            "SUBMISSION_PENDING, REJECTED"
    })
    void confirmedBrokerOrderPreventsPreAcceptanceTerminalStates(
            String currentStatus,
            String terminalStatus
    ) throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, currentStatus, new BigDecimal("10"));
        insertBrokerOrder(accountId, intentId, "PENDING");

        assertThatThrownBy(() -> terminate(
                intentId,
                terminalStatus,
                "PRE_ACCEPTANCE_TERMINAL",
                BigDecimal.ZERO,
                new BigDecimal("10")))
                .isInstanceOf(SQLException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "REVALIDATING, BLOCKED",
            "REVALIDATING, EXPIRED",
            "SUBMISSION_PENDING, REJECTED"
    })
    void preAcceptanceTerminalStatePreventsLaterBrokerOrderConfirmation(
            String currentStatus,
            String terminalStatus
    ) throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId, currentStatus, new BigDecimal("10"));
        terminate(
                intentId,
                terminalStatus,
                "PRE_ACCEPTANCE_TERMINAL",
                BigDecimal.ZERO,
                new BigDecimal("10"));

        assertThatThrownBy(() -> insertBrokerOrder(accountId, intentId, "PENDING"))
                .isInstanceOf(SQLException.class);
    }

    private UUID insertAccount() throws SQLException {
        var id = UUID.randomUUID();
        execute("INSERT INTO broker_accounts (id) VALUES (?)", id);
        return id;
    }

    private UUID insertIntent(UUID accountId, String status, BigDecimal quantity) throws SQLException {
        var id = UUID.randomUUID();
        execute("""
                INSERT INTO order_intents (id, broker_account_id, quantity, status)
                VALUES (?, ?, ?, ?)
                """, id, accountId, quantity, status);
        return id;
    }

    private UUID insertBrokerOrder(UUID accountId, UUID intentId, String status) throws SQLException {
        var id = UUID.randomUUID();
        execute("""
                INSERT INTO broker_orders (
                    id, order_intent_id, broker_account_id, broker_order_id, client_order_id, status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, id, intentId, accountId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), status);
        return id;
    }

    private void insertExecutionSnapshot(UUID brokerOrderId, BigDecimal filledQuantity)
            throws SQLException {
        execute("""
                INSERT INTO execution_snapshots (
                    id, broker_order_id, filled_quantity, captured_at
                ) VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), brokerOrderId, filledQuantity, OffsetDateTime.now());
    }

    private void terminate(
            UUID intentId,
            String status,
            String reason,
            BigDecimal finalFilledQuantity,
            BigDecimal remainingQuantity
    ) throws SQLException {
        execute("""
                UPDATE order_intents
                   SET status = ?,
                       terminal_reason = ?,
                       terminal_at = ?,
                       final_filled_quantity = ?,
                       remaining_quantity = ?
                 WHERE id = ?
                """,
                status,
                reason,
                OffsetDateTime.now(),
                finalFilledQuantity,
                remainingQuantity,
                intentId);
    }

    private String queryStatus(UUID intentId) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(
                     "SELECT status FROM order_intents WHERE id = ?")) {
            statement.setObject(1, intentId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private int execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }
}
