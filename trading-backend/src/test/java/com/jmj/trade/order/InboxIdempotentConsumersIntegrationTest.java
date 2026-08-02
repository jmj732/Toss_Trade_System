package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.inbox.InboxConsumer;
import com.jmj.trade.inbox.InboxLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The heart of the inbox delta: several consumers of one outbox event each run and record
 * completion independently, so one consumer's failure never rolls back another's committed work,
 * a re-delivered event skips already-finished consumers, and {@code published_at} is stamped only
 * once every consumer has finished.
 */
@SpringBootTest(classes = TradingBackendApplication.class)
class InboxIdempotentConsumersIntegrationTest extends PostgresIntegrationTest {

    private static final String INTENT_EVENT = "OrderIntentStatusChanged";
    private static final String CONSUMER_A = "consumer-a";
    private static final String CONSUMER_B = "consumer-b";

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
                CREATE TABLE IF NOT EXISTS relay_consumer_sink (
                    consumer_name VARCHAR(100),
                    event_id UUID,
                    PRIMARY KEY (consumer_name, event_id)
                )""");
        jdbc.execute("""
                TRUNCATE relay_consumer_sink,
                         inbox_messages,
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
    void twoConsumersEachProcessTheSameEventExactlyOnce() {
        var id = insertIntentOutbox();
        var a = new CountingConsumer(CONSUMER_A, false);
        var b = new CountingConsumer(CONSUMER_B, false);

        var result = processor(List.of(a, b)).process(10);

        assertThat(result.published()).isEqualTo(1);
        assertThat(a.invocations()).isEqualTo(1);
        assertThat(b.invocations()).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_A)).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_B)).isEqualTo(1);
        assertThat(inbox.isProcessed(CONSUMER_A, id)).isTrue();
        assertThat(inbox.isProcessed(CONSUMER_B, id)).isTrue();
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void aReDeliveredEventDoesNotRunAConsumerAgainOrDuplicateItsSideEffect() {
        var id = insertIntentOutbox();
        var a = new CountingConsumer(CONSUMER_A, false);
        assertThat(processor(List.of(a)).process(10).published()).isEqualTo(1);
        assertThat(a.invocations()).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_A)).isEqualTo(1);

        // Re-delivery: the row looks unpublished again but the consumer already has an inbox row.
        jdbc.update("UPDATE order_intent_outbox_events SET published_at = NULL WHERE id = ?", id);

        assertThat(processor(List.of(a)).process(10).published()).isEqualTo(1);
        assertThat(a.invocations()).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_A)).isEqualTo(1);
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void oneConsumerFailingDoesNotRollBackAnotherAndOnlyTheFailedOneRetries() {
        var id = insertIntentOutbox();
        var a = new CountingConsumer(CONSUMER_A, false);
        var b = new CountingConsumer(CONSUMER_B, true);

        var first = processor(List.of(a, b)).process(1);

        // A committed independently; B rolled back its own transaction and left the row unpublished.
        assertThat(first.published()).isZero();
        assertThat(a.invocations()).isEqualTo(1);
        assertThat(b.invocations()).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_A)).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_B)).isZero();
        assertThat(inbox.isProcessed(CONSUMER_A, id)).isTrue();
        assertThat(inbox.isProcessed(CONSUMER_B, id)).isFalse();
        assertThat(publishedAt(id)).isNull();
        assertThat(attempts(id)).isEqualTo(1);

        // Next tick with B healed: A is skipped (not re-run), only B runs, then the row publishes.
        var healedB = new CountingConsumer(CONSUMER_B, false);
        var second = processor(List.of(a, healedB)).process(1);

        assertThat(second.published()).isEqualTo(1);
        assertThat(a.invocations()).isEqualTo(1);
        assertThat(healedB.invocations()).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_A)).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_B)).isEqualTo(1);
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void aPartiallyProcessedRowStaysUnpublishedUntilEveryConsumerFinishes() {
        var id = insertIntentOutbox();
        var a = new CountingConsumer(CONSUMER_A, false);
        var failingB = new CountingConsumer(CONSUMER_B, true);

        processor(List.of(a, failingB)).process(1);
        assertThat(publishedAt(id)).isNull();
        assertThat(inbox.isProcessed(CONSUMER_A, id)).isTrue();
        assertThat(inbox.isProcessed(CONSUMER_B, id)).isFalse();

        var healedB = new CountingConsumer(CONSUMER_B, false);
        processor(List.of(a, healedB)).process(1);

        assertThat(publishedAt(id)).isNotNull();
        assertThat(a.invocations()).isEqualTo(1);
        assertThat(healedB.invocations()).isEqualTo(1);
    }

    @Test
    void losingTheInboxRowBeforePublishReRunsTheConsumerButKeepsOneDownstreamResult() {
        var id = insertIntentOutbox();
        var a = new CountingConsumer(CONSUMER_A, false);
        assertThat(processor(List.of(a)).process(10).published()).isEqualTo(1);
        assertThat(sinkCount(CONSUMER_A)).isEqualTo(1);

        // Simulate a crash before processed_at was durable: the inbox row and the publish are gone.
        jdbc.update("DELETE FROM inbox_messages WHERE consumer_name = ? AND event_id = ?",
                CONSUMER_A, id);
        jdbc.update("UPDATE order_intent_outbox_events SET published_at = NULL WHERE id = ?", id);

        assertThat(processor(List.of(a)).process(10).published()).isEqualTo(1);
        // Consumer ran a second time, but its downstream write is idempotent, so still one result.
        assertThat(a.invocations()).isEqualTo(2);
        assertThat(sinkCount(CONSUMER_A)).isEqualTo(1);
        assertThat(inbox.isProcessed(CONSUMER_A, id)).isTrue();
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void anEventTypeWithNoConsumersIsPublishedAndLeavesTheBacklog() {
        var id = insertIntentOutbox();

        var result = processor(List.of()).process(10);

        assertThat(result.published()).isEqualTo(1);
        assertThat(publishedAt(id)).isNotNull();
        assertThat(backlog()).isZero();
    }

    private OrderOutboxProcessor processor(List<InboxConsumer<OrderOutboxRecord>> consumers) {
        return new OrderOutboxProcessor(jdbc, transactionManager, inbox, "order_intent_outbox_events",
                Map.of(INTENT_EVENT, consumers), 5);
    }

    /** Records each invocation and writes an idempotent downstream row, optionally then throwing. */
    private final class CountingConsumer implements InboxConsumer<OrderOutboxRecord> {

        private final String name;
        private final boolean fail;
        private final AtomicInteger invocations = new AtomicInteger();

        private CountingConsumer(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void consume(OrderOutboxRecord record) {
            invocations.incrementAndGet();
            jdbc.update("""
                    INSERT INTO relay_consumer_sink (consumer_name, event_id)
                    VALUES (?, ?) ON CONFLICT DO NOTHING
                    """, name, record.id());
            if (fail) {
                throw new IllegalStateException("boom");
            }
        }

        private int invocations() {
            return invocations.get();
        }
    }

    private UUID insertIntentOutbox() {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_intent_outbox_events (
                    id, order_intent_id, event_type, from_status, to_status, actor,
                    terminal_reason, payload, created_at
                ) VALUES (?, ?, ?, 'ACTIVE', 'COMPLETED', 'test', '체결 완료', CAST(? AS jsonb), ?)
                """, id, intentId, INTENT_EVENT,
                "{\"orderIntentId\":\"" + intentId + "\"}", now());
        return id;
    }

    private OffsetDateTime publishedAt(UUID id) {
        return jdbc.queryForObject(
                "SELECT published_at FROM order_intent_outbox_events WHERE id = ?",
                OffsetDateTime.class, id);
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject(
                "SELECT attempts FROM order_intent_outbox_events WHERE id = ?", Integer.class, id);
    }

    private long sinkCount(String consumerName) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM relay_consumer_sink WHERE consumer_name = ?",
                Long.class, consumerName);
    }

    private long backlog() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM order_intent_outbox_events
                 WHERE published_at IS NULL AND failed_at IS NULL
                """, Long.class);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
