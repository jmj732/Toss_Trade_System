package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/paper-orders")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class PaperOrderWorkflowController {

    private final PaperOrderWorkflowService workflow;

    PaperOrderWorkflowController(PaperOrderWorkflowService workflow) {
        this.workflow = workflow;
    }

    @PostMapping
    PaperOrderWorkflowService.OrderView propose(
            Principal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ProposeRequest request
    ) {
        if (request == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
        return workflow.propose(
                userId(principal),
                idempotencyKey,
                request.channel(),
                new PaperOrderWorkflowService.ProposeCommand(
                        request.connectionId(),
                        request.side(),
                        request.type(),
                        request.symbol(),
                        request.quantity(),
                        request.limitPrice(),
                        request.currency()));
    }

    @GetMapping("/{id}")
    PaperOrderWorkflowService.OrderView read(Principal principal, @PathVariable UUID id) {
        return workflow.read(userId(principal), id);
    }

    @PostMapping("/{id}/approve")
    PaperOrderWorkflowService.OrderView approve(
            Principal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ActionRequest request
    ) {
        if (request == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
        return workflow.approve(userId(principal), id, idempotencyKey, request.channel());
    }

    @PostMapping("/{id}/cancel")
    PaperOrderWorkflowService.OrderView cancel(
            Principal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ActionRequest request
    ) {
        if (request == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
        return workflow.cancel(userId(principal), id, idempotencyKey, request.channel());
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw PaperOrderWorkflowException.authenticatedUserInvalid();
        }
    }

    record ProposeRequest(
            UUID connectionId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency,
            PaperOrderWorkflowService.Channel channel
    ) {
    }

    record ActionRequest(PaperOrderWorkflowService.Channel channel) {
    }
}

@RestControllerAdvice
class PaperOrderWorkflowErrorHandler {

    @ExceptionHandler(PaperOrderWorkflowException.class)
    ResponseEntity<PublicError> paperOrder(PaperOrderWorkflowException exception) {
        return switch (exception.code()) {
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "PAPER_ORDER_NOT_FOUND");
            case CONFLICT -> error(HttpStatus.CONFLICT, "PAPER_ORDER_CONFLICT");
            case VALIDATION_FAILED -> error(HttpStatus.UNPROCESSABLE_ENTITY, "PAPER_ORDER_VALIDATION_FAILED");
            case AUTHENTICATED_USER_INVALID -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
        };
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record PublicError(String code) {
    }
}
