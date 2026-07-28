package com.jmj.trade.notification;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TradingBackendApplication.class)
class NotificationControllerIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime TIME =
            OffsetDateTime.of(2026, 7, 28, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE notifications, notification_outbox_events, users CASCADE");
        jdbc.update("INSERT INTO users (id) VALUES (?), (?)", USER_ID, OTHER_USER_ID);
    }

    @Test
    void listsOnlyTheCallersNotificationsNewestFirst() throws Exception {
        var older = insertNotification(USER_ID, "SYNC_SUCCEEDED", TIME, null);
        var newer = insertNotification(USER_ID, "ORDER_RESULT", TIME.plusMinutes(1), null);
        insertNotification(OTHER_USER_ID, "SYNC_SUCCEEDED", TIME.plusMinutes(2), null);

        mockMvc.perform(get("/api/v1/notifications").with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer.toString()))
                .andExpect(jsonPath("$[1].id").value(older.toString()));
    }

    @Test
    void unreadOnlyFilterExcludesReadNotifications() throws Exception {
        insertNotification(USER_ID, "SYNC_SUCCEEDED", TIME, TIME.plusSeconds(1));
        var unread = insertNotification(USER_ID, "ORDER_RESULT", TIME.plusMinutes(1), null);

        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(unread.toString()));
    }

    @Test
    void unreadCountReflectsOnlyTheCallersUnreadRows() throws Exception {
        insertNotification(USER_ID, "SYNC_SUCCEEDED", TIME, null);
        insertNotification(USER_ID, "ORDER_RESULT", TIME.plusMinutes(1), TIME.plusMinutes(2));
        insertNotification(OTHER_USER_ID, "SYNC_SUCCEEDED", TIME, null);

        mockMvc.perform(get("/api/v1/notifications/unread-count").with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markingReadIsIdempotentAndPreservesTheFirstReadTimestamp() throws Exception {
        var notificationId = insertNotification(USER_ID, "SYNC_SUCCEEDED", TIME, null);

        mockMvc.perform(post("/api/v1/notifications/{id}/read", notificationId)
                        .with(user(USER_ID.toString())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").isNotEmpty());
        var firstReadAt = jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE id = ?", OffsetDateTime.class, notificationId);

        mockMvc.perform(post("/api/v1/notifications/{id}/read", notificationId)
                        .with(user(USER_ID.toString())).with(csrf()))
                .andExpect(status().isOk());

        var secondReadAt = jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE id = ?", OffsetDateTime.class, notificationId);
        org.assertj.core.api.Assertions.assertThat(secondReadAt).isEqualTo(firstReadAt);
    }

    @Test
    void markingAnotherUsersNotificationReadReturnsNotFound() throws Exception {
        var notificationId = insertNotification(OTHER_USER_ID, "SYNC_SUCCEEDED", TIME, null);

        mockMvc.perform(post("/api/v1/notifications/{id}/read", notificationId)
                        .with(user(USER_ID.toString())).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    private UUID insertNotification(
            UUID userId,
            String type,
            OffsetDateTime createdAt,
            OffsetDateTime readAt
    ) {
        var outboxId = UUID.randomUUID();
        var notificationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_outbox_events (
                    id, user_id, event_type, source_id, payload, occurred_at, created_at, processed_at
                ) VALUES (?, ?, ?, ?, '{}', ?, ?, ?)
                """, outboxId, userId, type, UUID.randomUUID(), createdAt, createdAt, createdAt);
        jdbc.update("""
                INSERT INTO notifications (
                    id, outbox_event_id, user_id, type, title, body, source_id, created_at, read_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, notificationId, outboxId, userId, type, "title", "body", UUID.randomUUID(),
                createdAt, readAt);
        return notificationId;
    }
}
