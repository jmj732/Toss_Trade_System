package com.jmj.trade.intelligence.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

final class MarketEventIngestionLease {

    static final String NAME = "market-event-ingestion";

    private final JdbcTemplate jdbc;
    private final Duration ttl;

    MarketEventIngestionLease(JdbcTemplate jdbc, Duration ttl) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (!ttl.isPositive()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    boolean acquire(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        return jdbc.update("""
                INSERT INTO market_event_ingestion_leases (name, owner, acquired_at, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + CAST(? AS bigint) * INTERVAL '1 millisecond')
                ON CONFLICT (name) DO UPDATE
                   SET owner = EXCLUDED.owner,
                       acquired_at = EXCLUDED.acquired_at,
                       expires_at = EXCLUDED.expires_at
                 WHERE market_event_ingestion_leases.expires_at <= CURRENT_TIMESTAMP
                """, NAME, owner, ttl.toMillis()) == 1;
    }

    boolean renew(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        return jdbc.update("""
                UPDATE market_event_ingestion_leases
                 SET expires_at = CURRENT_TIMESTAMP
                       + CAST(? AS bigint) * INTERVAL '1 millisecond'
                 WHERE name = ? AND owner = ?
                   AND expires_at > CURRENT_TIMESTAMP
                """, ttl.toMillis(), NAME, owner) == 1;
    }

    void release(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        jdbc.update("DELETE FROM market_event_ingestion_leases WHERE name = ? AND owner = ?",
                NAME, owner);
    }
}
