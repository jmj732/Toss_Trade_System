package com.jmj.trade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_intent_audit_logs")
public class OrderIntentAuditLog {

    @Id
    private UUID id;

    @Column(name = "order_intent_id", nullable = false)
    private UUID orderIntentId;

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

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OrderIntentAuditLog() {
    }

    public static OrderIntentAuditLog statusChanged(
            UUID orderIntentId,
            OrderIntentStatus fromStatus,
            OrderIntentStatus toStatus,
            String actor,
            String terminalReason,
            Instant occurredAt
    ) {
        return new OrderIntentAuditLog(
                UUID.randomUUID(),
                orderIntentId,
                fromStatus,
                toStatus,
                actor,
                terminalReason,
                occurredAt);
    }

    public OrderIntentAuditLog(
            UUID id,
            UUID orderIntentId,
            OrderIntentStatus fromStatus,
            OrderIntentStatus toStatus,
            String actor,
            String terminalReason,
            Instant occurredAt
    ) {
        this.id = id;
        this.orderIntentId = orderIntentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.terminalReason = terminalReason;
        this.occurredAt = occurredAt;
    }

    public UUID getOrderIntentId() {
        return orderIntentId;
    }

    public OrderIntentStatus getFromStatus() {
        return fromStatus;
    }

    public OrderIntentStatus getToStatus() {
        return toStatus;
    }
}
