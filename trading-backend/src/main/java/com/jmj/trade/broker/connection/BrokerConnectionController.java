package com.jmj.trade.broker.connection;

import com.jmj.trade.account.PortfolioReadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/broker-connections")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class BrokerConnectionController {

    private final BrokerConnectionService connectionService;
    private final BrokerConnectionValidationService validationService;
    private final PortfolioReadService portfolioReadService;

    BrokerConnectionController(
            BrokerConnectionService connectionService,
            BrokerConnectionValidationService validationService,
            PortfolioReadService portfolioReadService
    ) {
        this.connectionService = connectionService;
        this.validationService = validationService;
        this.portfolioReadService = portfolioReadService;
    }

    @PostMapping("/toss")
    BrokerConnectionResponse createToss(Principal principal, @RequestBody BrokerConnectionRequest request) {
        var validated = validated(request);
        return BrokerConnectionResponse.from(connectionService.createToss(
                userId(principal),
                validated.clientId(),
                validated.clientSecret()));
    }

    @PutMapping("/{id}/credentials")
    BrokerConnectionResponse replaceCredentials(
            Principal principal,
            @PathVariable UUID id,
            @RequestBody BrokerConnectionRequest request
    ) {
        var validated = validated(request);
        return BrokerConnectionResponse.from(connectionService.replaceCredentials(
                userId(principal),
                id,
                validated.clientId(),
                validated.clientSecret()));
    }

    @PostMapping("/{id}/verify")
    BrokerConnectionResponse verify(Principal principal, @PathVariable UUID id) {
        return BrokerConnectionResponse.from(validationService.validateToss(userId(principal), id));
    }

    @GetMapping("/{id}/portfolio")
    PortfolioReadService.PortfolioView portfolio(Principal principal, @PathVariable UUID id) {
        return portfolioReadService.read(userId(principal), id);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(Principal principal, @PathVariable UUID id) {
        connectionService.delete(userId(principal), id);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new AuthenticatedUserInvalidException();
        }
    }

    private static BrokerConnectionRequest validated(BrokerConnectionRequest request) {
        if (request == null) {
            throw BrokerConnectionException.validationFailed();
        }
        return request.validated();
    }
}
