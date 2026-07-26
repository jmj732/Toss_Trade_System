package com.jmj.trade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_submission_outbox_events")
public class OrderSubmissionOutboxEvent {

    @Id
    private UUID id;

    @Column(name = "order_intent_id", nullable = false)
    private UUID orderIntentId;

    @Column(name = "aggregate_type", nullable = false, length = 80)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(nullable = false, length = 200)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    protected OrderSubmissionOutboxEvent() {
    }

    public OrderSubmissionOutboxEvent(
            UUID id,
            UUID orderIntentId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String actor,
            String payload,
            Instant createdAt
    ) {
        this.id = requireId(id, "id");
        this.orderIntentId = requireId(orderIntentId, "orderIntentId");
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = requireId(aggregateId, "aggregateId");
        this.eventType = requireText(eventType, "eventType");
        this.actor = requireText(actor, "actor");
        this.payload = requireJsonObject(payload);
        this.createdAt = requireInstant(createdAt, "createdAt");
    }

    private static UUID requireId(UUID value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static Instant requireInstant(Instant value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static String requireJsonObject(String value) {
        var text = requireText(value, "payload").strip();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderIntentId() {
        return orderIntentId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActor() {
        return actor;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
