package com.jmj.trade.dashboard;

import com.jmj.trade.order.OrderIntentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/broker-connections/{connectionId}/dashboard")
public class DashboardReadModelController {

    private final DashboardReadModelService dashboard;

    DashboardReadModelController(DashboardReadModelService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    DashboardReadModelService.DashboardView read(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam(name = "orderStatus", required = false) List<String> orderStatus
    ) {
        var userId = userId(principal);
        if (orderStatus == null || orderStatus.isEmpty()) {
            return dashboard.read(userId, connectionId);
        }
        return dashboard.read(userId, connectionId, parseStatuses(orderStatus));
    }

    private static Collection<OrderIntentStatus> parseStatuses(List<String> raw) {
        var statuses = new ArrayList<OrderIntentStatus>(raw.size());
        for (var value : raw) {
            try {
                statuses.add(OrderIntentStatus.valueOf(value));
            } catch (IllegalArgumentException | NullPointerException exception) {
                // 알 수 없는 값은 조용히 버리거나 기본값으로 대체하지 않고 400 으로 거절한다(D-03).
                throw new InvalidOrderStatusException();
            }
        }
        return statuses;
    }

    @ExceptionHandler(InvalidUserException.class)
    ResponseEntity<PublicError> invalidUser() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new PublicError("AUTHENTICATED_USER_INVALID"));
    }

    @ExceptionHandler(InvalidOrderStatusException.class)
    ResponseEntity<PublicError> invalidOrderStatus() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new PublicError("INVALID_ORDER_STATUS"));
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new InvalidUserException();
        }
    }

    private static final class InvalidUserException extends RuntimeException {
    }

    private static final class InvalidOrderStatusException extends RuntimeException {
    }

    record PublicError(String code) {
    }
}
