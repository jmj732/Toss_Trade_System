package com.jmj.trade.account;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class BrokerSurfaceService {

    private final JdbcTemplate jdbc;
    private final FreshPortfolioReadService portfolios;
    private final BrokerAdapter broker;

    public BrokerSurfaceService(
            JdbcTemplate jdbc,
            FreshPortfolioReadService portfolios,
            BrokerAdapter broker
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.portfolios = Objects.requireNonNull(portfolios, "portfolios");
        this.broker = Objects.requireNonNull(broker, "broker");
    }

    public BrokerSurfaceResponse<Map<String, PortfolioReadService.BuyingPowerView>> buyingPower(
            UUID userId,
            UUID connectionId,
            String requestedCurrency
    ) {
        requireReady(userId, connectionId);
        parseCurrency(requestedCurrency);
        try {
            var portfolio = portfolios.read(userId, connectionId);
            var values = new LinkedHashMap<>(portfolio.buyingPower());
            var unknown = new ArrayList<String>();
            if (!values.containsKey("KRW")) {
                unknown.add("KRW");
            }
            if (!values.containsKey("USD")) {
                unknown.add("USD");
            }
            if (portfolio.stale() || portfolio.partial() || !unknown.isEmpty()) {
                return BrokerSurfaceResponse.degraded(
                        Map.copyOf(values), portfolio.stale(), portfolio.unknownFields().size() > 0,
                        unknown.isEmpty() ? portfolio.unknownFields() : unknown,
                        portfolio.staleReason() == null ? "BUYING_POWER_PARTIAL" : portfolio.staleReason());
            }
            return BrokerSurfaceResponse.available(Map.copyOf(values));
        } catch (PortfolioReadException exception) {
            return BrokerSurfaceResponse.unavailable("SNAPSHOT_NOT_READY");
        }
    }

    public BrokerSurfaceResponse<List<BrokerSurfaceResponse.PriceView>> prices(
            UUID userId,
            UUID connectionId,
            String rawSymbols
    ) {
        requireReady(userId, connectionId);
        var symbols = symbols(rawSymbols);
        var values = new ArrayList<BrokerSurfaceResponse.PriceView>();
        var unknownFields = new ArrayList<String>();
        for (var symbol : symbols) {
            try {
                var response = broker.getQuote(new BrokerConnectionRef(connectionId), symbol);
                var quote = response == null ? null : response.value();
                if (quote == null) {
                    unknownFields.add(symbol + ".quote");
                } else if (!connectionId.equals(quote.connection().brokerConnectionId())
                        || !symbol.equalsIgnoreCase(quote.symbol())) {
                    unknownFields.add(symbol + ".quote");
                } else {
                    values.add(price(quote));
                    if (quote.lastPrice() == null) {
                        unknownFields.add(symbol + ".lastPrice");
                    }
                    if (quote.bidPrice() == null) {
                        unknownFields.add(symbol + ".bidPrice");
                    }
                    if (quote.askPrice() == null) {
                        unknownFields.add(symbol + ".askPrice");
                    }
                }
            } catch (RuntimeException exception) {
                unknownFields.add(symbol + ".quote");
            }
        }
        if (!unknownFields.isEmpty()) {
            return BrokerSurfaceResponse.degraded(
                List.copyOf(values), false, true,
                    unknownFields,
                    "PRICE_PARTIAL");
        }
        return BrokerSurfaceResponse.available(List.copyOf(values));
    }

    public BrokerSurfaceResponse<BrokerSurfaceResponse.SellableQuantityView> sellableQuantity(
            UUID userId,
            UUID connectionId,
            String rawSymbol
    ) {
        requireReady(userId, connectionId);
        var symbol = symbol(rawSymbol);
        try {
            var portfolio = portfolios.read(userId, connectionId);
            var position = portfolio.positions().stream()
                    .filter(candidate -> symbol.equalsIgnoreCase(candidate.symbol()))
                    .findFirst()
                    .orElse(null);
            if (position == null || position.sellableQuantity() == null) {
                return BrokerSurfaceResponse.unavailable("SELLABLE_QUANTITY_NOT_SNAPSHOT");
            }
            var value = new BrokerSurfaceResponse.SellableQuantityView(
                    symbol,
                    "KNOWN",
                    position.sellableQuantity(),
                    position.observedAt());
            if (portfolio.stale() || portfolio.partial()) {
                return BrokerSurfaceResponse.degraded(
                        value, portfolio.stale(), false, List.of(),
                        portfolio.staleReason() == null ? "SELLABLE_QUANTITY_STALE" : portfolio.staleReason());
            }
            return BrokerSurfaceResponse.available(value);
        } catch (PortfolioReadException exception) {
            return BrokerSurfaceResponse.unavailable("SNAPSHOT_NOT_READY");
        }
    }

    public <T> BrokerSurfaceResponse<T> unsupported(UUID userId, UUID connectionId) {
        requireReady(userId, connectionId);
        return BrokerSurfaceResponse.unavailable("PROVIDER_UNSUPPORTED");
    }

    private void requireReady(UUID userId, UUID connectionId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        var statuses = jdbc.queryForList("""
                SELECT status
                  FROM broker_connections
                 WHERE id = ?
                   AND user_id = ?
                   AND deleted_at IS NULL
                """, String.class, connectionId, userId);
        if (statuses.isEmpty()) {
            throw BrokerConnectionException.notFound();
        }
        if (!"ACTIVE".equals(statuses.getFirst())) {
            throw BrokerConnectionException.notReady();
        }
    }

    private static BrokerSurfaceResponse.PriceView price(Quote quote) {
        return new BrokerSurfaceResponse.PriceView(
                quote.symbol(), quote.lastPrice(), quote.bidPrice(), quote.askPrice(),
                quote.currency().name(), quote.observedAt(), quote.brokerTimestamp());
    }

    private static List<String> symbols(String rawSymbols) {
        if (rawSymbols == null || rawSymbols.isBlank()) {
            throw BrokerConnectionException.validationFailed();
        }
        var values = Arrays.stream(rawSymbols.split(","))
                .map(BrokerSurfaceService::symbol)
                .distinct()
                .toList();
        if (values.isEmpty() || values.size() > 20) {
            throw BrokerConnectionException.validationFailed();
        }
        return values;
    }

    private static String symbol(String raw) {
        var value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9.-]+")) {
            throw BrokerConnectionException.validationFailed();
        }
        return value;
    }

    private static Currency parseCurrency(String raw) {
        try {
            return Currency.valueOf(Objects.requireNonNullElse(raw, "USD").toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw BrokerConnectionException.validationFailed();
        }
    }
}
