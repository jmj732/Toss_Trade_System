package com.jmj.trade.account;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.MarketDataAdapter;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDate;
import java.time.Clock;

@Service
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public final class BrokerSurfaceService {

    private final JdbcTemplate jdbc;
    private final FreshPortfolioReadService portfolios;
    private final BrokerAdapter broker;
    private final MarketDataAdapter marketData;
    private final Clock clock;

    @Autowired
    public BrokerSurfaceService(
            JdbcTemplate jdbc,
            FreshPortfolioReadService portfolios,
            BrokerAdapter brokerAdapter
    ) {
        this(jdbc, portfolios, brokerAdapter, Clock.systemUTC());
    }

    BrokerSurfaceService(
            JdbcTemplate jdbc,
            FreshPortfolioReadService portfolios,
            BrokerAdapter brokerAdapter,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.portfolios = Objects.requireNonNull(portfolios, "portfolios");
        this.broker = Objects.requireNonNull(brokerAdapter, "brokerAdapter");
        this.marketData = brokerAdapter instanceof MarketDataAdapter adapter ? adapter : null;
        this.clock = Objects.requireNonNull(clock, "clock");
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
        var provenance = new ArrayList<BrokerSurfaceResponse.ProviderProvenance>();
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
                    provenance.add(provenance(
                            "/api/v1/prices", quote.currency().name(), quote.brokerTimestamp(), quote.observedAt()));
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
            } catch (BrokerException exception) {
                return failure(exception, "/api/v1/prices");
            } catch (RuntimeException exception) {
                return failure(BrokerErrorCategory.CONTRACT, "/api/v1/prices");
            }
        }
        if (!unknownFields.isEmpty()) {
            return BrokerSurfaceResponse.degraded(
                List.copyOf(values), false, true,
                    unknownFields,
                    "PRICE_PARTIAL", provenance);
        }
        return BrokerSurfaceResponse.available(List.copyOf(values), provenance);
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

    public BrokerSurfaceResponse<BrokerSurfaceResponse.OrderBookView> orderBook(
            UUID userId, UUID connectionId, String rawSymbol) {
        requireReady(userId, connectionId);
        if (marketData == null) {
            return BrokerSurfaceResponse.unavailable("PROVIDER_UNSUPPORTED");
        }
        var symbol = symbol(rawSymbol);
        try {
            var response = marketData.getOrderBook(new BrokerConnectionRef(connectionId), symbol);
            var source = response.value();
            var unknown = new ArrayList<String>();
            if (source.timestamp() == null) unknown.add("timestamp");
            if (source.asks().isEmpty()) unknown.add("asks");
            if (source.bids().isEmpty()) unknown.add("bids");
            levelsUnknown(source.asks(), "asks", unknown);
            levelsUnknown(source.bids(), "bids", unknown);
            var data = new BrokerSurfaceResponse.OrderBookView(
                    source.symbol(), source.timestamp(), currency(source.currency()),
                    levels(source.asks()), levels(source.bids()));
            if (source.currency() == null) unknown.add("currency");
            var provenance = List.of(provenance(
                    "/api/v1/orderbook", currency(source.currency()), source.timestamp(), response.metadata().observedAt()));
            if (!unknown.isEmpty()) {
                return BrokerSurfaceResponse.degraded(data, false, true, unknown,
                        "ORDERBOOK_PARTIAL", provenance);
            }
            return BrokerSurfaceResponse.available(data, provenance);
        } catch (BrokerException exception) {
            return failure(exception, "/api/v1/orderbook");
        } catch (RuntimeException exception) {
            return failure(BrokerErrorCategory.CONTRACT, "/api/v1/orderbook");
        }
    }

    public BrokerSurfaceResponse<BrokerSurfaceResponse.CandleSeriesView> candles(
            UUID userId,
            UUID connectionId,
            String rawSymbol,
            String interval,
            int count,
            String before,
            boolean adjusted) {
        requireReady(userId, connectionId);
        if (marketData == null) {
            return BrokerSurfaceResponse.unavailable("PROVIDER_UNSUPPORTED");
        }
        var symbol = symbol(rawSymbol);
        if (!"1m".equals(interval) && !"1d".equals(interval)) {
            throw BrokerConnectionException.validationFailed();
        }
        if (count < 1 || count > 200) {
            throw BrokerConnectionException.validationFailed();
        }
        try {
            var response = marketData.getCandles(
                    new BrokerConnectionRef(connectionId), symbol, interval, count, before, adjusted);
            var source = response.value();
            var data = new BrokerSurfaceResponse.CandleSeriesView(
                    source.symbol(), source.interval(), source.adjusted(),
                    source.candles().stream().map(candle -> new BrokerSurfaceResponse.CandleView(
                            candle.timestamp(), candle.openPrice(), candle.highPrice(), candle.lowPrice(),
                            candle.closePrice(), candle.volume(), currency(candle.currency()))).toList(),
                    source.nextBefore());
            var unknown = new ArrayList<String>();
            if (source.candles().isEmpty()) unknown.add("candles");
            for (var i = 0; i < source.candles().size(); i++) {
                var candle = source.candles().get(i);
                if (candle.timestamp() == null) unknown.add("candles[" + i + "].timestamp");
                if (candle.openPrice() == null) unknown.add("candles[" + i + "].openPrice");
                if (candle.highPrice() == null) unknown.add("candles[" + i + "].highPrice");
                if (candle.lowPrice() == null) unknown.add("candles[" + i + "].lowPrice");
                if (candle.closePrice() == null) unknown.add("candles[" + i + "].closePrice");
                if (candle.volume() == null) unknown.add("candles[" + i + "].volume");
                if (candle.currency() == null) unknown.add("candles[" + i + "].currency");
            }
            var asOf = source.candles().stream().map(MarketDataAdapter.Candle::timestamp)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            var candleCurrency = commonCurrency(source.candles().stream()
                    .map(MarketDataAdapter.Candle::currency).toList());
            var provenance = List.of(provenance(
                    "/api/v1/candles", currency(candleCurrency), asOf, response.metadata().observedAt()));
            if (!unknown.isEmpty()) {
                return BrokerSurfaceResponse.degraded(data, false, true, unknown,
                        source.candles().isEmpty() ? "CANDLES_EMPTY" : "CANDLES_PARTIAL", provenance);
            }
            return BrokerSurfaceResponse.available(data, provenance);
        } catch (BrokerException exception) {
            return failure(exception, "/api/v1/candles");
        } catch (RuntimeException exception) {
            return failure(BrokerErrorCategory.CONTRACT, "/api/v1/candles");
        }
    }

    public BrokerSurfaceResponse<BrokerSurfaceResponse.ExchangeRateView> exchangeRate(
            UUID userId,
            UUID connectionId,
            String requestedBaseCurrency,
            String requestedQuoteCurrency) {
        requireReady(userId, connectionId);
        if (marketData == null) {
            return BrokerSurfaceResponse.unavailable("PROVIDER_UNSUPPORTED");
        }
        var base = parseCurrency(requestedBaseCurrency);
        var quote = parseCurrency(requestedQuoteCurrency);
        try {
            var response = marketData.getExchangeRate(new BrokerConnectionRef(connectionId), base, quote);
            var source = response.value();
            var data = new BrokerSurfaceResponse.ExchangeRateView(
                    source.baseCurrency().name(), source.quoteCurrency().name(), source.rate(), source.midRate(),
                    source.basisPoint(), source.rateChangeType(), source.validFrom(), source.validUntil());
            var provenance = List.of(provenance(
                    "/api/v1/exchange-rate", source.baseCurrency().name() + "/" + source.quoteCurrency().name(),
                    source.validFrom(), response.metadata().observedAt()));
            if (source.validUntil() != null && !source.validUntil().isAfter(clock.instant())) {
                return BrokerSurfaceResponse.degraded(data, true, false, List.of("validUntil"),
                        "PROVIDER_STALE", provenance);
            }
            return BrokerSurfaceResponse.available(data, provenance);
        } catch (BrokerException exception) {
            return failure(exception, "/api/v1/exchange-rate");
        } catch (RuntimeException exception) {
            return failure(BrokerErrorCategory.CONTRACT, "/api/v1/exchange-rate");
        }
    }

    public BrokerSurfaceResponse<BrokerSurfaceResponse.MarketCalendarView> marketCalendar(
            UUID userId, UUID connectionId, String rawMarket, LocalDate date) {
        requireReady(userId, connectionId);
        if (marketData == null) {
            return BrokerSurfaceResponse.unavailable("PROVIDER_UNSUPPORTED");
        }
        var market = rawMarket == null ? "" : rawMarket.toUpperCase(Locale.ROOT);
        if (!"KR".equals(market) && !"US".equals(market)) {
            throw BrokerConnectionException.validationFailed();
        }
        try {
            var response = marketData.getMarketCalendar(new BrokerConnectionRef(connectionId), market, date);
            var source = response.value();
            var data = new BrokerSurfaceResponse.MarketCalendarView(source.market(), source.payload());
            var provenance = List.of(provenance(
                    "/api/v1/market-calendar/" + market, null, null, response.metadata().observedAt()));
            if (!source.payload().has("today") || source.payload().path("today").isNull()) {
                return BrokerSurfaceResponse.degraded(data, false, true, List.of("today"),
                        "MARKET_CALENDAR_PARTIAL", provenance);
            }
            return BrokerSurfaceResponse.available(data, provenance);
        } catch (BrokerException exception) {
            return failure(exception, "/api/v1/market-calendar/" + market);
        } catch (RuntimeException exception) {
            return failure(BrokerErrorCategory.CONTRACT, "/api/v1/market-calendar/" + market);
        }
    }

    public BrokerSurfaceResponse<BrokerSurfaceResponse.RankingView> ranking(
            UUID userId,
            UUID connectionId,
            String rawType,
            String marketCountry,
            String duration,
            int count) {
        requireReady(userId, connectionId);
        if (marketData == null) {
            return BrokerSurfaceResponse.unavailable("PROVIDER_UNSUPPORTED");
        }
        var type = rankingType(rawType);
        if (type == null) {
            return BrokerSurfaceResponse.unavailable("PROVIDER_UNSUPPORTED_MARKET_CAP_RANKING");
        }
        var country = marketCountry == null ? "US" : marketCountry.toUpperCase(Locale.ROOT);
        if (!"KR".equals(country) && !"US".equals(country)) {
            throw BrokerConnectionException.validationFailed();
        }
        if (count < 1 || count > 100) {
            throw BrokerConnectionException.validationFailed();
        }
        try {
            var requestedDuration = duration == null || duration.isBlank() ? "realtime" : duration;
            var providerDuration = ("TOP_GAINERS".equals(type) || "TOP_LOSERS".equals(type))
                    && "realtime".equalsIgnoreCase(requestedDuration) ? "1d" : requestedDuration;
            var response = marketData.getRanking(
                    new BrokerConnectionRef(connectionId), type, country,
                    providerDuration, count);
            var source = response.value();
            var data = new BrokerSurfaceResponse.RankingView(
                    source.type(), source.marketCountry(), source.duration(), source.rankedAt(),
                    source.items().stream().map(item -> new BrokerSurfaceResponse.RankingItemView(
                            item.rank(), item.symbol(), currency(item.currency()), item.lastPrice(), item.basePrice(),
                            item.changeRate(), item.tradingVolume(), item.tradingAmount(), item.marketCap())).toList());
            var unknown = new ArrayList<String>();
            if (source.items().isEmpty()) unknown.add("items");
            if (source.rankedAt() == null) unknown.add("rankedAt");
            for (var i = 0; i < source.items().size(); i++) {
                var item = source.items().get(i);
                if (item.rank() == null) unknown.add("items[" + i + "].rank");
                if (item.symbol() == null) unknown.add("items[" + i + "].symbol");
                if (item.currency() == null) unknown.add("items[" + i + "].currency");
                if (item.lastPrice() == null) unknown.add("items[" + i + "].lastPrice");
                if (item.basePrice() == null) unknown.add("items[" + i + "].basePrice");
                if (item.changeRate() == null) unknown.add("items[" + i + "].changeRate");
                if (item.tradingVolume() == null) unknown.add("items[" + i + "].tradingVolume");
                if (item.tradingAmount() == null) unknown.add("items[" + i + "].tradingAmount");
            }
            var provenance = List.of(provenance(
                    "/api/v1/rankings", null, source.rankedAt(), response.metadata().observedAt()));
            return unknown.isEmpty()
                    ? BrokerSurfaceResponse.available(data, provenance)
                    : BrokerSurfaceResponse.degraded(data, false, true, unknown,
                    source.items().isEmpty() ? "RANKINGS_EMPTY" : "RANKINGS_PARTIAL", provenance);
        } catch (BrokerException exception) {
            return failure(exception, "/api/v1/rankings");
        } catch (RuntimeException exception) {
            return failure(BrokerErrorCategory.CONTRACT, "/api/v1/rankings");
        }
    }

    private static String rankingType(String raw) {
        return switch (raw == null ? "VOLUME" : raw.toUpperCase(Locale.ROOT)) {
            case "VOLUME", "MARKET_TRADING_VOLUME" -> "MARKET_TRADING_VOLUME";
            case "AMOUNT", "TRADING_AMOUNT", "MARKET_TRADING_AMOUNT" -> "MARKET_TRADING_AMOUNT";
            case "GAINERS", "TOP_GAINERS" -> "TOP_GAINERS";
            case "LOSERS", "TOP_LOSERS" -> "TOP_LOSERS";
            case "MARKET_CAP" -> null;
            default -> throw BrokerConnectionException.validationFailed();
        };
    }

    private <T> BrokerSurfaceResponse<T> failure(BrokerException exception, String endpoint) {
        var reason = reason(exception.category());
        return BrokerSurfaceResponse.unavailable(reason, List.of(provenance(endpoint, null, null, clock.instant())));
    }

    private <T> BrokerSurfaceResponse<T> failure(BrokerErrorCategory category, String endpoint) {
        return BrokerSurfaceResponse.unavailable(reason(category),
                List.of(provenance(endpoint, null, null, clock.instant())));
    }

    private static String reason(BrokerErrorCategory category) {
        return switch (category) {
            case RATE_LIMITED -> "PROVIDER_RATE_LIMITED";
            case NETWORK, TEMPORARY, BROKER_UNAVAILABLE -> "PROVIDER_TIMEOUT";
            case CONTRACT -> "PROVIDER_MALFORMED";
            case NOT_FOUND -> "PROVIDER_NOT_FOUND";
            case VALIDATION, INVALID_REQUEST -> "PROVIDER_INVALID_REQUEST";
            default -> "PROVIDER_UNAVAILABLE";
        };
    }

    private static BrokerSurfaceResponse.ProviderProvenance provenance(
            String endpoint, String currency, java.time.Instant asOf, java.time.Instant observedAt) {
        return new BrokerSurfaceResponse.ProviderProvenance("TOSS", endpoint, currency, asOf, observedAt);
    }

    private static List<BrokerSurfaceResponse.LevelView> levels(List<MarketDataAdapter.Level> source) {
        return source.stream().map(level -> new BrokerSurfaceResponse.LevelView(level.price(), level.volume())).toList();
    }

    private static String currency(Currency value) {
        return value == null ? null : value.name();
    }

    private static Currency commonCurrency(List<Currency> values) {
        return !values.isEmpty()
                && values.stream().allMatch(Objects::nonNull)
                && values.stream().distinct().count() == 1
                ? values.getFirst() : null;
    }

    private static void levelsUnknown(
            List<MarketDataAdapter.Level> source, String side, List<String> unknownFields) {
        for (var i = 0; i < source.size(); i++) {
            if (source.get(i).price() == null) unknownFields.add(side + "[" + i + "].price");
            if (source.get(i).volume() == null) unknownFields.add(side + "[" + i + "].volume");
        }
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
