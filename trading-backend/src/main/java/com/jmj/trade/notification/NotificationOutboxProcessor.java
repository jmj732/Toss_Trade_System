package com.jmj.trade.notification;

import com.jmj.trade.inbox.InboxLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * The inbox side of the outbox pattern: consumes {@code notification_outbox_events} rows and
 * idempotently creates the corresponding {@code notifications} row. Each row is claimed and
 * finished in its own transaction via {@code SELECT ... FOR UPDATE SKIP LOCKED}, so concurrent
 * invocations (two scheduler ticks overlapping, or multiple instances) partition the backlog
 * instead of double-processing.
 *
 * <p>This single-consumer relay runs through the shared {@link InboxLedger}: completion is recorded
 * against {@link #CONSUMER_NAME} in the same transaction as the notification insert, so re-claiming
 * a row after a crash (claimed and notified, but {@code processed_at} never got written) skips the
 * consumer entirely rather than doing the work again. {@code ON CONFLICT (outbox_event_id)
 * DO NOTHING} on {@code notifications} remains as a second line of defense for the case where the
 * inbox row itself is lost.
 */
final class NotificationOutboxProcessor {

    static final String CONSUMER_NAME = "notification-center";

    private static final Logger LOG = LoggerFactory.getLogger(NotificationOutboxProcessor.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final InboxLedger inbox;

    NotificationOutboxProcessor(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            InboxLedger inbox
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    ProcessResult process(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        var processed = 0;
        for (var i = 0; i < batchSize; i++) {
            var handled = transaction.execute(status -> processOne());
            if (handled == null) {
                break;
            }
            if (handled) {
                processed++;
            }
        }
        return new ProcessResult(processed);
    }

    private Boolean processOne() {
        var row = jdbc.query("""
                SELECT id, user_id, event_type, source_id, payload::text, occurred_at
                  FROM notification_outbox_events
                 WHERE processed_at IS NULL
                 ORDER BY created_at, id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNum) -> new OutboxRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getObject("source_id", UUID.class),
                resultSet.getString("payload"),
                resultSet.getObject("occurred_at", OffsetDateTime.class)
        )).stream().findFirst().orElse(null);
        if (row == null) {
            return null;
        }

        if (inbox.isProcessed(CONSUMER_NAME, row.id())) {
            // A prior life already created this notification; the crash only lost processed_at.
            // Re-stamp it so the row leaves the backlog, without touching notifications again.
            jdbc.update(
                    "UPDATE notification_outbox_events SET processed_at = ? WHERE id = ?",
                    now(), row.id());
            return true;
        }

        Rendered rendered;
        try {
            rendered = render(row.eventType(), row.payload());
        } catch (RuntimeException exception) {
            // An unrenderable row (malformed payload, unknown event type) must not block every
            // other user's notifications forever: mark it processed without creating a
            // notification, rather than letting the sweep re-select the same row on every tick.
            LOG.atError()
                    .addKeyValue("operation", "notification_outbox_render")
                    .addKeyValue("outbox_event_id", row.id())
                    .addKeyValue("event_type", row.eventType())
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .setCause(exception)
                    .log("dropping unrenderable notification outbox row");
            jdbc.update(
                    "UPDATE notification_outbox_events SET processed_at = ? WHERE id = ?",
                    now(), row.id());
            return false;
        }
        jdbc.update("""
                INSERT INTO notifications (
                    id, outbox_event_id, user_id, type, title, body, source_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (outbox_event_id) DO NOTHING
                """,
                UUID.randomUUID(), row.id(), row.userId(), row.eventType(),
                rendered.title(), rendered.body(), row.sourceId(), now());
        inbox.markProcessed(CONSUMER_NAME, row.id(), row.eventType());
        jdbc.update(
                "UPDATE notification_outbox_events SET processed_at = ? WHERE id = ?",
                now(), row.id());
        return true;
    }

    private Rendered render(String eventType, String payloadJson) {
        var payload = objectMapper.readTree(payloadJson);
        return switch (NotificationEventType.valueOf(eventType)) {
            case SYNC_SUCCEEDED -> new Rendered(
                    "Portfolio sync completed",
                    "Your portfolio data is up to date.");
            case SYNC_FAILED -> new Rendered(
                    "Portfolio sync failed",
                    "Sync failed (%s). Your latest portfolio data may be out of date."
                            .formatted(text(payload, "errorCode")));
            case ANALYSIS_SUCCEEDED -> new Rendered(
                    "Portfolio analysis completed",
                    "A new analysis result is available.");
            case ANALYSIS_FAILED -> new Rendered(
                    "Portfolio analysis failed",
                    "Analysis failed (%s).".formatted(text(payload, "errorCode")));
            case EVENT_CREATED -> new Rendered(
                    "New market event detected",
                    "%s affecting %s".formatted(
                            text(payload, "type"), symbols(payload.get("affectedSymbols"))));
            case ORDER_RESULT -> new Rendered(
                    "Order %s".formatted(text(payload, "status")),
                    "%s order for %s: %s".formatted(
                            text(payload, "status"), text(payload, "symbol"),
                            payload.get("terminalReason") == null
                                    || payload.get("terminalReason").isNull()
                                    ? "no additional reason"
                                    : payload.get("terminalReason").asText()));
            case ORDER_RECONCILIATION_MANUAL_REVIEW -> new Rendered(
                    "Order reconciliation needs manual review",
                    "An order attempt could not be reconciled automatically and the account is "
                            + "locked for new orders until an operator resolves it.");
            case PRODUCTION_READINESS_ALERT -> new Rendered(
                    "Production readiness needs attention",
                    "Provider credentials, freshness, scheduling, or safety gates are not ready.");
        };
    }

    private static String text(JsonNode payload, String field) {
        var value = payload.get(field);
        return value == null || value.isNull() ? "unknown" : value.asText();
    }

    private static String symbols(JsonNode symbolsNode) {
        if (symbolsNode == null || !symbolsNode.isArray() || symbolsNode.isEmpty()) {
            return "your portfolio";
        }
        var joined = new StringBuilder();
        for (var i = 0; i < symbolsNode.size(); i++) {
            if (i > 0) {
                joined.append(", ");
            }
            joined.append(symbolsNode.get(i).asText());
        }
        return joined.toString();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    record ProcessResult(int processed) {
    }

    private record OutboxRow(
            UUID id,
            UUID userId,
            String eventType,
            UUID sourceId,
            String payload,
            OffsetDateTime occurredAt
    ) {
    }

    private record Rendered(String title, String body) {
    }
}
