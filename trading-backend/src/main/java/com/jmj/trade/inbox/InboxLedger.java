package com.jmj.trade.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC access to {@code inbox_messages}. It owns no transaction of its own: every method runs in
 * whatever transaction is active on the calling thread, so a consumer's {@link #markProcessed}
 * commits together with the domain writes it accompanies.
 *
 * <p>{@link #markProcessed} is a plain insert, not {@code ON CONFLICT DO NOTHING}: a unique-key
 * violation means another worker recorded the same {@code (consumer_name, event_id)} first, and the
 * caller relies on that surfacing as a {@code DuplicateKeyException} so its transaction — including
 * the consumer's just-made domain change — rolls back instead of duplicating the side effect.
 */
@Component
public final class InboxLedger {

    private final JdbcTemplate jdbc;

    public InboxLedger(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Whether the named consumer has already finished this event (its inbox row is stamped). */
    public boolean isProcessed(String consumerName, UUID eventId) {
        return find(consumerName, eventId).map(InboxMessage::isProcessed).orElse(false);
    }

    public Optional<InboxMessage> find(String consumerName, UUID eventId) {
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(eventId, "eventId");
        return jdbc.query("""
                        SELECT id, consumer_name, event_id, event_type, received_at, processed_at
                          FROM inbox_messages
                         WHERE consumer_name = ? AND event_id = ?
                        """,
                (resultSet, rowNum) -> new InboxMessage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("consumer_name"),
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getObject("received_at", OffsetDateTime.class),
                        resultSet.getObject("processed_at", OffsetDateTime.class)),
                consumerName, eventId).stream().findFirst();
    }

    /**
     * Record that {@code consumerName} finished {@code eventId} in the current transaction. Throws
     * {@link org.springframework.dao.DuplicateKeyException} if a row for this
     * {@code (consumer_name, event_id)} already exists.
     */
    public void markProcessed(String consumerName, UUID eventId, String eventType) {
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        var now = now();
        jdbc.update("""
                INSERT INTO inbox_messages (
                    id, consumer_name, event_id, event_type, received_at, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), consumerName, eventId, eventType, now, now);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
