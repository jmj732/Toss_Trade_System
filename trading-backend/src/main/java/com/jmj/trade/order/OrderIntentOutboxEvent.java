package com.jmj.trade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_intent_outbox_events")
public class OrderIntentOutboxEvent {

    @Id
    private UUID id;

    @Column(name = "order_intent_id", nullable = false)
    private UUID orderIntentId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 40)
    private OrderIntentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 40)
    private OrderIntentStatus toStatus;

    @Column(nullable = false, length = 200)
    private String actor;

    @Column(name = "terminal_reason", length = 80)
    private String terminalReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    protected OrderIntentOutboxEvent() {
    }

    public static OrderIntentOutboxEvent statusChanged(
            UUID orderIntentId,
            OrderIntentStatus fromStatus,
            OrderIntentStatus toStatus,
            String actor,
            String terminalReason,
            Instant createdAt
    ) {
        return new OrderIntentOutboxEvent(
                UUID.randomUUID(),
                orderIntentId,
                "OrderIntentStatusChanged",
                fromStatus,
                toStatus,
                actor,
                terminalReason,
                """
                        {"orderIntentId":"%s","fromStatus":"%s","toStatus":"%s"}"""
                        .formatted(orderIntentId, fromStatus, toStatus),
                createdAt);
    }

    public OrderIntentOutboxEvent(
            UUID id,
            UUID orderIntentId,
            String eventType,
            OrderIntentStatus fromStatus,
            OrderIntentStatus toStatus,
            String actor,
            String terminalReason,
            String payload,
            Instant createdAt
    ) {
        this.id = id;
        this.orderIntentId = orderIntentId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.terminalReason = terminalReason;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public UUID getOrderIntentId() {
        return orderIntentId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public int getAttempts() {
        return attempts;
    }
}
