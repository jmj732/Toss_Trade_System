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
import java.time.LocalDate;

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

    @GetMapping("/orderbook")
    BrokerSurfaceResponse<BrokerSurfaceResponse.OrderBookView> orderBook(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam String symbol
    ) {
        return surfaces.orderBook(userId(principal), connectionId, symbol);
    }

    @GetMapping("/candles")
    BrokerSurfaceResponse<BrokerSurfaceResponse.CandleSeriesView> candles(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "true") boolean adjusted
    ) {
        return surfaces.candles(userId(principal), connectionId, symbol, interval, count, before, adjusted);
    }

    @GetMapping("/exchange-rate")
    BrokerSurfaceResponse<BrokerSurfaceResponse.ExchangeRateView> exchangeRate(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam(defaultValue = "USD") String baseCurrency,
            @RequestParam(defaultValue = "KRW") String quoteCurrency
    ) {
        return surfaces.exchangeRate(userId(principal), connectionId, baseCurrency, quoteCurrency);
    }

    @GetMapping("/market-calendar/{market}")
    BrokerSurfaceResponse<BrokerSurfaceResponse.MarketCalendarView> marketCalendar(
            Principal principal,
            @PathVariable UUID connectionId,
            @PathVariable String market,
            @RequestParam(required = false) LocalDate date
    ) {
        return surfaces.marketCalendar(userId(principal), connectionId, market, date);
    }

    @GetMapping("/rankings")
    BrokerSurfaceResponse<BrokerSurfaceResponse.RankingView> rankings(
            Principal principal,
            @PathVariable UUID connectionId,
            @RequestParam(name = "category", defaultValue = "VOLUME") String category,
            @RequestParam(defaultValue = "US") String marketCountry,
            @RequestParam(defaultValue = "realtime") String duration,
            @RequestParam(defaultValue = "10") int count
    ) {
        return surfaces.ranking(userId(principal), connectionId, category, marketCountry, duration, count);
    }

    @GetMapping({
            "/stocks/{symbol}/warnings", "/stocks/{symbol}/investor-trading", "/commissions"
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
