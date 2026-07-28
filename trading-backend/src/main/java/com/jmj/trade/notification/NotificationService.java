package com.jmj.trade.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class NotificationService {

    private final JdbcTemplate jdbc;

    NotificationService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    List<NotificationView> list(UUID userId, boolean unreadOnly, int limit) {
        requireId(userId);
        if (limit < 1 || limit > 100) {
            throw new NotificationException(NotificationException.Code.INVALID_INPUT);
        }
        return jdbc.query("""
                SELECT id, type, title, body, source_id, created_at, read_at
                  FROM notifications
                 WHERE user_id = ?
                   AND (? = false OR read_at IS NULL)
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, (resultSet, rowNum) -> new NotificationView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("type"),
                resultSet.getString("title"),
                resultSet.getString("body"),
                resultSet.getObject("source_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("read_at", OffsetDateTime.class)
        ), userId, unreadOnly, limit);
    }

    long unreadCount(UUID userId) {
        requireId(userId);
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Long.class, userId);
    }

    NotificationView markRead(UUID userId, UUID notificationId) {
        requireId(userId);
        requireId(notificationId);
        var rows = jdbc.query("""
                UPDATE notifications
                   SET read_at = COALESCE(read_at, ?)
                 WHERE id = ? AND user_id = ?
                RETURNING id, type, title, body, source_id, created_at, read_at
                """, (resultSet, rowNum) -> new NotificationView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("type"),
                resultSet.getString("title"),
                resultSet.getString("body"),
                resultSet.getObject("source_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("read_at", OffsetDateTime.class)
        ), now(), notificationId, userId);
        return rows.stream().findFirst().orElseThrow(
                () -> new NotificationException(NotificationException.Code.NOT_FOUND));
    }

    private static void requireId(UUID id) {
        if (id == null) {
            throw new NotificationException(NotificationException.Code.INVALID_USER);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    record NotificationView(
            UUID id,
            String type,
            String title,
            String body,
            UUID sourceId,
            OffsetDateTime createdAt,
            OffsetDateTime readAt
    ) {
    }
}
