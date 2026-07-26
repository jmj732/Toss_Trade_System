package com.jmj.trade.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@IdClass(SubmissionIdempotencyKeyId.class)
@Table(name = "submission_idempotency_keys")
public class SubmissionIdempotencyKey {

    private static final Pattern CLIENT_ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,36}");

    @Id
    @Column(name = "broker_account_id", nullable = false)
    private UUID brokerAccountId;

    @Id
    @Column(name = "client_order_id", nullable = false, length = 36)
    private String clientOrderId;

    @Column(name = "order_intent_id", nullable = false)
    private UUID orderIntentId;

    @Column(name = "request_body_hash", nullable = false, length = 128)
    private String requestBodyHash;

    @Column(name = "idempotency_expires_at", nullable = false)
    private Instant idempotencyExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SubmissionIdempotencyKey() {
    }

    private SubmissionIdempotencyKey(
            UUID brokerAccountId,
            String clientOrderId,
            UUID orderIntentId,
            String requestBodyHash,
            Instant idempotencyExpiresAt,
            Instant createdAt
    ) {
        this.brokerAccountId = requireId(brokerAccountId, "brokerAccountId");
        this.clientOrderId = requireClientOrderId(clientOrderId);
        this.orderIntentId = requireId(orderIntentId, "orderIntentId");
        this.requestBodyHash = requireText(requestBodyHash, "requestBodyHash");
        this.idempotencyExpiresAt = requireInstant(idempotencyExpiresAt, "idempotencyExpiresAt");
        this.createdAt = requireInstant(createdAt, "createdAt");
        if (!this.idempotencyExpiresAt.equals(this.createdAt.plusSeconds(600))) {
            throw new IllegalArgumentException("idempotencyExpiresAt must be exactly 10 minutes after createdAt");
        }
    }

    public static SubmissionIdempotencyKey create(
            UUID brokerAccountId,
            String clientOrderId,
            UUID orderIntentId,
            String requestBodyHash,
            Instant idempotencyExpiresAt,
            Instant createdAt
    ) {
        return new SubmissionIdempotencyKey(
                brokerAccountId,
                clientOrderId,
                orderIntentId,
                requestBodyHash,
                idempotencyExpiresAt,
                createdAt);
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

    private static String requireClientOrderId(String value) {
        if (value == null || !CLIENT_ORDER_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("clientOrderId must match [A-Za-z0-9_-]{1,36}");
        }
        return value;
    }

    public UUID getBrokerAccountId() {
        return brokerAccountId;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public UUID getOrderIntentId() {
        return orderIntentId;
    }

    public String getRequestBodyHash() {
        return requestBodyHash;
    }

    public Instant getIdempotencyExpiresAt() {
        return idempotencyExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
