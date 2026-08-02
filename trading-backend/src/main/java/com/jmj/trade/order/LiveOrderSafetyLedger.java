package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.Currency;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Live-only account ownership and atomic daily reservation gate. */
public final class LiveOrderSafetyLedger {

    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public LiveOrderSafetyLedger(JdbcTemplate jdbc, ZoneId zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    public Reservation reserve(
            UUID userId,
            UUID connectionId,
            UUID brokerAccountId,
            UUID orderIntentId,
            Currency currency,
            BigDecimal amount,
            Instant now
    ) {
        var rows = jdbc.query("""
                SELECT id, toss_account_seq, display_account_number,
                       daily_limit_krw, daily_limit_usd
                  FROM real_order_account_allowlist
                 WHERE user_id = ?
                   AND broker_connection_id = ?
                   AND broker_account_id = ?
                   AND enabled = TRUE
                 FOR UPDATE
                """, (rs, rowNumber) -> new AllowlistRow(
                rs.getObject("id", UUID.class),
                rs.getString("toss_account_seq"),
                rs.getString("display_account_number"),
                rs.getBigDecimal("daily_limit_krw"),
                rs.getBigDecimal("daily_limit_usd")),
                userId, connectionId, brokerAccountId);
        if (rows.size() != 1) {
            throw new IllegalStateException("live account mapping is missing or ambiguous");
        }
        var row = rows.getFirst();

        var existing = jdbc.query("""
                SELECT amount, currency, usage_date
                  FROM real_order_daily_reservations
                 WHERE order_intent_id = ?
                """, (rs, rowNumber) -> new ExistingReservation(
                rs.getBigDecimal("amount"),
                Currency.valueOf(rs.getString("currency")),
                rs.getObject("usage_date", LocalDate.class)), orderIntentId)
                .stream().findFirst();
        if (existing.isPresent()) {
            var saved = existing.get();
            if (saved.currency() != currency || saved.amount().compareTo(amount) != 0) {
                throw new IllegalStateException("live daily reservation conflicts with order");
            }
            return reservation(connectionId, row, saved.amount(), saved.usageDate());
        }

        var usageDate = now.atZone(zone).toLocalDate();
        var used = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM real_order_daily_reservations
                 WHERE allowlist_id = ? AND usage_date = ? AND currency = ?
                """, BigDecimal.class, row.id(), usageDate, currency.name());
        var limit = currency == Currency.KRW ? row.dailyLimitKrw() : row.dailyLimitUsd();
        if (used.add(amount).compareTo(limit) > 0) {
            throw new IllegalStateException("live daily order limit exceeded");
        }
        jdbc.update("""
                INSERT INTO real_order_daily_reservations (
                    id, allowlist_id, order_intent_id, usage_date, currency, amount, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), row.id(), orderIntentId, usageDate, currency.name(), amount, Timestamp.from(now));
        return reservation(connectionId, row, amount, usageDate);
    }

    public BrokerAccountRef resolve(UUID userId, UUID connectionId, UUID brokerAccountId) {
        var rows = jdbc.query("""
                SELECT toss_account_seq, display_account_number
                  FROM real_order_account_allowlist
                 WHERE user_id = ? AND broker_connection_id = ?
                   AND broker_account_id = ? AND enabled = TRUE
                """, (rs, row) -> new BrokerAccountRef(
                connectionId, rs.getString("toss_account_seq"), "LIVE", rs.getString("display_account_number")),
                userId, connectionId, brokerAccountId);
        if (rows.size() != 1) {
            throw new IllegalStateException("live account mapping is missing or ambiguous");
        }
        return rows.getFirst();
    }

    /** Rechecks the exact mapping and current daily ceiling without creating a reservation. */
    public BrokerAccountRef revalidate(
            UUID userId,
            UUID connectionId,
            UUID brokerAccountId,
            UUID orderIntentId,
            Currency currency,
            BigDecimal amount,
            Instant now
    ) {
        var rows = jdbc.query("""
                SELECT id, toss_account_seq, display_account_number,
                       daily_limit_krw, daily_limit_usd
                  FROM real_order_account_allowlist
                 WHERE user_id = ? AND broker_connection_id = ?
                   AND broker_account_id = ? AND enabled = TRUE
                 FOR UPDATE
                """, (rs, rowNumber) -> new AllowlistRow(
                rs.getObject("id", UUID.class),
                rs.getString("toss_account_seq"),
                rs.getString("display_account_number"),
                rs.getBigDecimal("daily_limit_krw"),
                rs.getBigDecimal("daily_limit_usd")),
                userId, connectionId, brokerAccountId);
        if (rows.size() != 1) {
            throw new IllegalStateException("live account mapping is missing or ambiguous");
        }
        var row = rows.getFirst();
        var usageDate = now.atZone(zone).toLocalDate();
        var used = orderIntentId == null
                ? jdbc.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)
                          FROM real_order_daily_reservations
                         WHERE allowlist_id = ? AND usage_date = ? AND currency = ?
                        """, BigDecimal.class, row.id(), usageDate, currency.name())
                : jdbc.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)
                          FROM real_order_daily_reservations
                         WHERE allowlist_id = ? AND usage_date = ? AND currency = ?
                           AND order_intent_id <> ?
                        """, BigDecimal.class, row.id(), usageDate, currency.name(), orderIntentId);
        var limit = currency == Currency.KRW ? row.dailyLimitKrw() : row.dailyLimitUsd();
        if (amount == null || amount.signum() <= 0 || used.add(amount).compareTo(limit) > 0) {
            throw new IllegalStateException("live daily order limit exceeded");
        }
        return new BrokerAccountRef(connectionId, row.tossAccountSeq(), "LIVE", row.displayAccountNumber());
    }

    private Reservation reservation(UUID connectionId, AllowlistRow row, BigDecimal amount, LocalDate usageDate) {
        return new Reservation(
                new BrokerAccountRef(
                        connectionId, row.tossAccountSeq(), "LIVE", row.displayAccountNumber()),
                amount, usageDate);
    }

    private record AllowlistRow(
            UUID id,
            String tossAccountSeq,
            String displayAccountNumber,
            BigDecimal dailyLimitKrw,
            BigDecimal dailyLimitUsd
    ) {
    }

    private record ExistingReservation(BigDecimal amount, Currency currency, LocalDate usageDate) {
    }

    public record Reservation(BrokerAccountRef account, BigDecimal amount, LocalDate usageDate) {
    }
}
