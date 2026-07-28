package com.jmj.trade.notification;

import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// concurrentSweepsPartitionTheBacklogWithoutDoubleProcessing runs 4 threads concurrently;
// the suite-wide default pool size (2, set in pom.xml) would serialize rather than deadlock
// them, but this keeps the test's own concurrency genuinely unconstrained by pool size.
@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = "spring.datasource.hikari.maximum-pool-size=4")
class NotificationOutboxProcessorIntegrationTest extends com.jmj.trade.PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private NotificationOutboxProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE notifications, notification_outbox_events, users CASCADE");
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @Test
    void processesAnUnprocessedRowIntoANotificationAndMarksItProcessed() {
        var outboxId = insertOutboxEvent(NotificationEventType.SYNC_SUCCEEDED, UUID.randomUUID(),
                "{}");

        var result = processor.process(10);

        assertThat(result.processed()).isEqualTo(1);
        var stored = jdbc.queryForMap(
                "SELECT title, body, user_id, read_at FROM notifications WHERE outbox_event_id = ?",
                outboxId);
        assertThat(stored.get("title")).isEqualTo("Portfolio sync completed");
        assertThat(stored.get("user_id")).isEqualTo(USER_ID);
        assertThat(stored.get("read_at")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT processed_at FROM notification_outbox_events WHERE id = ?",
                OffsetDateTime.class, outboxId)).isNotNull();
    }

    @Test
    void leavesAlreadyProcessedRowsUntouchedOnTheNextSweep() {
        insertOutboxEvent(NotificationEventType.SYNC_SUCCEEDED, UUID.randomUUID(), "{}");

        assertThat(processor.process(10).processed()).isEqualTo(1);
        assertThat(processor.process(10).processed()).isZero();
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void reprocessingAnOutboxRowAfterACrashBeforeTheProcessedAtWriteIsANoOp() {
        var outboxId = insertOutboxEvent(NotificationEventType.SYNC_SUCCEEDED, UUID.randomUUID(),
                "{}");
        assertThat(processor.process(10).processed()).isEqualTo(1);
        var notificationId = jdbc.queryForObject(
                "SELECT id FROM notifications WHERE outbox_event_id = ?", UUID.class, outboxId);

        // Simulate a crash between the notifications insert and the processed_at write: the
        // outbox row looks unprocessed again, so the sweep picks it up a second time.
        jdbc.update("UPDATE notification_outbox_events SET processed_at = NULL WHERE id = ?", outboxId);

        assertThat(processor.process(10).processed()).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM notifications WHERE outbox_event_id = ?", UUID.class, outboxId))
                .isEqualTo(notificationId);
        assertThat(jdbc.queryForObject(
                "SELECT processed_at FROM notification_outbox_events WHERE id = ?",
                OffsetDateTime.class, outboxId)).isNotNull();
    }

    @Test
    void batchSizeCapsHowManyRowsOneSweepClaims() {
        for (var i = 0; i < 5; i++) {
            insertOutboxEvent(NotificationEventType.SYNC_SUCCEEDED, UUID.randomUUID(), "{}");
        }

        assertThat(processor.process(3).processed()).isEqualTo(3);
        assertThat(processor.process(3).processed()).isEqualTo(2);
        assertThat(processor.process(3).processed()).isZero();
    }

    @Test
    void concurrentSweepsPartitionTheBacklogWithoutDoubleProcessing() throws InterruptedException {
        var total = 40;
        for (var i = 0; i < total; i++) {
            insertOutboxEvent(NotificationEventType.SYNC_SUCCEEDED, UUID.randomUUID(), "{}");
        }

        var start = new CountDownLatch(1);
        Callable<Integer> drain = () -> {
            start.await(5, TimeUnit.SECONDS);
            var processed = 0;
            NotificationOutboxProcessor.ProcessResult result;
            do {
                result = processor.process(4);
                processed += result.processed();
            } while (result.processed() > 0);
            return processed;
        };

        var futures = new ArrayList<Future<Integer>>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var i = 0; i < 4; i++) {
                futures.add(executor.submit(drain));
            }
            start.countDown();
            var sum = 0;
            for (var future : futures) {
                sum += get(future);
            }
            assertThat(sum).isEqualTo(total);
        }
        assertThat(count("notifications")).isEqualTo(total);
        assertThat(countWhere("notification_outbox_events", "processed_at IS NULL")).isZero();
    }

    @Test
    void aPoisonRowIsSkippedWithoutBlockingLaterValidRows() {
        // The payload column is JSONB, so malformed JSON syntax can never reach this table; the
        // realistic poison case is a row whose event_type does not match any known enum constant
        // (e.g. a stale row from a renamed/removed type, or a manual DB intervention).
        var poisonId = insertRawOutboxEvent("UNKNOWN_EVENT_TYPE", UUID.randomUUID(), "{}");
        var validId = insertOutboxEvent(NotificationEventType.SYNC_SUCCEEDED, UUID.randomUUID(), "{}");

        var result = processor.process(10);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT processed_at FROM notification_outbox_events WHERE id = ?",
                OffsetDateTime.class, poisonId)).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE outbox_event_id = ?",
                Long.class, poisonId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT processed_at FROM notification_outbox_events WHERE id = ?",
                OffsetDateTime.class, validId)).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE outbox_event_id = ?",
                Long.class, validId)).isEqualTo(1);
    }

    @Test
    void rendersEachEventTypeWithReadableTitleAndBody() {
        insertOutboxEvent(NotificationEventType.SYNC_FAILED, UUID.randomUUID(),
                """
                        {"connectionId":"%s","syncRunId":"%s","errorCode":"BROKER_TEMPORARY"}"""
                        .formatted(UUID.randomUUID(), UUID.randomUUID()));
        insertOutboxEvent(NotificationEventType.ANALYSIS_SUCCEEDED, UUID.randomUUID(),
                """
                        {"connectionId":"%s","analysisRunId":"%s"}"""
                        .formatted(UUID.randomUUID(), UUID.randomUUID()));
        insertOutboxEvent(NotificationEventType.ANALYSIS_FAILED, UUID.randomUUID(),
                """
                        {"connectionId":"%s","analysisRunId":"%s","errorCode":"ANALYSIS_UPSTREAM_UNAVAILABLE"}"""
                        .formatted(UUID.randomUUID(), UUID.randomUUID()));
        insertOutboxEvent(NotificationEventType.EVENT_CREATED, UUID.randomUUID(),
                """
                        {"connectionId":"%s","eventId":"%s","type":"EARNINGS","affectedSymbols":["NVDA","AAPL"]}"""
                        .formatted(UUID.randomUUID(), UUID.randomUUID()));
        insertOutboxEvent(NotificationEventType.ORDER_RESULT, UUID.randomUUID(),
                """
                        {"orderIntentId":"%s","symbol":"NVDA","status":"COMPLETED","terminalReason":null}"""
                        .formatted(UUID.randomUUID()));
        insertOutboxEvent(NotificationEventType.ORDER_RESULT, UUID.randomUUID(),
                """
                        {"orderIntentId":"%s","symbol":"NVDA","status":"REJECTED","terminalReason":"BROKER_REJECTED"}"""
                        .formatted(UUID.randomUUID()));

        assertThat(processor.process(10).processed()).isEqualTo(6);

        var rows = jdbc.queryForList("SELECT type, title, body FROM notifications ORDER BY created_at, id");
        assertThat(titleFor(rows, "SYNC_FAILED")).isEqualTo("Portfolio sync failed");
        assertThat(bodyFor(rows, "SYNC_FAILED")).contains("BROKER_TEMPORARY");
        assertThat(titleFor(rows, "ANALYSIS_SUCCEEDED")).isEqualTo("Portfolio analysis completed");
        assertThat(bodyFor(rows, "ANALYSIS_FAILED")).contains("ANALYSIS_UPSTREAM_UNAVAILABLE");
        assertThat(bodyFor(rows, "EVENT_CREATED")).contains("EARNINGS").contains("NVDA").contains("AAPL");
        assertThat(rows.stream().filter(row -> "ORDER_RESULT".equals(row.get("type")))
                .map(row -> (String) row.get("body")))
                .anySatisfy(body -> assertThat(body).contains("no additional reason"))
                .anySatisfy(body -> assertThat(body).contains("BROKER_REJECTED"));
    }

    private static String titleFor(List<Map<String, Object>> rows, String type) {
        return rows.stream().filter(row -> type.equals(row.get("type")))
                .map(row -> (String) row.get("title")).findFirst().orElseThrow();
    }

    private static String bodyFor(List<Map<String, Object>> rows, String type) {
        return rows.stream().filter(row -> type.equals(row.get("type")))
                .map(row -> (String) row.get("body")).findFirst().orElseThrow();
    }

    private static int get(Future<Integer> future) throws InterruptedException {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID insertOutboxEvent(NotificationEventType type, UUID sourceId, String payloadJson) {
        return insertRawOutboxEvent(type.name(), sourceId, payloadJson);
    }

    private UUID insertRawOutboxEvent(String eventType, UUID sourceId, String payloadJson) {
        var id = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO notification_outbox_events (
                    id, user_id, event_type, source_id, payload, occurred_at, created_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, id, USER_ID, eventType, sourceId, payloadJson, now, now);
        return id;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long countWhere(String table, String predicate) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class);
    }
}
