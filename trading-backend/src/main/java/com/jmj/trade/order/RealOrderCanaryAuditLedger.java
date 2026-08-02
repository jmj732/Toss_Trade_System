package com.jmj.trade.order;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Append-only, redacted evidence for one operator canary run. */
public final class RealOrderCanaryAuditLedger {

    private final JdbcTemplate jdbc;

    public RealOrderCanaryAuditLedger(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void record(
            UUID runId,
            int eventNumber,
            UUID userId,
            UUID connectionId,
            UUID brokerAccountId,
            UUID orderIntentId,
            UUID attemptId,
            String step,
            String outcome,
            String brokerResponseStatus,
            String brokerLifecycleStatus,
            boolean openOrdersComplete,
            boolean closedOrdersComplete,
            boolean unknown,
            String clientOrderId,
            String brokerOrderId,
            String reasonCode,
            Instant occurredAt
    ) {
        requireText(step, "step");
        requireText(outcome, "outcome");
        var evidence = "{\"openOrdersComplete\":" + openOrdersComplete
                + ",\"closedOrdersComplete\":" + closedOrdersComplete
                + ",\"unknown\":" + unknown
                + (reasonCode == null ? "" : ",\"reasonCode\":\"" + safe(reasonCode) + "\"")
                + "}";
        jdbc.update("""
                INSERT INTO real_order_canary_audit_events (
                    id, run_id, event_number, user_id, broker_connection_id, broker_account_id,
                    order_intent_id, submission_attempt_id, step, outcome, broker_response_status,
                    broker_lifecycle_status, open_orders_complete, closed_orders_complete, unknown,
                    client_order_id_hash, broker_order_id_hash, evidence, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(), runId, eventNumber, userId, connectionId, brokerAccountId,
                orderIntentId, attemptId, step, outcome, brokerResponseStatus, brokerLifecycleStatus,
                openOrdersComplete, closedOrdersComplete, unknown,
                hash(clientOrderId), hash(brokerOrderId), evidence, at(occurredAt));
    }

    static String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "occurredAt"), ZoneOffset.UTC);
    }
}
