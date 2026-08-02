package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/live-order-canary")
public final class RealOrderCanaryController {

    private final RealOrderCanaryService canary;

    RealOrderCanaryController(RealOrderCanaryService canary) {
        this.canary = canary;
    }

    @PostMapping("/preflight")
    RealOrderCanaryService.PreflightResult preflight(
            Principal principal,
            @AuthenticationPrincipal OidcUser oidcUser,
            @RequestBody OrderRequest request
    ) {
        if (request == null) {
            throw new CanaryInputException();
        }
        return canary.preflight(userId(principal), request.toOrder(), authenticatedAt(oidcUser));
    }

    @PostMapping("/run")
    RealOrderCanaryService.RunResult run(
            Principal principal,
            @AuthenticationPrincipal OidcUser oidcUser,
            @RequestHeader(value = "Idempotency-Key", required = false) String runKey,
            @RequestBody OrderRequest request
    ) {
        if (request == null || runKey == null || runKey.isBlank()) {
            throw new CanaryInputException();
        }
        var userId = userId(principal);
        return canary.run(userId, request.toOrder(), authenticatedAt(oidcUser), "CANARY:" + userId, runKey);
    }

    private static Instant authenticatedAt(OidcUser oidcUser) {
        return oidcUser == null || oidcUser.getIdToken() == null
                ? null : oidcUser.getIdToken().getAuthenticatedAt();
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new CanaryInputException();
        }
    }

    record OrderRequest(
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency
    ) {
        RealOrderCanaryService.CanaryOrder toOrder() {
            if (side == null || type == null || symbol == null || quantity == null || currency == null) {
                throw new CanaryInputException();
            }
            return new RealOrderCanaryService.CanaryOrder(side, type, symbol, quantity, limitPrice, currency);
        }
    }

    static final class CanaryInputException extends RuntimeException {
    }
}

@RestControllerAdvice(assignableTypes = RealOrderCanaryController.class)
final class RealOrderCanaryErrorHandler {

    @ExceptionHandler(RealOrderCanaryController.CanaryInputException.class)
    ResponseEntity<ErrorBody> invalidInput() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorBody("REAL_ORDER_CANARY_INPUT_INVALID"));
    }

    record ErrorBody(String code) {
    }
}
