package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIntentTransitionLedgerSchemaTest extends PostgresIntegrationTest {

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
    void flywayCreatesTransitionLedgerSchema() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
    }

    @Test
    void auditLogsAreAppendOnly() throws SQLException {
        var intentId = insertOrderIntent();
        var auditId = UUID.randomUUID();
        execute("""
                INSERT INTO order_intent_audit_logs (
                    id, order_intent_id, from_status, to_status, actor, occurred_at
                ) VALUES (?, ?, 'PROPOSED', 'APPROVED', 'tester', ?)
                """, auditId, intentId, OffsetDateTime.now());

        assertThatThrownBy(() -> execute(
                "UPDATE order_intent_audit_logs SET actor = 'other' WHERE id = ?",
                auditId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "DELETE FROM order_intent_audit_logs WHERE id = ?",
                auditId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void auditAndOutboxStatusesMustBeKnownOrderIntentStatuses() throws SQLException {
        var intentId = insertOrderIntent();

        assertThatThrownBy(() -> execute("""
                INSERT INTO order_intent_audit_logs (
                    id, order_intent_id, from_status, to_status, actor, occurred_at
                ) VALUES (?, ?, 'NOT_A_STATUS', 'APPROVED', 'tester', ?)
                """, UUID.randomUUID(), intentId, OffsetDateTime.now()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                INSERT INTO order_intent_outbox_events (
                    id, order_intent_id, event_type, from_status, to_status, actor, payload, created_at
                ) VALUES (?, ?, 'OrderIntentStatusChanged', 'PROPOSED', 'NOT_A_STATUS', 'tester', '{}'::jsonb, ?)
                """, UUID.randomUUID(), intentId, OffsetDateTime.now()))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void outboxEventsAllowOnlyDeliveryMetadataUpdates() throws SQLException {
        var intentId = insertOrderIntent();
        var outboxId = UUID.randomUUID();
        execute("""
                INSERT INTO order_intent_outbox_events (
                    id, order_intent_id, event_type, from_status, to_status, actor, payload, created_at
                ) VALUES (?, ?, 'OrderIntentStatusChanged', 'PROPOSED', 'APPROVED', 'tester', '{}'::jsonb, ?)
                """, outboxId, intentId, OffsetDateTime.now());

        execute("""
                UPDATE order_intent_outbox_events
                   SET published_at = ?, attempts = attempts + 1
                 WHERE id = ?
                """, OffsetDateTime.now(), outboxId);

        assertThatThrownBy(() -> execute(
                "UPDATE order_intent_outbox_events SET actor = 'other' WHERE id = ?",
                outboxId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE order_intent_outbox_events SET payload = '{\"changed\":true}'::jsonb WHERE id = ?",
                outboxId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE order_intent_outbox_events SET from_status = 'APPROVED' WHERE id = ?",
                outboxId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "DELETE FROM order_intent_outbox_events WHERE id = ?",
                outboxId))
                .isInstanceOf(SQLException.class);
    }

    private UUID insertOrderIntent() throws SQLException {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        execute("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        execute("""
                INSERT INTO order_intents (id, broker_account_id, quantity, status)
                VALUES (?, ?, 1, 'PROPOSED')
                """, intentId, accountId);
        return intentId;
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
