package com.jmj.trade.order;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A single claimed row from one of the order outbox tables, exposed to an
 * {@link com.jmj.trade.inbox.InboxConsumer} as typed accessors over the raw column map. Both
 * {@code order_intent_outbox_events} and {@code order_submission_outbox_events} share the
 * common {@code id}, {@code event_type}, {@code order_intent_id}, {@code created_at} and
 * {@code payload} columns; table-specific columns (e.g. {@code to_status}) are reachable via
 * {@link #column(String)} so the relay itself stays agnostic to either table's shape.
 */
public final class OrderOutboxRecord {

    private final Map<String, Object> columns;

    OrderOutboxRecord(Map<String, Object> columns) {
        this.columns = Objects.requireNonNull(columns, "columns");
    }

    public UUID id() {
        return (UUID) columns.get("id");
    }

    public String eventType() {
        return (String) columns.get("event_type");
    }

    public UUID orderIntentId() {
        return (UUID) columns.get("order_intent_id");
    }

    /** The event payload as its raw JSON text (never a driver-specific JSON wrapper). */
    public String payload() {
        return (String) columns.get("payload_json");
    }

    public Instant createdAt() {
        return toInstant(columns.get("created_at"));
    }

    /** A table-specific column as text, or {@code null} when absent or SQL NULL. */
    public String column(String name) {
        var value = columns.get(name);
        return value == null ? null : value.toString();
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException(
                    "unsupported created_at type: " + value.getClass().getName());
        };
    }
}
