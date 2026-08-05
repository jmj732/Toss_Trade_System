package com.jmj.trade.prediction;

import com.jmj.trade.security.AuthenticationClaims;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/prediction-ingestion-api-keys")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
final class PredictionIngestionApiKeyController {

    private final PredictionIngestionApiKeyService apiKeys;

    PredictionIngestionApiKeyController(PredictionIngestionApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PredictionIngestionApiKeyService.IssuedKey issue(
            Principal principal,
            org.springframework.security.core.Authentication authentication,
            @RequestBody IssueRequest request
    ) {
        AuthenticationClaims.requireRecent(authentication, Duration.ofMinutes(5));
        if (request == null) {
            throw new PredictionIngestionApiKeyService.ApiKeyException(
                    PredictionIngestionApiKeyService.ApiKeyException.Code.INVALID_INPUT);
        }
        return apiKeys.issue(userId(principal), new PredictionIngestionApiKeyService.IssueCommand(
                request.modelVersion(), request.contractVersion(), request.expiresAt()));
    }

    @GetMapping
    List<PredictionIngestionApiKeyService.KeyView> list(Principal principal) {
        return apiKeys.list(userId(principal));
    }

    @PostMapping("/{id}/rotate")
    @ResponseStatus(HttpStatus.CREATED)
    PredictionIngestionApiKeyService.IssuedKey rotate(
            Principal principal,
            org.springframework.security.core.Authentication authentication,
            @PathVariable UUID id,
            @RequestBody(required = false) RotateRequest request
    ) {
        AuthenticationClaims.requireRecent(authentication, Duration.ofMinutes(5));
        return apiKeys.rotate(
                userId(principal),
                id,
                request == null ? null : request.expiresAt());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(
            Principal principal,
            org.springframework.security.core.Authentication authentication,
            @PathVariable UUID id
    ) {
        AuthenticationClaims.requireRecent(authentication, Duration.ofMinutes(5));
        apiKeys.revoke(userId(principal), id);
    }

    @ExceptionHandler(PredictionIngestionApiKeyService.ApiKeyException.class)
    ResponseEntity<PublicError> apiKeyError(
            PredictionIngestionApiKeyService.ApiKeyException exception
    ) {
        return switch (exception.code()) {
            case INVALID_INPUT ->
                    error(HttpStatus.BAD_REQUEST, "PREDICTION_INGESTION_API_KEY_INVALID_INPUT");
            case MODEL_SCOPE_NOT_ACTIVE ->
                    error(HttpStatus.CONFLICT, "PREDICTION_INGESTION_API_KEY_SCOPE_NOT_ACTIVE");
            case NOT_FOUND ->
                    error(HttpStatus.NOT_FOUND, "PREDICTION_INGESTION_API_KEY_NOT_FOUND");
            case NOT_ACTIVE ->
                    error(HttpStatus.CONFLICT, "PREDICTION_INGESTION_API_KEY_NOT_ACTIVE");
        };
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new PredictionIngestionApiKeyService.ApiKeyException(
                    PredictionIngestionApiKeyService.ApiKeyException.Code.NOT_FOUND);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record IssueRequest(String modelVersion, String contractVersion, Instant expiresAt) {
    }

    record RotateRequest(Instant expiresAt) {
    }

    record PublicError(String code) {
    }
}
