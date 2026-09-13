package com.jmj.trade.connector;

import com.jmj.trade.security.AuthenticationClaims;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/connector-api-keys")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class ConnectorApiKeyController {
    private final ConnectorApiKeyService keys;

    public ConnectorApiKeyController(ConnectorApiKeyService keys) { this.keys = keys; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConnectorApiKeyService.IssuedKey issue(Principal principal,
                                            org.springframework.security.core.Authentication authentication,
                                            @RequestBody IssueRequest request) {
        AuthenticationClaims.requireRecent(authentication, Duration.ofMinutes(5));
        if (request == null || request.connectionId() == null) throw new ConnectorApiKeyService.ApiKeyException(ConnectorApiKeyService.Code.INVALID_INPUT);
        return keys.issue(UUID.fromString(principal.getName()), request.connectionId(), request.expiresAt());
    }

    @GetMapping
    List<ConnectorApiKeyService.KeyView> list(Principal principal) { return keys.list(UUID.fromString(principal.getName())); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Principal principal, org.springframework.security.core.Authentication authentication, @PathVariable UUID id) {
        AuthenticationClaims.requireRecent(authentication, Duration.ofMinutes(5));
        keys.revoke(UUID.fromString(principal.getName()), id);
    }

    record IssueRequest(UUID connectionId, Instant expiresAt) { }

    @ExceptionHandler(ConnectorApiKeyService.ApiKeyException.class)
    ResponseEntity<PublicError> error(ConnectorApiKeyService.ApiKeyException exception) {
        var status = switch (exception.code()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case CONNECTION_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(new PublicError("CONNECTOR_API_KEY_" + exception.code()));
    }

    record PublicError(String code) { }
}
