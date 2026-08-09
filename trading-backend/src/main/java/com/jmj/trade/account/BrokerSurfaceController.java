package com.jmj.trade.account;

import com.jmj.trade.broker.connection.AuthenticatedUserInvalidException;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/broker-connections/{connectionId}")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class BrokerSurfaceController {

    private final BrokerSurfaceService surfaces;

    BrokerSurfaceController(BrokerSurfaceService surfaces) {
        this.surfaces = surfaces;
    }

    @GetMapping("/buying-power")
    BrokerSurfaceResponse<Map<String, PortfolioReadService.BuyingPowerView>> buyingPower(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam(name = "currency", defaultValue = "USD") String currency
    ) {
        return surfaces.buyingPower(userId(principal), connectionId, currency);
    }

    @GetMapping("/prices")
    BrokerSurfaceResponse<List<BrokerSurfaceResponse.PriceView>> prices(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam String symbols
    ) {
        return surfaces.prices(userId(principal), connectionId, symbols);
    }

    @GetMapping("/sellable-quantity")
    BrokerSurfaceResponse<BrokerSurfaceResponse.SellableQuantityView> sellableQuantity(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam String symbol
    ) {
        return surfaces.sellableQuantity(userId(principal), connectionId, symbol);
    }

    @GetMapping({
            "/orderbook", "/candles", "/exchange-rate", "/market-calendar/{market}",
            "/stocks/{symbol}/warnings", "/stocks/{symbol}/investor-trading", "/rankings", "/commissions"
    })
    BrokerSurfaceResponse<Void> unsupported(Principal principal, @PathVariable UUID connectionId) {
        return surfaces.unsupported(userId(principal), connectionId);
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new AuthenticatedUserInvalidException();
        }
    }
}
