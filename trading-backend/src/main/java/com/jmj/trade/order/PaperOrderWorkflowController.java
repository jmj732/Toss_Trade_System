package com.jmj.trade.order;

import com.jmj.trade.account.PortfolioReadException;
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

    /**
     * 주문 사전 위험 미리보기 (BC-6). 아무 상태도 남기지 않으므로 {@code Idempotency-Key} 를
     * 요구하지 않고, step-up 토큰도 요구하거나 발급하지 않는다. 승인 시점의 재검사는 그대로 남는다.
     */
    @PostMapping("/preview")
    PaperOrderWorkflowService.OrderPreview preview(
            Principal principal,
            @RequestBody ProposeRequest request
    ) {
        if (request == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
        return workflow.preview(
                userId(principal),
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

    /**
     * 포트폴리오 스냅샷이 아직 없으면 위험 판정 자체가 불가능하다. 이 컨트롤러 안에서만 409 로
     * 명시해, 프론트가 "판정 불가"를 500 이 아닌 파싱 가능한 사실로 받게 한다. 없는 근거를 0 이나
     * "위험 없음"으로 대체하지 않는다.
     */
    @ExceptionHandler(PortfolioReadException.class)
    ResponseEntity<PaperOrderWorkflowErrorHandler.PublicError> portfolioUnavailable() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new PaperOrderWorkflowErrorHandler.PublicError(
                        "PAPER_ORDER_PORTFOLIO_SNAPSHOT_UNAVAILABLE"));
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
            case PROPOSAL_EXPIRED -> error(HttpStatus.CONFLICT, "PAPER_ORDER_PROPOSAL_EXPIRED");
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
