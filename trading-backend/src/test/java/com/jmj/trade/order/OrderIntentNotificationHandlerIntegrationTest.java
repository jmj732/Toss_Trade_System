package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.inbox.InboxLedger;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the first real relay subscriber: a terminal order-intent transition is
 * fanned out to a single {@code ORDER_RESULT} notification via the existing
 * {@code notification_outbox_events} path, idempotently, while non-terminal transitions notify
 * nothing.
 */
@SpringBootTest(classes = TradingBackendApplication.class)
class OrderIntentNotificationHandlerIntegrationTest extends PostgresIntegrationTest {

    private static final String INTENT_EVENT = "OrderIntentStatusChanged";
    private static final String SYMBOL = "005930";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrderIntentRepository orderIntentRepository;

    @Autowired
    private NotificationOutboxWriter notificationOutboxWriter;

    @Autowired
    private InboxLedger inbox;

    private UUID userId;
    private UUID intentId;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                TRUNCATE inbox_messages,
                         notifications,
                         notification_outbox_events,
                         order_submission_outbox_events,
                         order_intent_outbox_events,
                         real_order_daily_reservations, real_order_account_allowlist, order_intents,
                         broker_connections,
                         broker_accounts,
                         users CASCADE
                """);
        userId = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        var connectionId = UUID.randomUUID();
        intentId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?)
                """, connectionId, userId, new byte[17], new byte[12], now(), now());
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(
                intentId, accountId, userId, connectionId, OrderSide.BUY, OrderType.MARKET,
                SYMBOL, new BigDecimal("1"), null, Currency.KRW));
    }

    @Test
    void aTerminalTransitionEmitsASingleOrderResultNotification() {
        var outboxId = insertIntentOutbox("COMPLETED", "체결 완료");

        var result = processor().process(10);

        assertThat(result.published()).isEqualTo(1);
        assertThat(publishedAt(outboxId)).isNotNull();
        var stored = jdbc.queryForMap("""
                SELECT user_id, event_type, source_id, payload::text AS payload
                  FROM notification_outbox_events
                 WHERE event_type = 'ORDER_RESULT' AND source_id = ?
                """, intentId);
        assertThat(stored.get("user_id")).isEqualTo(userId);
        assertThat((String) stored.get("payload")).contains(SYMBOL).contains("COMPLETED");
    }

    @Test
    void reprocessingTheSameTerminalRowDoesNotDuplicateTheNotification() {
        var outboxId = insertIntentOutbox("REJECTED", "브로커 거부");
        assertThat(processor().process(10).published()).isEqualTo(1);
        assertThat(notificationCount()).isEqualTo(1);

        jdbc.update("UPDATE order_intent_outbox_events SET published_at = NULL WHERE id = ?", outboxId);

        assertThat(processor().process(10).published()).isEqualTo(1);
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    void aNonTerminalTransitionIsPublishedWithoutNotifying() {
        var outboxId = insertIntentOutbox("APPROVED", null);

        assertThat(processor().process(10).published()).isEqualTo(1);

        assertThat(publishedAt(outboxId)).isNotNull();
        assertThat(notificationCount()).isZero();
    }

    private OrderOutboxProcessor processor() {
        return new OrderOutboxProcessor(jdbc, transactionManager, inbox, "order_intent_outbox_events",
                Map.of(INTENT_EVENT,
                        List.of(new OrderIntentNotificationHandler(jdbc, notificationOutboxWriter))),
                5);
    }

    private UUID insertIntentOutbox(String toStatus, String terminalReason) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_intent_outbox_events (
                    id, order_intent_id, event_type, from_status, to_status, actor,
                    terminal_reason, payload, created_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, 'test', ?, CAST(? AS jsonb), ?)
                """, id, intentId, INTENT_EVENT, toStatus, terminalReason,
                "{\"orderIntentId\":\"" + intentId + "\"}", now());
        return id;
    }

    private OffsetDateTime publishedAt(UUID id) {
        return jdbc.queryForObject(
                "SELECT published_at FROM order_intent_outbox_events WHERE id = ?",
                OffsetDateTime.class, id);
    }

    private long notificationCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox_events WHERE source_id = ?",
                Long.class, intentId);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
