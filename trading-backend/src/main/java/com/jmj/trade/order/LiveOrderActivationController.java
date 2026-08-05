package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;
import com.jmj.trade.security.AuthenticationClaims;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/live-orders")
@ConditionalOnProperty(prefix = "real-order", name = "enabled", havingValue = "true")
public final class LiveOrderActivationController {

    private final LiveOrderActivationService activation;

    LiveOrderActivationController(LiveOrderActivationService activation) {
        this.activation = activation;
    }

    @PostMapping
    UUID propose(Principal principal, @RequestBody ProposalRequest request) {
        if (request == null) {
            throw validation();
        }
        return activation.propose(userId(principal), new LiveOrderActivationService.Proposal(
                request.connectionId(), request.brokerAccountId(), request.side(), request.type(),
                request.symbol(), request.quantity(), request.limitPrice(), request.currency()));
    }

    @PostMapping("/{id}/step-up")
    StepUpView stepUp(Principal principal, Authentication authentication,
                     @PathVariable UUID id) {
        var issued = activation.issueStepUp(
                userId(principal), id, AuthenticationClaims.authenticatedAt(authentication));
        return new StepUpView(issued.stepUpToken(), issued.expiresAt());
    }

    @PostMapping("/{id}/approve")
    void approve(Principal principal, @PathVariable UUID id,
                 @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
                 @RequestBody ApproveRequest request) {
        if (request == null) {
            throw validation();
        }
        activation.approve(userId(principal), id, stepUpToken, "WEB:" + principal.getName(),
                request.displayedQuantity(), request.displayedMaxLoss(), request.displayedCurrency());
    }

    @PostMapping("/{id}/dispatch")
    LiveOrderActivationService.DispatchResult dispatch(
            Principal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String clientOrderId,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken
    ) {
        return activation.dispatch(userId(principal), id, clientOrderId, stepUpToken, "WEB:" + principal.getName());
    }

    @PostMapping("/{id}/cancel")
    LiveOrderActivationService.OperationResult cancel(
            Principal principal,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken
    ) {
        return activation.cancel(userId(principal), id, stepUpToken, "WEB:" + principal.getName());
    }

    @PostMapping("/{id}/modify")
    LiveOrderActivationService.OperationResult modify(
            Principal principal,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
            @RequestBody ModifyRequest request
    ) {
        if (request == null) {
            throw validation();
        }
        return activation.modify(userId(principal), id, request.newLimitPrice(), stepUpToken,
                "WEB:" + principal.getName());
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new LiveOrderActivationException(
                    LiveOrderActivationException.Code.VALIDATION, "authenticated user is invalid");
        }
    }

    private static LiveOrderActivationException validation() {
        return new LiveOrderActivationException(LiveOrderActivationException.Code.VALIDATION,
                "invalid live order request");
    }

    record ProposalRequest(UUID connectionId, UUID brokerAccountId, OrderSide side, OrderType type,
                           String symbol, BigDecimal quantity, BigDecimal limitPrice, Currency currency) {
    }

    record ApproveRequest(BigDecimal displayedQuantity, BigDecimal displayedMaxLoss, Currency displayedCurrency) {
    }

    record ModifyRequest(BigDecimal newLimitPrice) {
    }

    record StepUpView(String stepUpToken, Instant expiresAt) {
    }
}

@RestControllerAdvice(assignableTypes = LiveOrderActivationController.class)
final class LiveOrderActivationErrorHandler {

    @ExceptionHandler(LiveOrderActivationException.class)
    ResponseEntity<ErrorBody> handle(LiveOrderActivationException exception) {
        var status = switch (exception.code()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STEP_UP_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case VALIDATION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case SAFETY_BLOCKED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MANUAL_REVIEW_REQUIRED, CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ErrorBody("LIVE_ORDER_" + exception.code()));
    }

    record ErrorBody(String code) {
    }
}
