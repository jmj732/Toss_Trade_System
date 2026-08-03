package com.jmj.trade.observability;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations/readiness")
public final class OperationalReadinessController {

    private final OperationalReadinessService readiness;

    OperationalReadinessController(OperationalReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping
    OperationalReadinessService.ReadinessView read(Principal principal) {
        return readiness.read(userId(principal));
    }

    @PostMapping("/provider-check")
    OperationalReadinessService.ReadinessView check(
            Principal principal,
            @RequestBody ProviderCheckRequest request
    ) {
        if (request == null) throw new InvalidInputException();
        var userId = userId(principal);
        return readiness.checkProviders(userId, request.symbol(), "READINESS:" + userId);
    }

    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<ErrorBody> invalidInput() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorBody("PRODUCTION_READINESS_INPUT_INVALID"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> invalidArgument() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorBody("PRODUCTION_READINESS_INPUT_INVALID"));
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new InvalidInputException();
        }
    }

    record ProviderCheckRequest(String symbol) {
    }

    record ErrorBody(String code) {
    }

    static final class InvalidInputException extends RuntimeException {
    }
}
