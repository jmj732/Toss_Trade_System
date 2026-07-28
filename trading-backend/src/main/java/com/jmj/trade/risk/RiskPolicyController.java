package com.jmj.trade.risk;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/risk-policy")
public class RiskPolicyController {

    private final RiskPolicyService policies;

    RiskPolicyController(RiskPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    RiskPolicyService.RiskPolicySnapshot current(Principal principal) {
        return policies.current(userId(principal));
    }

    @GetMapping("/history")
    List<RiskPolicyService.RiskPolicyHistoryEntry> history(
            Principal principal,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return policies.history(userId(principal), limit);
    }

    @PutMapping
    RiskPolicyService.RiskPolicySnapshot update(Principal principal, @RequestBody UpdateRequest request) {
        var userId = userId(principal);
        return policies.update(
                userId,
                request.expectedVersion(),
                new RiskPolicyService.RiskPolicyInput(
                        request.maxOrderAmountKrw(), request.maxOrderAmountUsd(),
                        request.maxQuantity(), request.maxConcentration()),
                userId.toString());
    }

    @ExceptionHandler(RiskPolicyException.class)
    ResponseEntity<PublicError> riskPolicy(RiskPolicyException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "RISK_POLICY_INPUT_INVALID");
            case VERSION_CONFLICT -> error(HttpStatus.CONFLICT, "RISK_POLICY_VERSION_CONFLICT");
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<PublicError> malformedJson() {
        return error(HttpStatus.BAD_REQUEST, "RISK_POLICY_INPUT_INVALID");
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new RiskPolicyException(RiskPolicyException.Code.INVALID_USER);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record UpdateRequest(
            Long expectedVersion,
            BigDecimal maxOrderAmountKrw,
            BigDecimal maxOrderAmountUsd,
            BigDecimal maxQuantity,
            BigDecimal maxConcentration
    ) {
    }

    record PublicError(String code) {
    }
}
