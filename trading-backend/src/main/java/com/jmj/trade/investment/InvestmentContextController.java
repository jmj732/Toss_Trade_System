package com.jmj.trade.investment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/investment")
final class InvestmentContextController {

    private final InvestmentContextService service;

    InvestmentContextController(InvestmentContextService service) {
        this.service = service;
    }

    @GetMapping("/context")
    InvestmentContextService.ContextView context(Principal principal) {
        return service.context(userId(principal));
    }

    @PutMapping("/securities/{ticker}/thesis")
    InvestmentContextService.ThesisView thesis(
            Principal principal,
            @PathVariable String ticker,
            @RequestBody InvestmentContextService.ThesisInput input
    ) {
        return service.putThesis(userId(principal), ticker, input);
    }

    @GetMapping("/decisions")
    List<InvestmentContextService.DecisionView> decisions(
            Principal principal,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return service.decisionLedger(userId(principal), limit);
    }

    @PostMapping("/decisions")
    InvestmentContextService.DecisionView decision(
            Principal principal,
            @RequestBody InvestmentContextService.DecisionInput input
    ) {
        return service.recordDecision(userId(principal), input);
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

    record PublicError(String code) {
    }
}
