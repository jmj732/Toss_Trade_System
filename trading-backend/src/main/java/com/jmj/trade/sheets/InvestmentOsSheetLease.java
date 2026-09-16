package com.jmj.trade.sheets;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL lease; one row name serializes all application instances. */
public final class InvestmentOsSheetLease {
    static final String NAME = "investment-os-sheet-sync";

    private final JdbcTemplate jdbc;
    private final Duration ttl;

    public InvestmentOsSheetLease(JdbcTemplate jdbc, Duration ttl) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("ttl must be positive");
    }

    public boolean acquire(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        return jdbc.update("""
                INSERT INTO scheduled_refresh_leases (name, owner, acquired_at, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + CAST(? AS bigint) * INTERVAL '1 millisecond')
                ON CONFLICT (name) DO UPDATE
                   SET owner = EXCLUDED.owner,
                       acquired_at = EXCLUDED.acquired_at,
                       expires_at = EXCLUDED.expires_at
                 WHERE scheduled_refresh_leases.expires_at <= CURRENT_TIMESTAMP
                """, NAME, owner, ttl.toMillis()) == 1;
    }

    public void release(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        jdbc.update("DELETE FROM scheduled_refresh_leases WHERE name = ? AND owner = ?", NAME, owner);
    }
}
