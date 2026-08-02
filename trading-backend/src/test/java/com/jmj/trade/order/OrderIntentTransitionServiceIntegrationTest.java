package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {
        TradingBackendApplication.class,
        OrderIntentTransitionServiceIntegrationTest.RepositoryFindGateConfiguration.class
})
class OrderIntentTransitionServiceIntegrationTest extends PostgresIntegrationTest {

    private static final String ACTOR = "integration-test";
    private static final RepositoryFindGate REPOSITORY_FIND_GATE = new RepositoryFindGate();

    @Autowired
    private OrderIntentTransitionService transitionService;

    @Autowired
    private OrderIntentRepository orderIntentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanLedger() {
        REPOSITORY_FIND_GATE.disable();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_order_intent_outbox_insert ON order_intent_outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_order_intent_outbox_insert()");
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

    @AfterEach
    void disableRepositoryGate() {
        REPOSITORY_FIND_GATE.disable();
    }

    @Test
    void approveCommitsStateAuditAndOutboxAtomically() {
        var intentId = proposedIntent("10");

        transitionService.approve(intentId, ACTOR);

        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.APPROVED);
        assertLedger(intentId, OrderIntentStatus.PROPOSED, OrderIntentStatus.APPROVED, 1);
    }

    @Test
    void terminateStoresCalculatedTerminalDataWithLedgerRows() {
        var intentId = activeIntent("10");
        var terminalAt = Instant.parse("2026-07-27T01:00:00Z");
        insertClosedBrokerOrderWithLatestFill(intentId, "4.5");

        transitionService.terminate(
                intentId,
                OrderIntentStatus.PARTIALLY_COMPLETED,
                "잔여 수량 체결 불가",
                terminalAt,
                new BigDecimal("4.5"),
                ACTOR);

        var stored = orderIntentRepository.findById(intentId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderIntentStatus.PARTIALLY_COMPLETED);
        assertThat(stored.getTerminalReason()).isEqualTo("잔여 수량 체결 불가");
        assertThat(stored.getTerminalAt()).isEqualTo(terminalAt);
        assertThat(stored.getFinalFilledQuantity()).isEqualByComparingTo("4.5");
        assertThat(stored.getRemainingQuantity()).isEqualByComparingTo("5.5");
        assertLedger(intentId, OrderIntentStatus.ACTIVE, OrderIntentStatus.PARTIALLY_COMPLETED, 5);
    }

    @Test
    void invalidTransitionLeavesNoAuditOrOutbox() {
        var intentId = proposedIntent("10");

        assertThatThrownBy(() -> transitionService.startRevalidation(intentId, ACTOR))
                .isInstanceOf(IllegalStateException.class);

        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(count("order_intent_audit_logs", intentId)).isZero();
        assertThat(count("order_intent_outbox_events", intentId)).isZero();
    }

