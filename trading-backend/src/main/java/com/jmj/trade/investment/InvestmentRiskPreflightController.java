package com.jmj.trade.investment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/investment/risk")
public class InvestmentRiskPreflightController {

    private final InvestmentRiskPreflightService preflight;

    public InvestmentRiskPreflightController(InvestmentRiskPreflightService preflight) {
        this.preflight = preflight;
    }

    @PostMapping("/preflight")
    public InvestmentRiskPreflightService.PreflightView preflight(
            Principal principal,
            @RequestBody InvestmentRiskPreflightService.PreflightInput input
    ) {
        return preflight.preflight(userId(principal), input);
    }

    @ExceptionHandler(InvestmentException.class)
    ResponseEntity<PublicError> investment(InvestmentException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "INVESTMENT_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "INVESTMENT_STATE_NOT_FOUND");
            case CONFLICT -> error(HttpStatus.CONFLICT, "INVESTMENT_STATE_CONFLICT");
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

    record PublicError(String code) {
    }
}
