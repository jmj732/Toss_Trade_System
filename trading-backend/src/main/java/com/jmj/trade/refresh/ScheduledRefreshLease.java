package com.jmj.trade.refresh;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-writer lease for the scheduled refresh sweep. The lease row expires so a crashed instance
 * cannot block later sweeps.
 */
final class ScheduledRefreshLease {

    static final String NAME = "portfolio-refresh";

    private final JdbcTemplate jdbc;
    private final Duration ttl;

    ScheduledRefreshLease(JdbcTemplate jdbc, Duration ttl) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (!ttl.isPositive()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    boolean acquire(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        return jdbc.update("""
                INSERT INTO scheduled_refresh_leases (name, owner, acquired_at, expires_at)
                VALUES (
                    ?, ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + CAST(? AS bigint) * INTERVAL '1 millisecond'
                )
                ON CONFLICT (name) DO UPDATE
                   SET owner = EXCLUDED.owner,
                       acquired_at = EXCLUDED.acquired_at,
                       expires_at = EXCLUDED.expires_at
                 WHERE scheduled_refresh_leases.expires_at <= CURRENT_TIMESTAMP
                """, NAME, owner, ttl.toMillis()) == 1;
    }

    void release(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        jdbc.update(
                "DELETE FROM scheduled_refresh_leases WHERE name = ? AND owner = ?",
                NAME,
                owner);
    }
}
