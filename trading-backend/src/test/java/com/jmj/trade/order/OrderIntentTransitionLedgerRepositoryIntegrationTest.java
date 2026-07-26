package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TradingBackendApplication.class)
class OrderIntentTransitionLedgerRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OrderIntentRepository orderIntentRepository;

    @Autowired
    private OrderIntentAuditLogRepository auditLogRepository;

    @Autowired
    private OrderIntentOutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanLedger() {
        jdbcTemplate.execute("""
                TRUNCATE order_submission_outbox_events,
                         order_submission_audit_logs,
                         reconciliation_checks,
                         submission_attempts,
                         submission_idempotency_keys,
                         order_intent_outbox_events,
                         order_intent_audit_logs,
                         execution_snapshots,
                         broker_orders,
                         order_intents,
                         broker_accounts
                """);
    }

    @Test
    void repositoriesPersistAndLoadTransitionLedgers() {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        var auditId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        var now = Instant.now();
        jdbcTemplate.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(
                intentId,
                accountId,
                new BigDecimal("10")));

        auditLogRepository.save(new OrderIntentAuditLog(
                auditId,
                intentId,
                OrderIntentStatus.PROPOSED,
                OrderIntentStatus.APPROVED,
                "tester",
                null,
                now));
        outboxEventRepository.saveAndFlush(new OrderIntentOutboxEvent(
                outboxId,
                intentId,
                "OrderIntentStatusChanged",
                OrderIntentStatus.PROPOSED,
                OrderIntentStatus.APPROVED,
                "tester",
                null,
                "{\"orderIntentId\":\"" + intentId + "\"}",
                now));
        entityManager.clear();

        var audit = auditLogRepository.findById(auditId).orElseThrow();
        var outbox = outboxEventRepository.findById(outboxId).orElseThrow();

        assertThat(audit.getOrderIntentId()).isEqualTo(intentId);
        assertThat(audit.getFromStatus()).isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(audit.getToStatus()).isEqualTo(OrderIntentStatus.APPROVED);
        assertThat(outbox.getOrderIntentId()).isEqualTo(intentId);
        assertThat(outbox.getEventType()).isEqualTo("OrderIntentStatusChanged");
        assertThat(outbox.getPayload()).contains(intentId.toString());
        assertThat(outbox.getAttempts()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT jsonb_typeof(payload) = 'object'
                   AND payload ->> 'orderIntentId' = ?
                  FROM order_intent_outbox_events
                 WHERE id = ?
                """, Boolean.class, intentId.toString(), outboxId)).isTrue();
    }
}
