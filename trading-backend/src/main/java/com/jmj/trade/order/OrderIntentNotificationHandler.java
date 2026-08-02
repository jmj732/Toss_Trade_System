package com.jmj.trade.order;

import com.jmj.trade.inbox.InboxConsumer;
import com.jmj.trade.notification.NotificationEventType;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The first real subscriber of the order outbox relay: turns a terminal order-intent status
 * transition into a user-facing {@code ORDER_RESULT} notification, reusing the existing
 * {@code notification_outbox_events} path rather than any new notification infrastructure.
 *
 * <p>Which transitions notify is deliberately the same rule the domain already applied inline
 * before the relay existed: only a transition to a terminal status, and only when the intent has
 * an owning user. Non-terminal transitions are internal workflow steps with nothing to tell the
 * user, and the notification renderer only knows how to render {@code ORDER_RESULT}. The intent's
 * user and symbol are not carried on the outbox row (its write contract is frozen), so they are
 * read back from {@code order_intents}. The inbox ledger keeps this consumer from re-running for an
 * already-processed event, and {@link NotificationOutboxWriter}'s dedup on
 * {@code (event_type, source_id)} keeps emission idempotent even if the inbox row is ever lost.
 */
final class OrderIntentNotificationHandler implements InboxConsumer<OrderOutboxRecord> {

    static final String CONSUMER_NAME = "order-intent-notification";

    private final JdbcTemplate jdbc;
    private final NotificationOutboxWriter notifications;

    OrderIntentNotificationHandler(JdbcTemplate jdbc, NotificationOutboxWriter notifications) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public void consume(OrderOutboxRecord record) {
        var toStatus = OrderIntentStatus.valueOf(record.column("to_status"));
        if (!OrderIntent.isTerminal(toStatus)) {
            return;
        }
        var intentId = record.orderIntentId();
        var owners = jdbc.queryForList(
                "SELECT user_id, symbol FROM order_intents WHERE id = ?", intentId);
        if (owners.isEmpty()) {
            return;
        }
        var owner = owners.get(0);
        var userId = (UUID) owner.get("user_id");
        if (userId == null) {
            return;
        }
        var symbol = (String) owner.get("symbol");
        var terminalReason = record.column("terminal_reason");
        var occurredAt = record.createdAt();
        notifications.emit(
                userId,
                NotificationEventType.ORDER_RESULT,
                intentId,
                OrderIntent.orderResultPayload(intentId, symbol, toStatus, terminalReason),
                occurredAt == null ? Instant.now() : occurredAt);
    }
}
