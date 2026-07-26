package com.jmj.trade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_intents")
public class OrderIntent {

    @Id
    private UUID id;

    @Column(name = "broker_account_id", nullable = false)
    private UUID brokerAccountId;

    @Column(nullable = false, precision = 28, scale = 10)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderIntentStatus status;

    @Column(name = "terminal_reason", length = 80)
    private String terminalReason;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "final_filled_quantity", precision = 28, scale = 10)
    private BigDecimal finalFilledQuantity;

    @Column(name = "remaining_quantity", precision = 28, scale = 10)
    private BigDecimal remainingQuantity;

    @Version
    @Column(nullable = false)
    private long version;

    protected OrderIntent() {
    }

    private OrderIntent(UUID id, UUID brokerAccountId, BigDecimal quantity) {
        this.id = id;
        this.brokerAccountId = brokerAccountId;
        this.quantity = quantity;
        this.status = OrderIntentStatus.PROPOSED;
    }

    public static OrderIntent proposed(UUID id, UUID brokerAccountId, BigDecimal quantity) {
        return new OrderIntent(id, brokerAccountId, quantity);
    }

    public void approve() {
        status = OrderIntentStatus.APPROVED;
    }

    public UUID getId() {
        return id;
    }

    public OrderIntentStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }
}
