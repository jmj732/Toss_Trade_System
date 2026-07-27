package com.jmj.trade.intelligence;

import com.jmj.trade.analysis.PortfolioAnalysisException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/broker-connections/{connectionId}/events")
public class EventIntelligenceController {

    private final EventIntelligenceService service;
    private final EventReviewWorkflowService reviews;

    EventIntelligenceController(
            EventIntelligenceService service,
            EventReviewWorkflowService reviews
    ) {
        this.service = service;
        this.reviews = reviews;
    }

    @PostMapping
    EventIntelligenceService.EventView create(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestBody EventIntelligenceService.CreateEvent command
    ) {
        return service.create(userId(principal), connectionId, command);
    }

    @GetMapping
    List<EventReviewWorkflowService.ReviewSummary> list(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return reviews.list(userId(principal), connectionId, limit);
    }

    @GetMapping("/{eventId}")
    EventReviewWorkflowService.ReviewDetail get(
            Principal principal,
            @PathVariable UUID connectionId,
            @PathVariable UUID eventId
    ) {
        return reviews.get(userId(principal), connectionId, eventId);
    }

    @PostMapping("/{eventId}/reanalyze")
    EventIntelligenceService.ComparisonView reanalyze(
            Principal principal,
            @PathVariable UUID connectionId,
            @PathVariable UUID eventId
    ) {
        return service.reanalyze(userId(principal), connectionId, eventId);
    }

    @GetMapping("/{eventId}/comparison")
    EventIntelligenceService.ComparisonView comparison(
            Principal principal,
            @PathVariable UUID connectionId,
            @PathVariable UUID eventId
    ) {
        return service.getComparison(userId(principal), connectionId, eventId);
    }

    @PostMapping("/{eventId}/review")
    EventReviewWorkflowService.ReviewDetail review(
            Principal principal,
            @PathVariable UUID connectionId,
            @PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EventReviewWorkflowService.ReviewCommand command
    ) {
        return reviews.review(userId(principal), connectionId, eventId, idempotencyKey, command);
    }

    @ExceptionHandler(EventIntelligenceException.class)
    ResponseEntity<PublicError> event(EventIntelligenceException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "EVENT_INPUT_INVALID");
            case CONNECTION_NOT_FOUND ->
                    error(HttpStatus.NOT_FOUND, "BROKER_CONNECTION_NOT_FOUND");
            case EVENT_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
            case EVENT_ALREADY_EXISTS -> error(HttpStatus.CONFLICT, "EVENT_ALREADY_EXISTS");
            case EVENT_ALREADY_ANALYZED ->
                    error(HttpStatus.CONFLICT, "EVENT_ALREADY_ANALYZED");
            case COMPARISON_NOT_FOUND ->
                    error(HttpStatus.NOT_FOUND, "EVENT_COMPARISON_NOT_FOUND");
            case REVIEW_CONFLICT -> error(HttpStatus.CONFLICT, "EVENT_REVIEW_CONFLICT");
        };
    }

    @ExceptionHandler(PortfolioAnalysisException.class)
    ResponseEntity<PublicError> analysis(PortfolioAnalysisException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "BROKER_CONNECTION_NOT_FOUND");
            case SNAPSHOT_NOT_FOUND ->
                    error(HttpStatus.NOT_FOUND, "PORTFOLIO_SNAPSHOT_NOT_FOUND");
            case RESULT_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "ANALYSIS_RESULT_NOT_FOUND");
            case ALREADY_RUNNING -> error(HttpStatus.CONFLICT, "ANALYSIS_ALREADY_RUNNING");
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "ANALYSIS_TIMEOUT");
            case CONTRACT_ERROR -> error(HttpStatus.BAD_GATEWAY, "ANALYSIS_CONTRACT_ERROR");
            case UPSTREAM_UNAVAILABLE ->
                    error(HttpStatus.SERVICE_UNAVAILABLE, "ANALYSIS_SERVICE_UNAVAILABLE");
        };
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new EventIntelligenceException(EventIntelligenceException.Code.INVALID_USER);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record PublicError(String code) {
    }
}
