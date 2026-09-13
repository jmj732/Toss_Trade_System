package com.jmj.trade.connector;

import com.jmj.trade.broker.BrokerException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/connector")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class ConnectorController {

    private final ConnectorService service;

    public ConnectorController(ConnectorService service) { this.service = service; }

    @GetMapping("/portfolio")
    ConnectorResponse.Portfolio portfolio(Authentication authentication) {
        var key = key(authentication);
        return service.portfolio(key.userId(), key.connectionId());
    }

    @GetMapping("/orders")
    List<ConnectorResponse.Order> orders(Authentication authentication,
                                         @RequestParam(defaultValue = "OPEN") String group) {
        var key = key(authentication);
        return service.orders(key.userId(), key.connectionId(), group);
    }

    @GetMapping("/fills")
    List<ConnectorResponse.Fill> fills(Authentication authentication,
                                       @RequestParam(required = false) String since) {
        var key = key(authentication);
        return service.fills(key.userId(), key.connectionId(), since == null ? null : Instant.parse(since));
    }

    private static ConnectorApiKeyService.AuthenticatedKey key(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof ConnectorApiKeyService.AuthenticatedKey key)) {
            throw new org.springframework.security.authentication.BadCredentialsException("connector key required");
        }
        return key;
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
    org.springframework.http.ResponseEntity<PublicError> invalidRequest(RuntimeException exception) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new PublicError("CONNECTOR_INVALID_REQUEST"));
    }

    @ExceptionHandler({BrokerException.class, IllegalStateException.class})
    org.springframework.http.ResponseEntity<PublicError> unavailable(RuntimeException exception) {
        return org.springframework.http.ResponseEntity.status(503)
                .body(new PublicError("CONNECTOR_UNAVAILABLE"));
    }

    record PublicError(String code) { }
}
