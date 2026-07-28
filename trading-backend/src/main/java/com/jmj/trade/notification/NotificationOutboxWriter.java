package com.jmj.trade.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes a notification outbox row in whatever transaction is already active on the calling
 * thread — a plain bean method call, not a separate service boundary, so it participates in
 * the same commit as the domain event that triggered it (JPA {@code @Transactional} or a
 * {@code TransactionTemplate} block both work). {@code ON CONFLICT DO NOTHING} on
 * {@code (event_type, source_id)} makes emission itself idempotent if a caller is ever invoked
 * twice for the same domain event. The payload is a plain map serialized here, rather than a
 * caller-built JSON string, so free-text fields (order symbols, terminal reasons) can never
 * produce malformed JSON.
 */
@Component
public final class NotificationOutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationOutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void emit(
            UUID userId,
            NotificationEventType eventType,
            UUID sourceId,
            Map<String, Object> payload,
            Instant occurredAt
    ) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        jdbc.update("""
                INSERT INTO notification_outbox_events (
                    id, user_id, event_type, source_id, payload, occurred_at, created_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (event_type, source_id) DO NOTHING
                """,
                UUID.randomUUID(), userId, eventType.name(), sourceId, encode(payload),
                offset(occurredAt), now());
    }

    private String encode(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("notification payload serialization failed", exception);
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