    @Test
    void outboxInsertFailureRollsBackStateAndAudit() {
        var intentId = proposedIntent("10");
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_order_intent_outbox_insert()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced outbox failure';
                END;
                $$;
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_fail_order_intent_outbox_insert
                BEFORE INSERT ON order_intent_outbox_events
                FOR EACH ROW
                EXECUTE FUNCTION fail_order_intent_outbox_insert()
                """);

        assertThatThrownBy(() -> transitionService.approve(intentId, ACTOR))
                .isInstanceOf(RuntimeException.class);

        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(count("order_intent_audit_logs", intentId)).isZero();
        assertThat(count("order_intent_outbox_events", intentId)).isZero();
    }

    @Test
    void optimisticLockLoserRollsBackAuditAndOutbox() throws Exception {
        var intentId = proposedIntent("10");
        REPOSITORY_FIND_GATE.enable(intentId, 2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<AttemptResult> transition = () -> approveWithService(intentId);
            var first = executor.submit(transition);
            var second = executor.submit(transition);

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(AttemptResult.COMMITTED, AttemptResult.OPTIMISTIC_LOCK_REJECTED);
        }

        var stored = orderIntentRepository.findById(intentId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderIntentStatus.APPROVED);
        assertThat(stored.getVersion()).isEqualTo(1);
        assertLedger(intentId, OrderIntentStatus.PROPOSED, OrderIntentStatus.APPROVED, 1);
    }

    private UUID activeIntent(String quantity) {
        var intentId = proposedIntent(quantity);
        transitionService.approve(intentId, ACTOR);
        transitionService.startRevalidation(intentId, ACTOR);
        transitionService.markSubmissionPending(intentId, ACTOR);
        transitionService.activate(intentId, ACTOR);
        return intentId;
    }

    private UUID proposedIntent(String quantity) {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(intentId, accountId, new BigDecimal(quantity)));
        return intentId;
    }

    private void insertClosedBrokerOrderWithLatestFill(UUID intentId, String filledQuantity) {
        var brokerOrderId = UUID.randomUUID();
        var brokerAccountId = jdbcTemplate.queryForObject(
                "SELECT broker_account_id FROM order_intents WHERE id = ?",
                UUID.class,
                intentId);
        jdbcTemplate.update("""
                INSERT INTO broker_orders (
                    id, order_intent_id, broker_account_id, broker_order_id, client_order_id, status
                ) VALUES (?, ?, ?, ?, ?, 'CANCELED')
                """, brokerOrderId, intentId, brokerAccountId,
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        jdbcTemplate.update("""
                INSERT INTO execution_snapshots (id, broker_order_id, filled_quantity, captured_at)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), brokerOrderId, new BigDecimal(filledQuantity), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void assertLedger(
            UUID intentId,
            OrderIntentStatus fromStatus,
            OrderIntentStatus toStatus,
            int expectedRows
    ) {
        assertThat(count("order_intent_audit_logs", intentId)).isEqualTo(expectedRows);
        assertThat(count("order_intent_outbox_events", intentId)).isEqualTo(expectedRows);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) = 1
                  FROM order_intent_audit_logs a
                  JOIN order_intent_outbox_events o ON o.order_intent_id = a.order_intent_id
                   AND o.from_status = a.from_status
                   AND o.to_status = a.to_status
                   AND o.actor = a.actor
                   AND o.created_at = a.occurred_at
                 WHERE a.order_intent_id = ?
                   AND a.from_status = ?
                   AND a.to_status = ?
                   AND o.event_type = 'OrderIntentStatusChanged'
                   AND o.payload ->> 'orderIntentId' = ?
                   AND o.payload ->> 'fromStatus' = ?
                   AND o.payload ->> 'toStatus' = ?
                """, Boolean.class,
                intentId, fromStatus.name(), toStatus.name(),
                intentId.toString(), fromStatus.name(), toStatus.name())).isTrue();
    }

    private long count(String table, UUID intentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE order_intent_id = ?",
                Long.class,
                intentId);
    }

    private AttemptResult approveWithService(UUID intentId) {
        try {
            transitionService.approve(intentId, ACTOR);
            return AttemptResult.COMMITTED;
        } catch (RuntimeException exception) {
            if (hasOptimisticLockCause(exception)) {
                return AttemptResult.OPTIMISTIC_LOCK_REJECTED;
            }
            throw exception;
        }
    }

    private boolean hasOptimisticLockCause(Throwable throwable) {
        for (var current = throwable; current != null; current = current.getCause()) {
            if (current instanceof OptimisticLockException
                    || current instanceof OptimisticLockingFailureException
                    || current instanceof ObjectOptimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }

    enum AttemptResult {
        COMMITTED,
        OPTIMISTIC_LOCK_REJECTED
    }

    @TestConfiguration
    static class RepositoryFindGateConfiguration {

        @Bean
        static BeanPostProcessor orderIntentRepositoryFindGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (!(bean instanceof OrderIntentRepository repository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            bean.getClass().getClassLoader(),
                            new Class<?>[]{OrderIntentRepository.class},
                            (proxy, method, args) -> {
                                var result = method.invoke(repository, args);
                                if ("findById".equals(method.getName())
                                        && args != null
                                        && args.length == 1
                                        && args[0] instanceof UUID id
                                        && result instanceof Optional<?> optional
                                        && optional.orElse(null) instanceof OrderIntent) {
                                    REPOSITORY_FIND_GATE.afterFindById(id);
                                }
                                return result;
                            });
                }
            };
        }
    }

    static class RepositoryFindGate {
        private UUID intentId;
        private CountDownLatch loaded;
        private CountDownLatch release;

        synchronized void enable(UUID intentId, int parties) {
            this.intentId = intentId;
            this.loaded = new CountDownLatch(parties);
            this.release = new CountDownLatch(1);
        }

        synchronized void disable() {
            intentId = null;
            loaded = null;
            release = null;
        }

        void afterFindById(UUID foundIntentId) throws InterruptedException {
            CountDownLatch localLoaded;
            CountDownLatch localRelease;
            synchronized (this) {
                if (!foundIntentId.equals(intentId)) {
                    return;
                }
                localLoaded = loaded;
                localRelease = release;
            }
            localLoaded.countDown();
            if (localLoaded.await(5, TimeUnit.SECONDS)) {
                localRelease.countDown();
            }
            if (!localRelease.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent repository find gate timed out");
            }
        }
    }
}
