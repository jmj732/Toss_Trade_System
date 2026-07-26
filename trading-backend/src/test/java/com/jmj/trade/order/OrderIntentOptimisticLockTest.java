package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TradingBackendApplication.class)
class OrderIntentOptimisticLockTest extends PostgresIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void concurrentTransitionsAllowExactlyOneCommit() throws Exception {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        persist(OrderIntent.proposed(intentId, accountId, new BigDecimal("10")));

        var loaded = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<AttemptResult> transition = () -> approve(intentId, loaded, start);
            var first = executor.submit(transition);
            var second = executor.submit(transition);

            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(
                            AttemptResult.COMMITTED,
                            AttemptResult.OPTIMISTIC_LOCK_REJECTED);
        }

        try (var entityManager = entityManagerFactory.createEntityManager()) {
            var stored = entityManager.find(OrderIntent.class, intentId);
            assertThat(stored.getStatus()).isEqualTo(OrderIntentStatus.APPROVED);
            assertThat(stored.getVersion()).isEqualTo(1);
        }
    }

    private AttemptResult approve(
            UUID intentId,
            CountDownLatch loaded,
            CountDownLatch start
    ) throws InterruptedException {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            var intent = entityManager.find(OrderIntent.class, intentId);
            loaded.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent transition start timed out");
            }
            intent.approve();
            transaction.commit();
            return AttemptResult.COMMITTED;
        } catch (RuntimeException exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (hasOptimisticLockCause(exception)) {
                return AttemptResult.OPTIMISTIC_LOCK_REJECTED;
            }
            throw exception;
        } finally {
            entityManager.close();
        }
    }

    private void persist(OrderIntent intent) {
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            entityManager.persist(intent);
            transaction.commit();
        }
    }

    private boolean hasOptimisticLockCause(Throwable throwable) {
        for (var current = throwable; current != null; current = current.getCause()) {
            if (current instanceof OptimisticLockException) {
                return true;
            }
        }
        return false;
    }

    enum AttemptResult {
        COMMITTED,
        OPTIMISTIC_LOCK_REJECTED
    }
}
