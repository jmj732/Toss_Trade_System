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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TradingBackendApplication.class)
class SubmissionAttemptRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-27T01:00:00Z");
    private static final Instant STARTED_AT = CREATED_AT.plusSeconds(1);

    @Autowired
    private SubmissionAttemptRepository repository;

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
    void repositoryPersistsDispatchEvidenceAsJsonbAndReloadsIt() {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        jdbcTemplate.update("""
                INSERT INTO order_intents (id, broker_account_id, quantity, status)
                VALUES (?, ?, ?, 'SUBMISSION_PENDING')
                """, intentId, accountId, new BigDecimal("10"));
        var createdAt = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO submission_idempotency_keys (
                    broker_account_id, client_order_id, order_intent_id, request_body_hash,
                    idempotency_expires_at, created_at
                ) VALUES (?, 'client-1', ?, 'hash-1', ?, ?)
                """, accountId, intentId, createdAt.plusSeconds(600), createdAt);

        var attempt = SubmissionAttempt.initial(
                attemptId,
                intentId,
                accountId,
                "client-1",
                "hash-1",
                "internal-1",
                CREATED_AT);
        repository.saveAndFlush(attempt);

        var evidence = new DispatchEvidence("request-1", "accepted by client");
        attempt.startDispatch(STARTED_AT, evidence);
        repository.saveAndFlush(attempt);
        entityManager.clear();

        var stored = repository.findById(attemptId).orElseThrow();

        assertThat(stored.getDispatchEvidence()).isEqualTo(evidence);
        assertThat(stored.getStatus()).isEqualTo(SubmissionAttemptStatus.DISPATCHING);
        assertThat(stored.getStartedAt()).isEqualTo(STARTED_AT);
    }
}
