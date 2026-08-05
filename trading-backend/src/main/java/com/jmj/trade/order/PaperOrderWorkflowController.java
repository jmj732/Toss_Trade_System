package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;
import com.jmj.trade.security.AuthenticationClaims;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
import java.time.Instant;
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

    @GetMapping("/{id}/approval-preview")
    PaperOrderWorkflowService.ApprovalPreview approvalPreview(
            Principal principal, @PathVariable UUID id) {
        return workflow.approvalPreview(userId(principal), id);
    }

    /**
     * step-up 재인증 토큰 발급. 최근 OIDC 재인증(auth_time)만이 근거이며, 값이 없거나 오래되면 401.
     * 원문 토큰은 이 응답에서 한 번만 노출되고 서버는 해시만 저장한다.
     */
    @PostMapping("/{id}/step-up")
    StepUpView stepUp(
            Principal principal,
            Authentication authentication,
            @PathVariable UUID id
    ) {
        var issued = workflow.issueStepUp(
                userId(principal), id, AuthenticationClaims.authenticatedAt(authentication));
        return new StepUpView(issued.stepUpToken(), issued.expiresAt());
    }

    @PostMapping("/{id}/approve")
    PaperOrderWorkflowService.OrderView approve(
            Principal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
            @RequestBody ApproveRequest request
    ) {
        if (request == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
        return workflow.approve(
                userId(principal),
                id,
                idempotencyKey,
                new PaperOrderWorkflowService.ApproveCommand(
                        request.channel(),
                        request.displayedQuantity(),
                        request.displayedMaxLoss(),
                        request.displayedCurrency(),
                        stepUpToken,
                        request.proposalVersion()));
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

    /** 승인 철회. 제출 전이 전(PROPOSED)에서만 compare-and-set 으로 성공한다. */
    @PostMapping("/{id}/withdraw")
    PaperOrderWorkflowService.OrderView withdraw(
            Principal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ActionRequest request
    ) {
        if (request == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
        return workflow.withdraw(userId(principal), id, idempotencyKey, request.channel());
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

    record ApproveRequest(
            PaperOrderWorkflowService.Channel channel,
            BigDecimal displayedQuantity,
            BigDecimal displayedMaxLoss,
            Currency displayedCurrency,
            Long proposalVersion
    ) {
    }

    record ActionRequest(PaperOrderWorkflowService.Channel channel) {
    }

    record StepUpView(String stepUpToken, Instant expiresAt) {
    }
}

@RestControllerAdvice
class PaperOrderWorkflowErrorHandler {

    @ExceptionHandler(PaperOrderWorkflowException.class)
    ResponseEntity<?> paperOrder(PaperOrderWorkflowException exception) {
        return switch (exception.code()) {
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "PAPER_ORDER_NOT_FOUND");
            case CONFLICT -> error(HttpStatus.CONFLICT, "PAPER_ORDER_CONFLICT");
            case VALIDATION_FAILED -> error(HttpStatus.UNPROCESSABLE_ENTITY, "PAPER_ORDER_VALIDATION_FAILED");
            case AUTHENTICATED_USER_INVALID -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case STEP_UP_REQUIRED -> error(HttpStatus.UNAUTHORIZED, "PAPER_ORDER_STEP_UP_REQUIRED");
            case DISPLAY_MISMATCH -> displayMismatch(exception.serverSnapshot());
        };
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    private static ResponseEntity<?> displayMismatch(PaperOrderWorkflowException.DisplaySnapshot snapshot) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new DisplayMismatchError(
                "PAPER_ORDER_DISPLAY_MISMATCH",
                snapshot == null ? null : snapshot.quantity(),
                snapshot == null ? null : snapshot.maxLoss(),
                snapshot == null ? null : snapshot.currency()));
    }

    record PublicError(String code) {
    }

    record DisplayMismatchError(
            String code,
            BigDecimal serverQuantity,
            BigDecimal serverMaxLoss,
            Currency currency
    ) {
    }
}
