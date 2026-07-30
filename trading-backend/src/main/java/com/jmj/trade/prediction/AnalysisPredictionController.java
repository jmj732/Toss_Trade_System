package com.jmj.trade.prediction;

import com.jmj.trade.broker.Currency;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/v1/broker-connections/{connectionId}/analysis-predictions")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class AnalysisPredictionController {

    private final AnalysisPredictionService predictions;

    AnalysisPredictionController(AnalysisPredictionService predictions) {
        this.predictions = predictions;
    }

    @PostMapping
    AnalysisPredictionService.PredictionView create(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestBody CreateRequest request
    ) {
        if (request == null) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.INVALID_INPUT);
        }
        return predictions.create(
                userId(principal),
                connectionId,
                new AnalysisPredictionService.CreateCommand(
                        request.symbol(), request.currency(), request.predictedDirection(),
                        request.modelVersion(), request.contractVersion()),
                Instant.now());
    }

    @PostMapping("/batch")
    AnalysisPredictionService.BatchView createBatch(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestBody BatchRequest request
    ) {
        if (request == null) {
            throw new AnalysisPredictionException(AnalysisPredictionException.Code.INVALID_INPUT);
        }
        return predictions.createBatch(
                userId(principal),
                connectionId,
                request.items() == null ? null : request.items().stream()
                        .map(item -> item == null ? null : new AnalysisPredictionService.BatchCommand(
                                item.clientRequestId(),
                                new AnalysisPredictionService.CreateCommand(
                                        item.symbol(), item.currency(), item.predictedDirection(),
                                        item.modelVersion(), item.contractVersion())))
                        .toList(),
                Instant.now());
    }

    @GetMapping
    AnalysisPredictionService.PredictionPerformanceView read(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String modelVersion,
            @RequestParam(required = false) String contractVersion
    ) {
        return predictions.read(
                userId(principal), connectionId, from, to, modelVersion, contractVersion, Instant.now());
    }

    @ExceptionHandler(AnalysisPredictionException.class)
    ResponseEntity<PublicError> analysisPrediction(AnalysisPredictionException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "ANALYSIS_PREDICTION_INPUT_INVALID");
            case MODEL_VERSION_NOT_ACTIVE ->
                    error(HttpStatus.CONFLICT, "ANALYSIS_PREDICTION_MODEL_VERSION_NOT_ACTIVE");
            case QUOTE_CURRENCY_MISMATCH -> error(HttpStatus.CONFLICT, "ANALYSIS_PREDICTION_QUOTE_CURRENCY_MISMATCH");
            case QUOTE_UNAVAILABLE -> error(HttpStatus.CONFLICT, "ANALYSIS_PREDICTION_QUOTE_UNAVAILABLE");
        };
    }

    @ExceptionHandler(InvalidUserException.class)
    ResponseEntity<PublicError> invalidUser() {
        return error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new InvalidUserException();
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    private static final class InvalidUserException extends RuntimeException {
    }

    record CreateRequest(
            String symbol,
            Currency currency,
            PredictedDirection predictedDirection,
            String modelVersion,
            String contractVersion
    ) {
    }

    record BatchRequest(List<BatchItemRequest> items) {
    }

    record BatchItemRequest(
            String clientRequestId,
            String symbol,
            Currency currency,
            PredictedDirection predictedDirection,
            String modelVersion,
            String contractVersion
    ) {
    }

    record PublicError(String code) {
    }
}
