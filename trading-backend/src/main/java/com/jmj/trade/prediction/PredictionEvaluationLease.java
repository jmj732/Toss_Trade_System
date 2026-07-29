package com.jmj.trade.prediction;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public final class PredictionEvaluationLease {

    static final String NAME = "prediction-evaluation";

    private final JdbcTemplate jdbc;
    private final Duration ttl;

    public PredictionEvaluationLease(JdbcTemplate jdbc, Duration ttl) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (!ttl.isPositive()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    boolean acquire(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        return jdbc.update("""
                INSERT INTO prediction_evaluation_leases (name, owner, acquired_at, expires_at)
                VALUES (
                    ?, ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + CAST(? AS bigint) * INTERVAL '1 millisecond'
                )
                ON CONFLICT (name) DO UPDATE
                   SET owner = EXCLUDED.owner,
                       acquired_at = EXCLUDED.acquired_at,
                       expires_at = EXCLUDED.expires_at
                 WHERE prediction_evaluation_leases.expires_at <= CURRENT_TIMESTAMP
                """, NAME, owner, ttl.toMillis()) == 1;
    }

    void release(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        jdbc.update(
                "DELETE FROM prediction_evaluation_leases WHERE name = ? AND owner = ?",
                NAME,
                owner);
    }
}
