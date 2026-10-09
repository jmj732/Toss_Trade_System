package com.jmj.trade.investment;

import com.jmj.trade.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** Review/audit notes. Separate from {@code /investment/decisions}: never records an investment action. */
@RestController
@RequestMapping("/investment/reviews")
final class InvestmentReviewController {

    private final InvestmentReviewService service;
    private final ObjectMapper objectMapper;

    InvestmentReviewController(InvestmentReviewService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    List<InvestmentReviewService.ReviewView> reviews(
            Principal principal,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return service.reviews(userId(principal), limit);
    }

    @PostMapping
    InvestmentReviewService.ReviewView review(Principal principal, @RequestBody JsonNode body) {
        var userId = userId(principal);
        if (body == null || !body.isObject()) throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        final InvestmentReviewService.ReviewInput parsed;
        try {
            parsed = objectMapper.treeToValue(body, InvestmentReviewService.ReviewInput.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        var source = parsed.source() == null ? "USER_REST" : parsed.source().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"USER_REST".equals(source) && !"SHEET_LEGACY_IMPORT".equals(source)) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        // Without an explicit original row, the received request body is the verbatim raw record.
        var input = parsed.withSource(source);
        if (!body.has("rawPayload")) input = input.withRawPayload(body.deepCopy());
        return service.recordReview(userId, input,
                InvestmentReviewService.Actor.userSession(userId, extractSessionId(principal)));
    }

    @ExceptionHandler(InvestmentException.class)
    ResponseEntity<PublicError> investment(InvestmentException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "INVESTMENT_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "INVESTMENT_STATE_NOT_FOUND");
            case CONFLICT -> error(HttpStatus.CONFLICT, "INVESTMENT_REVIEW_CONFLICT");
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

    private static UUID extractSessionId(Principal principal) {
        if (principal instanceof AuthenticatedUser user) return user.sessionId();
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.sessionId();
        }
        return null;
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record PublicError(String code) {
    }
}
