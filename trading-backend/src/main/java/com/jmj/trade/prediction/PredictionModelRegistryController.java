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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/prediction-model-versions")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class PredictionModelRegistryController {

    private final PredictionModelRegistryService registry;

    PredictionModelRegistryController(PredictionModelRegistryService registry) {
        this.registry = registry;
    }

    @PostMapping
    PredictionModelRegistryService.VersionView register(
            Principal principal,
            org.springframework.security.core.Authentication authentication,
            @RequestBody RegisterRequest request
    ) {
        AuthenticationClaims.requireRecent(authentication, Duration.ofMinutes(5));
        if (request == null) {
            throw new PredictionModelRegistryException(
                    PredictionModelRegistryException.Code.INVALID_INPUT);
        }
        return registry.register(
                userId(principal),
                new PredictionModelRegistryService.RegisterCommand(
                        request.modelVersion(), request.contractVersion()));
    }

    @GetMapping
    List<PredictionModelRegistryService.VersionView> list(Principal principal) {
        return registry.list(userId(principal));
    }

    @PostMapping("/{id}/deprecate")
    PredictionModelRegistryService.VersionView deprecate(
            Principal principal,
            org.springframework.security.core.Authentication authentication,
            @PathVariable UUID id
    ) {
        AuthenticationClaims.requireRecent(authentication, Duration.ofMinutes(5));
        return registry.deprecate(userId(principal), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(
            Principal principal,
            org.springframework.security.core.Authentication authentication,
            @PathVariable UUID id
    ) {
        AuthenticationClaims.requireRecent(authentication, Duration.ofMinutes(5));
        registry.delete(userId(principal), id);
    }

    @ExceptionHandler(PredictionModelRegistryException.class)
    ResponseEntity<PublicError> registryError(PredictionModelRegistryException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT ->
                    error(HttpStatus.BAD_REQUEST, "PREDICTION_MODEL_VERSION_INVALID_INPUT");
            case ALREADY_EXISTS ->
                    error(HttpStatus.CONFLICT, "PREDICTION_MODEL_VERSION_ALREADY_EXISTS");
            case NOT_FOUND ->
                    error(HttpStatus.NOT_FOUND, "PREDICTION_MODEL_VERSION_NOT_FOUND");
            case IN_USE ->
                    error(HttpStatus.CONFLICT, "PREDICTION_MODEL_VERSION_IN_USE");
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

    record RegisterRequest(String modelVersion, String contractVersion) {
    }

    record PublicError(String code) {
    }
}
