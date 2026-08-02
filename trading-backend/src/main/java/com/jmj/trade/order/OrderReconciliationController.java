package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Clock;
import java.util.UUID;

/**
 * UNKNOWN 제출 시도 수동 조정 API (플랜 원장 E5; SPEC:1099-1100/1121).
 *
 * <p>운영자가 UNKNOWN attempt 를 조정한다. 브로커 OPEN/CLOSED 를 조회해 판정하고, 판정이
 * {@code MANUAL_REVIEW_REQUIRED} 이면 그 계좌의 신규 주문을 잠근다(E4 ACCOUNT kill switch).
 * <b>잠금 해제는 이 API 에 없다</b> — 해제는 kill switch 조작 API({@code /api/v1/trading/kill-switch})
 * 의 disengage 로만, step-up 재인증을 거쳐 이뤄진다(자동 해제 경로 없음).
 *
 * <p>{@code userId} 는 인증 주체이며 대상 연결의 소유자여야 한다(E4 소유권 검증). 브로커 조회에
 * 필요한 계좌 참조는 요청 본문으로 받는다.
 */
@RestController
@RequestMapping("/api/v1/trading/order-reconciliation")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class OrderReconciliationController {

    private final UnknownAttemptReconciler reconciler;
    private final Clock clock;

    OrderReconciliationController(UnknownAttemptReconciler reconciler) {
        this.reconciler = reconciler;
        this.clock = Clock.systemUTC();
    }

    @PostMapping
    ReconcileView reconcile(Principal principal, @RequestBody ReconcileRequest request) {
        if (request == null
                || request.attemptId() == null
                || request.brokerConnectionId() == null
                || request.reason() == null || request.reason().isBlank()) {
            throw new ReconcileInputInvalidException();
        }
        var userId = userId(principal);
        var account = new BrokerAccountRef(
                request.brokerConnectionId(),
                request.brokerAccountId(),
                request.accountType(),
                request.displayAccountNumber());
        var outcome = reconciler.reconcile(new UnknownAttemptReconciler.Command(
                request.attemptId(),
                account,
                userId,
                clock.instant(),
                userId.toString(),
                request.reason()));
        return new ReconcileView(
                outcome.attemptId(),
                outcome.orderIntentId(),
                outcome.decision().name(),
                outcome.accountLocked());
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw PaperOrderWorkflowException.authenticatedUserInvalid();
        }
    }

    @ExceptionHandler(ReconcileInputInvalidException.class)
    ResponseEntity<PublicError> invalidInput(ReconcileInputInvalidException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new PublicError("ORDER_RECONCILIATION_INPUT_INVALID"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<PublicError> illegalState(IllegalStateException exception) {
        // UNKNOWN 이 아닌 attempt 조정 시도 등. 대상 상태 충돌은 409.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new PublicError("ORDER_RECONCILIATION_STATE_INVALID"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<PublicError> illegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new PublicError("ORDER_RECONCILIATION_INPUT_INVALID"));
    }

    record ReconcileRequest(
            UUID attemptId,
            UUID brokerConnectionId,
            String brokerAccountId,
            String accountType,
            String displayAccountNumber,
            String reason
    ) {
    }

    record ReconcileView(UUID attemptId, UUID orderIntentId, String decision, boolean accountLocked) {
    }

    record PublicError(String code) {
    }

    private static final class ReconcileInputInvalidException extends RuntimeException {
    }
}
