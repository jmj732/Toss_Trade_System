package com.jmj.trade.investment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * User-session REST for Telegram thesis approval requests (under {@code /investment/**}, user token only).
 * The request body carries no price: the trigger is the stored proposal or a server-computed candidate.
 */
@RestController
@RequestMapping("/investment")
final class ThesisApprovalRequestController {

    private final TelegramApprovalService approvals;

    ThesisApprovalRequestController(TelegramApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/securities/{ticker}/thesis/approval-requests")
    ResponseEntity<TelegramApprovalService.RequestView> create(
            Principal principal,
            @PathVariable String ticker,
            @RequestBody CreateRequest body
    ) {
        if (body == null) throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        var created = approvals.createRequest(userId(principal), ticker, body.candidateSource(),
                body.expectedThesisUpdatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/thesis/approval-requests")
    List<TelegramApprovalService.RequestView> list(Principal principal,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return approvals.list(userId(principal), limit);
    }

    @ExceptionHandler(TelegramApprovalService.Rejected.class)
    ResponseEntity<RejectedError> rejected(TelegramApprovalService.Rejected rejected) {
        var status = switch (rejected.reason()) {
            case NOT_READY, THESIS_CHANGED, ALREADY_CONFIRMED -> HttpStatus.CONFLICT;
            case INVALID_SOURCE, INVALID_THESIS -> HttpStatus.BAD_REQUEST;
            case THESIS_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TRIGGER_MISSING, CANDIDATE_UNVERIFIED -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status)
                .body(new RejectedError("THESIS_APPROVAL_" + rejected.reason().name(), rejected.detail()));
    }

    @ExceptionHandler(InvestmentException.class)
    ResponseEntity<RejectedError> investment(InvestmentException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "INVESTMENT_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "INVESTMENT_STATE_NOT_FOUND");
            case CONFLICT -> error(HttpStatus.CONFLICT, "INVESTMENT_DECISION_CONFLICT");
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<RejectedError> malformedJson() {
        return error(HttpStatus.BAD_REQUEST, "INVESTMENT_INPUT_INVALID");
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        }
    }

    private static ResponseEntity<RejectedError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new RejectedError(code, null));
    }

    /** Only {@code candidateSource} and the optional thesis version; any price field is ignored. */
    record CreateRequest(String candidateSource, Instant expectedThesisUpdatedAt) {
    }

    record RejectedError(String code, String reason) {
    }
}
