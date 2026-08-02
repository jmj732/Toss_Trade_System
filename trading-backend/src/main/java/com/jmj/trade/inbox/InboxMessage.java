package com.jmj.trade.inbox;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code inbox_messages}: the record that a named consumer has seen, and possibly
 * finished, a specific outbox event. {@link #processedAt()} is {@code null} until the consumer's
 * domain change committed together with this row; {@link #isProcessed()} is the question the relay
 * asks before deciding whether to run the consumer again.
 */
public record InboxMessage(
        UUID id,
        String consumerName,
        UUID eventId,
        String eventType,
        OffsetDateTime receivedAt,
        OffsetDateTime processedAt
) {

    public InboxMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(consumerName, "consumerName");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public boolean isProcessed() {
        return processedAt != null;
    }
}
