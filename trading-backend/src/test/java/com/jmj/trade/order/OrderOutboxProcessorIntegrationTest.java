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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the generic relay mechanics against both order outbox tables with lightweight test
 * consumers, independent of the opt-in bean wiring (the processors are constructed directly).
 */
@SpringBootTest(classes = TradingBackendApplication.class)
class OrderOutboxProcessorIntegrationTest extends PostgresIntegrationTest {

    private static final String INTENT_EVENT = "OrderIntentStatusChanged";
    private static final String SUBMISSION_EVENT = "OrderSubmissionAttempted";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private InboxLedger inbox;

    private UUID intentId;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS relay_test_sink (outbox_event_id UUID PRIMARY KEY)");
        jdbc.execute("""
                TRUNCATE relay_test_sink,
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
    void publishesAnUnpublishedIntentRowAndStampsPublishedAt() {
        var id = insertIntentOutbox("COMPLETED", "체결 완료");

        var result = intentProcessor(sink(), 5).process(10);

        assertThat(result.published()).isEqualTo(1);
        assertThat(publishedAt("order_intent_outbox_events", id)).isNotNull();
        assertThat(sinkCount()).isEqualTo(1);
    }

    @Test
    void publishesAnUnpublishedSubmissionRowAndStampsPublishedAt() {
        var id = insertSubmissionOutbox();

        var result = submissionProcessor(Map.of(SUBMISSION_EVENT, List.of(sink())), 5).process(10);

        assertThat(result.published()).isEqualTo(1);
        assertThat(publishedAt("order_submission_outbox_events", id)).isNotNull();
        assertThat(sinkCount()).isEqualTo(1);
    }

    @Test
    void reclaimingAfterACrashDoesNotDuplicateDownstreamRecords() {
        var id = insertIntentOutbox("COMPLETED", "체결 완료");
        assertThat(intentProcessor(sink(), 5).process(10).published()).isEqualTo(1);
        assertThat(sinkCount()).isEqualTo(1);

        // Simulate a crash between the handler side effect and the published_at write.
        jdbc.update("UPDATE order_intent_outbox_events SET published_at = NULL WHERE id = ?", id);

        assertThat(intentProcessor(sink(), 5).process(10).published()).isEqualTo(1);
        assertThat(sinkCount()).isEqualTo(1);
        assertThat(publishedAt("order_intent_outbox_events", id)).isNotNull();
    }

    @Test
    void aHandlerExceptionLeavesTheRowUnpublishedAndOnlyAdvancesAttempts() {
        var id = insertIntentOutbox("COMPLETED", "체결 완료");

        var result = intentProcessor(failing("boom"), 5).process(1);

        assertThat(result.published()).isZero();
        assertThat(publishedAt("order_intent_outbox_events", id)).isNull();
        assertThat(failedAt("order_intent_outbox_events", id)).isNull();
        assertThat(attempts("order_intent_outbox_events", id)).isEqualTo(1);
        assertThat(sinkCount()).isZero();
    }

    @Test
    void reachingTheAttemptCapDeadLettersTheRowAndStopsReclaimingIt() {
        var id = insertIntentOutbox("COMPLETED", "체결 완료");

        assertThat(intentProcessor(failing("boom"), 1).process(1).published()).isZero();

        assertThat(attempts("order_intent_outbox_events", id)).isEqualTo(1);
        assertThat(failedAt("order_intent_outbox_events", id)).isNotNull();
        assertThat(lastError("order_intent_outbox_events", id)).isNotBlank();

        // Dead-lettered rows are excluded by the unpublished partial index, so a later sweep is a
        // no-op: no further attempts, still unpublished.
        assertThat(intentProcessor(failing("boom"), 1).process(10).published()).isZero();
        assertThat(attempts("order_intent_outbox_events", id)).isEqualTo(1);
        assertThat(publishedAt("order_intent_outbox_events", id)).isNull();
    }

    @Test
    void anEventTypeWithNoSubscriberIsStillPublishedAndLeavesTheBacklog() {
        var id = insertSubmissionOutbox();

        // Empty consumer map: nothing subscribes to SUBMISSION_EVENT.
        var result = submissionProcessor(Map.of(), 5).process(10);

        assertThat(result.published()).isEqualTo(1);
        assertThat(publishedAt("order_submission_outbox_events", id)).isNotNull();
        assertThat(backlog("order_submission_outbox_events")).isZero();
    }

    @Test
    void lastErrorRecordsOnlyTheExceptionTypeAndNeverSensitivePayload() {
        var secret = "ACCT-9999-8888 password=hunter2";
        var id = insertIntentOutbox("COMPLETED", "체결 완료");

        intentProcessor(failing(secret), 1).process(1);

        var lastError = lastError("order_intent_outbox_events", id);
        assertThat(lastError).contains("IllegalStateException");
        assertThat(lastError).doesNotContain("9999").doesNotContain("hunter2").doesNotContain("ACCT");
    }

    private InboxConsumer<OrderOutboxRecord> sink() {
        return namedConsumer("sink", record -> jdbc.update(
                "INSERT INTO relay_test_sink (outbox_event_id) VALUES (?) ON CONFLICT DO NOTHING",
                record.id()));
    }

    private InboxConsumer<OrderOutboxRecord> failing(String message) {
        return namedConsumer("failing", record -> {
            throw new IllegalStateException(message);
        });
    }

    private static InboxConsumer<OrderOutboxRecord> namedConsumer(
            String name, java.util.function.Consumer<OrderOutboxRecord> body) {
        return new InboxConsumer<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void consume(OrderOutboxRecord record) {
                body.accept(record);
            }
        };
    }

    private OrderOutboxProcessor intentProcessor(
            InboxConsumer<OrderOutboxRecord> consumer, int maxAttempts) {
        return new OrderOutboxProcessor(jdbc, transactionManager, inbox, "order_intent_outbox_events",
                Map.of(INTENT_EVENT, List.of(consumer)), maxAttempts);
    }

    private OrderOutboxProcessor submissionProcessor(
            Map<String, List<InboxConsumer<OrderOutboxRecord>>> consumers, int maxAttempts) {
        return new OrderOutboxProcessor(jdbc, transactionManager, inbox,
                "order_submission_outbox_events", consumers, maxAttempts);
    }

    private UUID insertIntentOutbox(String toStatus, String terminalReason) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_intent_outbox_events (
                    id, order_intent_id, event_type, from_status, to_status, actor,
                    terminal_reason, payload, created_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, 'test', ?, CAST(? AS jsonb), ?)
                """, id, intentId, INTENT_EVENT, toStatus, terminalReason,
                "{\"orderIntentId\":\"" + intentId + "\"}", now());
        return id;
    }

    private UUID insertSubmissionOutbox() {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_submission_outbox_events (
                    id, order_intent_id, aggregate_type, aggregate_id, event_type, actor,
                    payload, created_at
                ) VALUES (?, ?, 'SubmissionAttempt', ?, ?, 'test', CAST(? AS jsonb), ?)
                """, id, intentId, UUID.randomUUID(), SUBMISSION_EVENT,
                "{\"orderIntentId\":\"" + intentId + "\"}", now());
        return id;
    }

    private OffsetDateTime publishedAt(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT published_at FROM " + table + " WHERE id = ?", OffsetDateTime.class, id);
    }

    private OffsetDateTime failedAt(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT failed_at FROM " + table + " WHERE id = ?", OffsetDateTime.class, id);
    }

    private String lastError(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT last_error FROM " + table + " WHERE id = ?", String.class, id);
    }

    private int attempts(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT attempts FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private long backlog(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE published_at IS NULL AND failed_at IS NULL",
                Long.class);
    }

    private long sinkCount() {
        return jdbc.queryForObject("SELECT count(*) FROM relay_test_sink", Long.class);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
