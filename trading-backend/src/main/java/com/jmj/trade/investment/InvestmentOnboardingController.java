package com.jmj.trade.investment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/investment/onboarding")
final class InvestmentOnboardingController {

    private final InvestmentOnboardingService service;

    InvestmentOnboardingController(InvestmentOnboardingService service) {
        this.service = service;
    }

    @GetMapping("/readiness")
    InvestmentOnboardingService.ReadinessView readiness(Principal principal, @RequestParam String ticker) {
        return service.readiness(userId(principal), ticker);
    }

    @PostMapping("/register")
    InvestmentOnboardingService.RegistrationView register(
            Principal principal,
            @RequestBody RegistrationRequest request
    ) {
        return service.register(userId(principal), request == null ? null : request.ticker());
    }

    @ExceptionHandler(InvestmentException.class)
    ResponseEntity<PublicError> investment(InvestmentException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "INVESTMENT_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "INVESTMENT_STATE_NOT_FOUND");
            case CONFLICT -> error(HttpStatus.CONFLICT, "INVESTMENT_DECISION_CONFLICT");
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<PublicError> malformedJson() {
        return error(HttpStatus.BAD_REQUEST, "INVESTMENT_INPUT_INVALID");
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record RegistrationRequest(String ticker) {
    }

    record PublicError(String code) {
    }
}
