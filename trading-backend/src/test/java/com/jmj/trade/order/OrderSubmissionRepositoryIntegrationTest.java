package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TradingBackendApplication.class)
class OrderSubmissionRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-27T01:00:00Z");
    private static final Instant CHECKED_AT = CREATED_AT.plusSeconds(20);

    @Autowired
    private SubmissionIdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private BrokerOrderRepository brokerOrderRepository;

    @Autowired
    private SubmissionAttemptRepository submissionAttemptRepository;

    @Autowired
    private ReconciliationCheckRepository reconciliationCheckRepository;

    @Autowired
    private OrderSubmissionAuditLogRepository auditLogRepository;

    @Autowired
    private OrderSubmissionOutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanLedger() {
        jdbcTemplate.execute("""
                TRUNCATE order_approval_step_up_tokens, paper_order_workflow_commands,
                         pre_trade_risk_decisions,
                         order_submission_outbox_events,
                         order_submission_audit_logs,
                         reconciliation_checks,
                         submission_attempts,
                         submission_idempotency_keys,
                         order_intent_outbox_events,
                         order_intent_audit_logs,
                         execution_snapshots,
                         broker_orders,
                         real_order_daily_reservations, real_order_account_allowlist, order_intents,
                         broker_accounts
                """);
    }

    @Test
    void repositoriesPersistAndLoadSubmissionLedgerMappings() {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        var brokerOrderPk = UUID.randomUUID();
        var checkId = UUID.randomUUID();
        var auditId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        var expiresAt = CREATED_AT.plusSeconds(600);

        insertAccountAndIntent(accountId, intentId);
        idempotencyKeyRepository.saveAndFlush(SubmissionIdempotencyKey.create(
                accountId,
                "client-1",
                intentId,
                "hash-1",
                expiresAt,
                CREATED_AT));

        var brokerOrder = BrokerOrder.confirmed(
                brokerOrderPk,
                intentId,
                accountId,
                "broker-1",
                "client-1",
                BrokerOrderStatus.PENDING);
        brokerOrderRepository.saveAndFlush(brokerOrder);
        brokerOrder.updateProjection(BrokerOrderStatus.PARTIALLY_FILLED);
        brokerOrderRepository.saveAndFlush(brokerOrder);

        var attempt = SubmissionAttempt.initial(
                attemptId,
                intentId,
                accountId,
                "client-1",
                "hash-1",
                "internal-1",
                CREATED_AT);
        submissionAttemptRepository.saveAndFlush(attempt);
        entityManager.clear();

        var checkNumber = new AtomicInteger();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            var lifecycleAttempt = submissionAttemptRepository.findById(attemptId).orElseThrow();
            lifecycleAttempt.startDispatch(CREATED_AT.plusSeconds(1), new DispatchEvidence("req-1", "sent"));
            submissionAttemptRepository.saveAndFlush(lifecycleAttempt);
            lifecycleAttempt.markUnknown(CREATED_AT.plusSeconds(2), new DispatchEvidence("req-1", "timeout"));
            submissionAttemptRepository.saveAndFlush(lifecycleAttempt);
            lifecycleAttempt.startReconciliation();
            submissionAttemptRepository.saveAndFlush(lifecycleAttempt);
            checkNumber.set(lifecycleAttempt.allocateNextReconciliationCheckNumber());
            submissionAttemptRepository.saveAndFlush(lifecycleAttempt);

            reconciliationCheckRepository.saveAndFlush(ReconciliationCheck.record(
                    checkId,
                    attemptId,
                    intentId,
                    checkNumber.get(),
                    true,
                    true,
                    CREATED_AT.minusSeconds(60),
                    CREATED_AT.plusSeconds(60),
                    true,
                    "result-hash-1",
                    brokerOrderPk,
                    ReconciliationDecision.BROKER_ORDER_FOUND,
                    CHECKED_AT));

            lifecycleAttempt.acknowledge(
                    CHECKED_AT.plusSeconds(1),
                    brokerOrderPk,
                    new DispatchEvidence("req-1", "reconciled"));
            submissionAttemptRepository.saveAndFlush(lifecycleAttempt);

            auditLogRepository.save(new OrderSubmissionAuditLog(
                    auditId,
                    intentId,
                    "SubmissionAttempt",
                    attemptId,
                    "ReconciliationCheckRecorded",
                    "tester",
                    "{\"checkNumber\":" + checkNumber.get() + "}",
                    CHECKED_AT));
            outboxEventRepository.saveAndFlush(new OrderSubmissionOutboxEvent(
                    outboxId,
                    intentId,
                    "SubmissionAttempt",
                    attemptId,
                    "BrokerOrderFound",
                    "tester",
                    "{\"brokerOrderId\":\"" + brokerOrderPk + "\"}",
                    CHECKED_AT));
        });
        entityManager.clear();

        var storedAttempt = submissionAttemptRepository.findById(attemptId).orElseThrow();
        var storedKey = idempotencyKeyRepository.findById(
                new SubmissionIdempotencyKeyId(accountId, "client-1")).orElseThrow();
        var storedBrokerOrder = brokerOrderRepository.findByBrokerAccountIdAndBrokerOrderId(
                accountId, "broker-1").orElseThrow();
        var storedCheck = reconciliationCheckRepository.findById(checkId).orElseThrow();
        var storedAudit = auditLogRepository.findById(auditId).orElseThrow();
        var storedOutbox = outboxEventRepository.findById(outboxId).orElseThrow();

        assertThat(storedAttempt.getStatus()).isEqualTo(SubmissionAttemptStatus.ACKNOWLEDGED);
        assertThat(storedAttempt.getConfirmedBrokerOrderId()).isEqualTo(brokerOrderPk);
        assertThat(storedAttempt.getLastReconciliationCheckNumber()).isEqualTo(1);
        assertThat(storedKey.getOrderIntentId()).isEqualTo(intentId);
        assertThat(storedKey.getRequestBodyHash()).isEqualTo("hash-1");
        assertThat(storedKey.getIdempotencyExpiresAt()).isEqualTo(expiresAt);
        assertThat(storedBrokerOrder.getId()).isEqualTo(brokerOrderPk);
        assertThat(storedBrokerOrder.getStatus()).isEqualTo(BrokerOrderStatus.PARTIALLY_FILLED);
        assertThat(storedBrokerOrder.getVersion()).isEqualTo(1);
        assertThat(storedCheck.getSubmissionAttemptId()).isEqualTo(attemptId);
        assertThat(storedCheck.getCheckNumber()).isEqualTo(1);
        assertThat(storedCheck.getDecision()).isEqualTo(ReconciliationDecision.BROKER_ORDER_FOUND);
        assertThat(storedCheck.getMatchedBrokerOrderId()).isEqualTo(brokerOrderPk);
        assertThat(storedAudit.getPayload()).contains("\"checkNumber\"");
        assertThat(storedOutbox.getPayload()).contains(brokerOrderPk.toString());
        assertThat(storedOutbox.getAttempts()).isZero();
        assertThat(storedOutbox.getPublishedAt()).isNull();
        assertJsonObject("order_submission_audit_logs", auditId, "checkNumber", "1");
        assertJsonObject("order_submission_outbox_events", outboxId, "brokerOrderId", brokerOrderPk.toString());
    }

    @Test
    void idempotencyKeyFactoryMatchesCanonicalClientOrderIdAndExpiryContract() {
        assertThatThrownBy(() -> SubmissionIdempotencyKey.create(
                UUID.randomUUID(),
                "bad id",
                UUID.randomUUID(),
                "hash-1",
                CREATED_AT.plusSeconds(600),
                CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientOrderId");
        assertThatThrownBy(() -> SubmissionIdempotencyKey.create(
                UUID.randomUUID(),
                "client-1",
                UUID.randomUUID(),
                "hash-1",
                CREATED_AT.plusSeconds(601),
                CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10 minutes");
    }

    private void insertAccountAndIntent(UUID accountId, UUID intentId) {
        jdbcTemplate.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        jdbcTemplate.update("""
                INSERT INTO order_intents (id, broker_account_id, quantity, status)
                VALUES (?, ?, ?, 'SUBMISSION_PENDING')
                """, intentId, accountId, new BigDecimal("10"));
    }

    private void assertJsonObject(String table, UUID id, String key, String value) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT jsonb_typeof(payload) = 'object'
                   AND payload ->> ? = ?
                  FROM %s
                 WHERE id = ?
                """.formatted(table), Boolean.class, key, value, id)).isTrue();
    }
}
