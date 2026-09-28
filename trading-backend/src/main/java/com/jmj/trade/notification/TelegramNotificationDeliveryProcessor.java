package com.jmj.trade.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Claims the durable Telegram delivery queue, sends outside the database transaction, then stamps
 * the result. A process crash after Telegram accepts a send but before SENT is stored can resend it.
 */
final class TelegramNotificationDeliveryProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramNotificationDeliveryProcessor.class);
    private static final long LEASE_MINUTES = 2;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TelegramDeliverySettings settings;
    private final TelegramMessageSender sender;

    TelegramNotificationDeliveryProcessor(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            TelegramDeliverySettings settings,
            TelegramMessageSender sender
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    int process(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (!settings.isReady()) {
            return 0;
        }
        var attempted = 0;
        for (var i = 0; i < batchSize; i++) {
            var row = transaction.execute(status -> claimOne());
            if (row == null) {
                break;
            }
            attempted++;
            try {
                sender.send(row.title() + "\n" + row.body());
                markSent(row.id());
            } catch (RuntimeException exception) {
                var reason = exception instanceof TelegramDeliveryException deliveryException
                        ? deliveryException.reason() : "CLIENT";
                markForRetry(row.id(), row.attemptCount(), reason);
                LOG.atWarn()
                        .addKeyValue("operation", "telegram_monitoring_delivery")
                        .addKeyValue("outbox_event_id", row.id())
                        .addKeyValue("reason", reason)
                        .log("monitoring alert Telegram delivery will retry");
            }
        }
        return attempted;
    }

    private DeliveryRow claimOne() {
        var timestamp = now();
        var row = jdbc.query("""
                SELECT d.outbox_event_id, d.attempt_count, n.title, n.body
                  FROM telegram_notification_deliveries d
                  JOIN notification_outbox_events e ON e.id = d.outbox_event_id
                  JOIN notifications n ON n.outbox_event_id = d.outbox_event_id
                 WHERE d.user_id = ? AND e.user_id = d.user_id
                   AND e.event_type = 'MONITORING_ALERT' AND n.type = 'MONITORING_ALERT'
                   AND ((d.status = 'PENDING' AND d.next_attempt_at <= ?)
                     OR (d.status = 'IN_FLIGHT' AND d.lease_until <= ?))
                 ORDER BY d.created_at, d.outbox_event_id
                 LIMIT 1
                 FOR UPDATE OF d SKIP LOCKED
                """, (resultSet, rowNum) -> new DeliveryRow(
                resultSet.getObject("outbox_event_id", UUID.class),
                resultSet.getInt("attempt_count"),
                resultSet.getString("title"),
                resultSet.getString("body")), settings.targetUserId(), timestamp, timestamp)
                .stream().findFirst().orElse(null);
        if (row == null) {
            return null;
        }
        var leaseUntil = timestamp.plusMinutes(LEASE_MINUTES);
        var updated = jdbc.update("""
                UPDATE telegram_notification_deliveries
                   SET status = 'IN_FLIGHT', attempt_count = attempt_count + 1,
                       lease_until = ?, last_error = NULL, updated_at = ?
                 WHERE outbox_event_id = ?
                """, leaseUntil, timestamp, row.id());
        return updated == 1 ? new DeliveryRow(
                row.id(), row.attemptCount() + 1, row.title(), row.body()) : null;
    }

    private void markSent(UUID id) {
        var timestamp = now();
        jdbc.update("""
                UPDATE telegram_notification_deliveries
                   SET status = 'SENT', sent_at = ?, lease_until = NULL, last_error = NULL,
                       updated_at = ?
                 WHERE outbox_event_id = ? AND status = 'IN_FLIGHT'
                """, timestamp, timestamp, id);
    }

    private void markForRetry(UUID id, int attemptCount, String reason) {
        var timestamp = now();
        var retryMinutes = Math.min(60, 1L << Math.min(attemptCount - 1, 6));
        jdbc.update("""
                UPDATE telegram_notification_deliveries
                   SET status = 'PENDING', next_attempt_at = ?, lease_until = NULL,
                       last_error = ?, updated_at = ?
                 WHERE outbox_event_id = ? AND status = 'IN_FLIGHT'
                """, timestamp.plusMinutes(retryMinutes), reason, timestamp, id);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private record DeliveryRow(UUID id, int attemptCount, String title, String body) {
    }
}
