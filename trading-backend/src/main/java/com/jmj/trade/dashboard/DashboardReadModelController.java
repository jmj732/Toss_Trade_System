package com.jmj.trade.dashboard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
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
            @PathVariable UUID connectionId
    ) {
        return dashboard.read(userId(principal), connectionId);
    }

    @ExceptionHandler(InvalidUserException.class)
    ResponseEntity<PublicError> invalidUser() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new PublicError("AUTHENTICATED_USER_INVALID"));
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

    record PublicError(String code) {
    }
}
