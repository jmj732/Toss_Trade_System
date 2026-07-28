package com.jmj.trade.notification;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    List<NotificationService.NotificationView> list(
            Principal principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return notifications.list(userId(principal), unreadOnly, limit);
    }

    @GetMapping("/unread-count")
    UnreadCount unreadCount(Principal principal) {
        return new UnreadCount(notifications.unreadCount(userId(principal)));
    }

    @PostMapping("/{notificationId}/read")
    NotificationService.NotificationView markRead(
            Principal principal,
            @PathVariable UUID notificationId
    ) {
        return notifications.markRead(userId(principal), notificationId);
    }

    @ExceptionHandler(NotificationException.class)
    ResponseEntity<PublicError> notification(NotificationException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "NOTIFICATION_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND");
        };
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new NotificationException(NotificationException.Code.INVALID_USER);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record UnreadCount(long count) {
    }

    record PublicError(String code) {
    }
}
