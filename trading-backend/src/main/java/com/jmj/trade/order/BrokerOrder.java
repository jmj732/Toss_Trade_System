package com.jmj.trade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

import java.util.UUID;

@Entity
@DynamicUpdate
@Table(name = "broker_orders")
public class BrokerOrder {

    @Id
    private UUID id;

    @Column(name = "order_intent_id", nullable = false)
    private UUID orderIntentId;

    @Column(name = "broker_account_id", nullable = false)
    private UUID brokerAccountId;

    @Column(name = "broker_order_id", nullable = false, length = 200)
    private String brokerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private BrokerOrderStatus status;

    @Column(name = "client_order_id", length = 36)
    private String clientOrderId;

    @Column(name = "replaces_broker_order_id")
    private UUID replacesBrokerOrderId;

    @Version
    @Column(nullable = false)
    private long version;

    protected BrokerOrder() {
    }

    private BrokerOrder(
            UUID id,
            UUID orderIntentId,
            UUID brokerAccountId,
            String brokerOrderId,
            String clientOrderId,
            BrokerOrderStatus status,
            UUID replacesBrokerOrderId
    ) {
        this.id = requireId(id, "id");
        this.orderIntentId = requireId(orderIntentId, "orderIntentId");
        this.brokerAccountId = requireId(brokerAccountId, "brokerAccountId");
        this.brokerOrderId = requireText(brokerOrderId, "brokerOrderId");
        this.clientOrderId = requireText(clientOrderId, "clientOrderId");
        this.status = requireStatus(status);
        this.replacesBrokerOrderId = replacesBrokerOrderId;
    }

    public static BrokerOrder confirmed(
            UUID id,
            UUID orderIntentId,
            UUID brokerAccountId,
            String brokerOrderId,
            String clientOrderId,
            BrokerOrderStatus status
    ) {
        return new BrokerOrder(id, orderIntentId, brokerAccountId, brokerOrderId, clientOrderId, status, null);
    }

    public void updateProjection(BrokerOrderStatus status) {
        this.status = requireStatus(status);
    }

    private static UUID requireId(UUID value, String fieldName) {
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

    private static BrokerOrderStatus requireStatus(BrokerOrderStatus value) {
        if (value == null) {
            throw new IllegalArgumentException("status is required");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderIntentId() {
        return orderIntentId;
    }

    public UUID getBrokerAccountId() {
        return brokerAccountId;
    }

    public String getBrokerOrderId() {
        return brokerOrderId;
    }

    public BrokerOrderStatus getStatus() {
        return status;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public UUID getReplacesBrokerOrderId() {
        return replacesBrokerOrderId;
    }

    public long getVersion() {
        return version;
    }
}
