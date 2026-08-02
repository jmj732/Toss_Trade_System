package com.jmj.trade.observability;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.inbox.InboxConsumer;
import com.jmj.trade.inbox.InboxLedger;
import com.jmj.trade.order.OrderOutboxProcessor;
import com.jmj.trade.order.OrderOutboxRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ties the relay to the real {@code trade.outbox.backlog} / {@code trade.outbox.dead_letter}
 * gauges: after the relay runs the backlog converges to zero, and a dead-lettered row leaves the
 * backlog while showing up under the dead-letter gauge.
 */
@SpringBootTest(classes = TradingBackendApplication.class)
class OrderOutboxBacklogGaugeIntegrationTest extends PostgresIntegrationTest {

    private static final String INTENT_EVENT = "OrderIntentStatusChanged";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private InboxLedger inbox;

    private UUID intentId;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                TRUNCATE inbox_messages,
                         order_submission_outbox_events,
                         order_intent_outbox_events,
                         real_order_daily_reservations, real_order_account_allowlist, order_intents,
                         broker_accounts CASCADE
                """);
        var accountId = UUID.randomUUID();
        intentId = UUID.randomUUID();
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        jdbc.update("""
                INSERT INTO order_intents (id, broker_account_id, quantity, status)
                VALUES (?, ?, 1, 'PROPOSED')
                """, intentId, accountId);
    }

    @Test
    void backlogGaugeConvergesToZeroAfterTheRelayPublishes() {
        for (var i = 0; i < 3; i++) {
            insertIntentOutbox();
        }
        var registry = new SimpleMeterRegistry();
        new WorkflowMetrics(jdbc, Duration.ofMinutes(15)).bindTo(registry);
        assertThat(backlogGauge(registry)).isEqualTo(3);

        publishingProcessor().process(10);

        assertThat(backlogGauge(registry)).isZero();
    }

    @Test
    void deadLetteredRowLeavesBacklogAndCountsUnderTheDeadLetterGauge() {
        insertIntentOutbox();
        var registry = new SimpleMeterRegistry();
        new WorkflowMetrics(jdbc, Duration.ofMinutes(15)).bindTo(registry);
        assertThat(backlogGauge(registry)).isEqualTo(1);

        failingProcessor().process(1);

        assertThat(backlogGauge(registry)).isZero();
        assertThat(deadLetterGauge(registry)).isEqualTo(1);
    }

    private OrderOutboxProcessor publishingProcessor() {
        // Empty consumer map: rows are simply published, exercising the backlog drain.
        return new OrderOutboxProcessor(jdbc, transactionManager, inbox, "order_intent_outbox_events",
                Map.of(), 5);
    }

    private OrderOutboxProcessor failingProcessor() {
        InboxConsumer<OrderOutboxRecord> failing = new InboxConsumer<>() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public void consume(OrderOutboxRecord record) {
                throw new IllegalStateException("boom");
            }
        };
        return new OrderOutboxProcessor(jdbc, transactionManager, inbox, "order_intent_outbox_events",
                Map.of(INTENT_EVENT, List.of(failing)), 1);
    }

    private double backlogGauge(SimpleMeterRegistry registry) {
        return registry.get("trade.outbox.backlog").tag("outbox", "order-intent").gauge().value();
    }

    private double deadLetterGauge(SimpleMeterRegistry registry) {
        return registry.get("trade.outbox.dead_letter").tag("outbox", "order-intent").gauge().value();
    }

    private void insertIntentOutbox() {
        jdbc.update("""
                INSERT INTO order_intent_outbox_events (
                    id, order_intent_id, event_type, from_status, to_status, actor, payload, created_at
                ) VALUES (?, ?, ?, 'ACTIVE', 'COMPLETED', 'test', CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), intentId, INTENT_EVENT,
                "{\"orderIntentId\":\"" + intentId + "\"}",
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
