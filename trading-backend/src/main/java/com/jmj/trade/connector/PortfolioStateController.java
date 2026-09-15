package com.jmj.trade.connector;

import com.jmj.trade.broker.connection.AuthenticatedUserInvalidException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/** Human-authenticated dashboard surface for the same state contract used by GPT Actions. */
@RestController
@RequestMapping("/api/v1/broker-connections/{connectionId}/portfolio")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class PortfolioStateController {

    private final ConnectorService service;

    PortfolioStateController(ConnectorService service) {
        this.service = service;
    }

    @GetMapping("/state")
    ConnectorResponse.PortfolioState state(Principal principal, @PathVariable UUID connectionId) {
        return service.portfolioState(userId(principal), connectionId);
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new AuthenticatedUserInvalidException();
        }
    }
}
