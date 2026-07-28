package com.jmj.trade.account;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/broker-connections/{connectionId}/portfolio-history")
public class PortfolioHistoryController {

    private final PortfolioHistoryService history;

    PortfolioHistoryController(PortfolioHistoryService history) {
        this.history = history;
    }

    @GetMapping
    PortfolioHistoryService.PortfolioHistoryView read(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "90") int maxPoints
    ) {
        return history.read(userId(principal), connectionId, from, to, maxPoints);
    }

    @ExceptionHandler(PortfolioHistoryException.class)
    ResponseEntity<PublicError> portfolioHistory(PortfolioHistoryException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "PORTFOLIO_HISTORY_INPUT_INVALID");
        };
    }

    @ExceptionHandler(InvalidUserException.class)
    ResponseEntity<PublicError> invalidUser() {
        return error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new InvalidUserException();
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    private static final class InvalidUserException extends RuntimeException {
    }

    record PublicError(String code) {
    }
}
