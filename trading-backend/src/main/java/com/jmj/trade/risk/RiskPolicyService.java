package com.jmj.trade.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-user pre-trade risk limits: a current row (optimistic-concurrency versioned, matching
 * {@code event_reviews}' current-state pattern) plus an append-only history of every accepted
 * edit. A user with no row yet is on the platform defaults — version {@code 0} — which is a
 * permanent, valid state, not a placeholder pending backfill.
 */
@Service
public class RiskPolicyService {

    // Comfortably inside NUMERIC(28,10)'s ~18 integer digits, with headroom to spare — a
    // sanity ceiling, not an attempt to use the column's full range.
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000");
    private static final int AMOUNT_SCALE = 10;
    private static final int CONCENTRATION_SCALE = 4;

    private final JdbcTemplate jdbc;
    private final RiskPolicySnapshot defaults;

    RiskPolicyService(
            JdbcTemplate jdbc,
            @Value("${pre-trade-risk.max-order-amount.krw:10000000}") BigDecimal defaultMaxOrderAmountKrw,
            @Value("${pre-trade-risk.max-order-amount.usd:10000}") BigDecimal defaultMaxOrderAmountUsd,
            @Value("${pre-trade-risk.max-quantity:100}") BigDecimal defaultMaxQuantity,
            @Value("${pre-trade-risk.max-concentration:0.25}") BigDecimal defaultMaxConcentration
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        var normalized = normalize(new RiskPolicyInput(
                defaultMaxOrderAmountKrw, defaultMaxOrderAmountUsd,
                defaultMaxQuantity, defaultMaxConcentration));
        this.defaults = new RiskPolicySnapshot(
                0, normalized.maxOrderAmountKrw(), normalized.maxOrderAmountUsd(),
                normalized.maxQuantity(), normalized.maxConcentration(), false);
    }

    public RiskPolicySnapshot current(UUID userId) {
        requireId(userId);
        return jdbc.query("""
                SELECT version, max_order_amount_krw, max_order_amount_usd,
                       max_quantity, max_concentration
                  FROM risk_policies
                 WHERE user_id = ?
                """, (resultSet, rowNum) -> new RiskPolicySnapshot(
                resultSet.getLong("version"),
                resultSet.getBigDecimal("max_order_amount_krw"),
                resultSet.getBigDecimal("max_order_amount_usd"),
                resultSet.getBigDecimal("max_quantity"),
                resultSet.getBigDecimal("max_concentration"),
                true
        ), userId).stream().findFirst().orElse(defaults);
    }

    @Transactional
    public RiskPolicySnapshot update(UUID userId, Long expectedVersion, RiskPolicyInput input, String actor) {
        requireId(userId);
        requireActor(actor);
        if (expectedVersion == null) {
            throw new RiskPolicyException(RiskPolicyException.Code.INVALID_INPUT);
        }
        RiskPolicyInput normalized;
        try {
            normalized = normalize(input);
        } catch (IllegalArgumentException exception) {
            throw new RiskPolicyException(RiskPolicyException.Code.INVALID_INPUT);
        }
        // FOR NO KEY UPDATE (not FOR UPDATE) serializes concurrent policy edits for this
        // same user without blocking unrelated FK-referencing inserts elsewhere (every
        // INSERT into a table with a users FK takes a FOR KEY SHARE lock on this row, which
        // FOR UPDATE would needlessly conflict with).
        lockUser(userId);
        var currentVersion = currentVersion(userId);
        if (currentVersion != expectedVersion) {
            throw new RiskPolicyException(RiskPolicyException.Code.VERSION_CONFLICT);
        }
        var nextVersion = currentVersion + 1;
        var now = now();
        jdbc.update("""
                INSERT INTO risk_policies (
                    user_id, version, max_order_amount_krw, max_order_amount_usd,
                    max_quantity, max_concentration, updated_at, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    version = EXCLUDED.version,
                    max_order_amount_krw = EXCLUDED.max_order_amount_krw,
                    max_order_amount_usd = EXCLUDED.max_order_amount_usd,
                    max_quantity = EXCLUDED.max_quantity,
                    max_concentration = EXCLUDED.max_concentration,
                    updated_at = EXCLUDED.updated_at,
                    updated_by = EXCLUDED.updated_by
                """, userId, nextVersion, normalized.maxOrderAmountKrw(), normalized.maxOrderAmountUsd(),
                normalized.maxQuantity(), normalized.maxConcentration(), now, actor);
        jdbc.update("""
                INSERT INTO risk_policy_history (
                    id, user_id, version, max_order_amount_krw, max_order_amount_usd,
                    max_quantity, max_concentration, changed_by, changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, nextVersion, normalized.maxOrderAmountKrw(),
                normalized.maxOrderAmountUsd(), normalized.maxQuantity(), normalized.maxConcentration(),
                actor, now);
        return new RiskPolicySnapshot(
                nextVersion, normalized.maxOrderAmountKrw(), normalized.maxOrderAmountUsd(),
                normalized.maxQuantity(), normalized.maxConcentration(), true);
    }

    public List<RiskPolicyHistoryEntry> history(UUID userId, int limit) {
        requireId(userId);
        if (limit < 1 || limit > 100) {
            throw new RiskPolicyException(RiskPolicyException.Code.INVALID_INPUT);
        }
        return jdbc.query("""
                SELECT version, max_order_amount_krw, max_order_amount_usd,
                       max_quantity, max_concentration, changed_by, changed_at
                  FROM risk_policy_history
                 WHERE user_id = ?
                 ORDER BY version DESC
                 LIMIT ?
                """, (resultSet, rowNum) -> new RiskPolicyHistoryEntry(
                resultSet.getLong("version"),
                resultSet.getBigDecimal("max_order_amount_krw"),
                resultSet.getBigDecimal("max_order_amount_usd"),
                resultSet.getBigDecimal("max_quantity"),
                resultSet.getBigDecimal("max_concentration"),
                resultSet.getString("changed_by"),
                resultSet.getObject("changed_at", OffsetDateTime.class).toInstant()
        ), userId, limit);
    }

    private void lockUser(UUID userId) {
        if (jdbc.queryForList(
                "SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE", UUID.class, userId).isEmpty()) {
            throw new RiskPolicyException(RiskPolicyException.Code.INVALID_USER);
        }
    }

    private long currentVersion(UUID userId) {
        return jdbc.queryForList("SELECT version FROM risk_policies WHERE user_id = ?", Long.class, userId)
                .stream().findFirst().orElse(0L);
    }

    /**
     * Rounds every field to the column scale it will be stored at BEFORE validating range, so
     * a value that rounds down to zero (or up past a boundary) is rejected using the number
     * that will actually reach the database — not the pre-rounding number the caller sent —
     * and the normalized values (not the raw input) are what get persisted and echoed back.
     */
    private static RiskPolicyInput normalize(RiskPolicyInput input) {
        if (input == null) {
            throw new IllegalArgumentException("policy input is required");
        }
        return new RiskPolicyInput(
                amount(input.maxOrderAmountKrw(), "maxOrderAmountKrw"),
                amount(input.maxOrderAmountUsd(), "maxOrderAmountUsd"),
                amount(input.maxQuantity(), "maxQuantity"),
                concentration(input.maxConcentration()));
    }

    private static BigDecimal amount(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        var scaled = value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0 || scaled.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0 and at most " + MAX_AMOUNT);
        }
        return scaled;
    }

    private static BigDecimal concentration(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("maxConcentration is required");
        }
        var scaled = value.setScale(CONCENTRATION_SCALE, RoundingMode.HALF_UP);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0 || scaled.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("maxConcentration must be greater than 0 and at most 1");
        }
        return scaled;
    }

    private static void requireId(UUID userId) {
        if (userId == null) {
            throw new RiskPolicyException(RiskPolicyException.Code.INVALID_USER);
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public record RiskPolicyInput(
            BigDecimal maxOrderAmountKrw,
            BigDecimal maxOrderAmountUsd,
            BigDecimal maxQuantity,
            BigDecimal maxConcentration
    ) {
    }

    public record RiskPolicySnapshot(
            long version,
            BigDecimal maxOrderAmountKrw,
            BigDecimal maxOrderAmountUsd,
            BigDecimal maxQuantity,
            BigDecimal maxConcentration,
            boolean customized
    ) {
    }

    public record RiskPolicyHistoryEntry(
            long version,
            BigDecimal maxOrderAmountKrw,
            BigDecimal maxOrderAmountUsd,
            BigDecimal maxQuantity,
            BigDecimal maxConcentration,
            String changedBy,
            Instant changedAt
    ) {
    }
}
