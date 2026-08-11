package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.CashBalanceStatus;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.MoneyByCurrency;
import com.jmj.trade.broker.MarketDataAdapter;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderSide;
import com.jmj.trade.broker.BrokerOrderType;
import com.jmj.trade.broker.BrokerOrderView;
import com.jmj.trade.broker.SellableQuantitySnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class TossResponseMapper {

    BrokerAccountView account(BrokerConnectionRef connection, TossApiDtos.Account account) {
        Objects.requireNonNull(connection, "connection");
        if (account == null || account.accountSeq() == null) {
            throw contract();
        }
        var accountType = required(account.accountType());
        var ref = new BrokerAccountRef(
                connection.brokerConnectionId(),
                String.valueOf(account.accountSeq()),
                accountType,
                maskAccountNo(account.accountNo()));
        return new BrokerAccountView(ref, "Toss " + accountType + " " + ref.displayAccountNumber());
    }

    AccountSnapshot accountSnapshot(BrokerAccountRef account, TossApiDtos.Holdings holdings, BrokerCallMetadata metadata) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(metadata, "metadata");
        if (holdings == null || holdings.items() == null || holdings.marketValue() == null
                || holdings.profitLoss() == null || holdings.dailyProfitLoss() == null) {
            throw contract();
        }
        validateItems(account, holdings, metadata);
        return new AccountSnapshot(
                account,
                money(holdings.totalPurchaseAmount()),
                money(holdings.marketValue().amount()),
                money(holdings.marketValue().amountAfterCost()),
                money(holdings.profitLoss().amount()),
                money(holdings.profitLoss().amountAfterCost()),
                decimal(holdings.profitLoss().rate()),
                decimal(holdings.profitLoss().rateAfterCost()),
                money(holdings.dailyProfitLoss().amount()),
                decimal(holdings.dailyProfitLoss().rate()),
                CashBalanceStatus.UNKNOWN,
                metadata.observedAt());
    }

    List<Position> positions(BrokerAccountRef account, TossApiDtos.Holdings holdings, BrokerCallMetadata metadata) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(metadata, "metadata");
        if (holdings == null || holdings.items() == null) {
            throw contract();
        }
        return List.copyOf(validateItems(account, holdings, metadata).stream()
                .filter(position -> "US".equals(position.marketCountry()))
                .toList());
    }

    Quote quote(BrokerConnectionRef connection, String symbol, List<TossApiDtos.Price> prices, BrokerCallMetadata metadata) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(metadata, "metadata");
        if (prices == null || prices.size() != 1) {
            throw contract();
        }
        var price = prices.getFirst();
        if (price == null || !symbol.equals(price.symbol())) {
            throw contract();
        }
        return new Quote(
                connection,
                symbol,
                currency(price.currency()),
                nonNegativeDecimal(price.lastPrice()),
                null,
                null,
                instant(price.timestamp()),
                metadata.observedAt());
    }

    MarketDataAdapter.OrderBook orderBook(
            String symbol,
            TossApiDtos.OrderBook source,
            BrokerCallMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (source == null || source.asks() == null || source.bids() == null) {
            throw contract();
        }
        return new MarketDataAdapter.OrderBook(
                symbol,
                instant(source.timestamp()),
                currency(source.currency()),
                levels(source.asks()),
                levels(source.bids()));
    }

    MarketDataAdapter.CandleSeries candles(
            String symbol,
            String interval,
            boolean adjusted,
            TossApiDtos.CandleSeries source,
            BrokerCallMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (source == null || source.candles() == null) {
            throw contract();
        }
        return new MarketDataAdapter.CandleSeries(
                symbol,
                interval,
                adjusted,
                source.candles().stream().map(this::candle).toList(),
                instant(source.nextBefore()));
    }

    MarketDataAdapter.ExchangeRate exchangeRate(
            TossApiDtos.ExchangeRate source,
            BrokerCallMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (source == null) {
            throw contract();
        }
        return new MarketDataAdapter.ExchangeRate(
                currency(source.baseCurrency()),
                currency(source.quoteCurrency()),
                nonNegativeDecimal(source.rate()),
                nonNegativeDecimal(source.midRate()),
                decimal(source.basisPoint()),
                required(source.rateChangeType()),
                instant(source.validFrom()),
                instant(source.validUntil()));
    }

    MarketDataAdapter.MarketCalendar marketCalendar(
            String market,
            tools.jackson.databind.JsonNode source,
            BrokerCallMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (source == null || source.isNull()) {
            throw contract();
        }
        return new MarketDataAdapter.MarketCalendar(market, source);
    }

    MarketDataAdapter.Ranking ranking(
            TossApiDtos.Rankings source,
            String type,
            String marketCountry,
            String duration,
            BrokerCallMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (source == null || source.rankings() == null) {
            throw contract();
        }
        return new MarketDataAdapter.Ranking(
                required(type),
                required(marketCountry),
                required(duration),
                instant(source.rankedAt()),
                source.rankings().stream().map(this::rankingItem).toList());
    }

    AccountCapacitySnapshot capacity(BrokerAccountRef account, Currency requested, TossApiDtos.BuyingPower buyingPower, BrokerCallMetadata metadata) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(metadata, "metadata");
        if (buyingPower == null || currency(buyingPower.currency()) != requested) {
            throw contract();
        }
        return new AccountCapacitySnapshot(
                account,
                requested,
                nonNegativeDecimal(buyingPower.cashBuyingPower()),
                metadata.observedAt());
    }

    SellableQuantitySnapshot sellableQuantity(
            BrokerAccountRef account,
            String symbol,
            TossApiDtos.SellableQuantity sellable,
            BrokerCallMetadata metadata) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(metadata, "metadata");
        if (sellable == null) {
            throw contract();
        }
        return SellableQuantitySnapshot.known(
                account,
                symbol,
                nonNegativeDecimal(sellable.sellableQuantity()),
                metadata.observedAt());
    }

    BrokerConnectionRef requireToss(BrokerConnectionRef connection) {
        return Objects.requireNonNull(connection, "connection");
    }

    BrokerOrderView order(TossApiDtos.Order order) {
        if (order == null || order.orderId() == null || order.orderId().isBlank()
                || order.execution() == null) {
            throw contract();
        }
        return new BrokerOrderView(
                order.orderId(),
                null,
                orderSide(order.side()),
                orderType(order.orderType()),
                required(order.symbol()),
                nonNegativeDecimal(order.quantity()),
                nonNegativeDecimal(order.execution().filledQuantity()),
                order.price() == null ? null : nonNegativeDecimal(order.price()),
                currency(order.currency()),
                lifecycle(order.status()));
    }

    private BrokerOrderSide orderSide(String raw) {
        try {
            return BrokerOrderSide.valueOf(required(raw));
        } catch (RuntimeException exception) {
            throw contract();
        }
    }

    private BrokerOrderType orderType(String raw) {
        try {
            return BrokerOrderType.valueOf(required(raw));
        } catch (RuntimeException exception) {
            throw contract();
        }
    }

    private BrokerOrderLifecycle lifecycle(String raw) {
        return switch (required(raw)) {
            case "PENDING" -> BrokerOrderLifecycle.PENDING;
            case "PENDING_CANCEL" -> BrokerOrderLifecycle.CANCELING;
            case "PENDING_REPLACE" -> BrokerOrderLifecycle.REPLACING;
            case "PARTIAL_FILLED" -> BrokerOrderLifecycle.PARTIALLY_FILLED;
            case "FILLED" -> BrokerOrderLifecycle.FILLED;
            case "CANCELED" -> BrokerOrderLifecycle.CANCELED;
            case "REJECTED" -> BrokerOrderLifecycle.REJECTED;
            case "CANCEL_REJECTED" -> BrokerOrderLifecycle.CANCEL_REJECTED;
            case "REPLACE_REJECTED" -> BrokerOrderLifecycle.REPLACE_REJECTED;
            case "REPLACED" -> BrokerOrderLifecycle.REPLACED;
            default -> throw contract();
        };
    }

    BrokerAccountRef requireToss(BrokerAccountRef account) {
        Objects.requireNonNull(account, "account");
        if (!account.brokerAccountId().matches("\\d+")) {
            throw invalidRequest("Toss accountSeq is invalid");
        }
        try {
            if (Long.parseLong(account.brokerAccountId()) < 0) {
                throw invalidRequest("Toss accountSeq is invalid");
            }
        } catch (NumberFormatException exception) {
            throw invalidRequest("Toss accountSeq is invalid");
        }
        return account;
    }

    String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw invalidRequest("Quote symbol is invalid");
        }
        var normalized = symbol.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9.-]+")) {
            throw invalidRequest("Quote symbol is invalid");
        }
        return normalized;
    }

    private Position position(BrokerAccountRef account, TossApiDtos.HoldingItem item, Instant observedAt) {
        if (item == null || item.marketValue() == null || item.profitLoss() == null
                || item.dailyProfitLoss() == null || item.cost() == null) {
            throw contract();
        }
        return new Position(
                account,
                required(item.symbol()),
                required(item.name()),
                required(item.marketCountry()),
                nonNegativeDecimal(item.quantity()),
                currency(item.currency()),
                nonNegativeDecimal(item.averagePurchasePrice()),
                nonNegativeDecimal(item.lastPrice()),
                nonNegativeDecimal(item.marketValue().purchaseAmount()),
                nonNegativeDecimal(item.marketValue().amount()),
                nonNegativeDecimal(item.marketValue().amountAfterCost()),
                decimal(item.profitLoss().amount()),
                decimal(item.profitLoss().amountAfterCost()),
                decimal(item.profitLoss().rate()),
                decimal(item.profitLoss().rateAfterCost()),
                decimal(item.dailyProfitLoss().amount()),
                decimal(item.dailyProfitLoss().rate()),
                nonNegativeDecimal(item.cost().commission()),
                item.cost().tax() == null ? null : nonNegativeDecimal(item.cost().tax()),
                observedAt);
    }

    private List<MarketDataAdapter.Level> levels(List<TossApiDtos.Level> source) {
        return source.stream()
                .map(level -> level == null
                        ? new MarketDataAdapter.Level(null, null)
                        : new MarketDataAdapter.Level(nullableDecimal(level.price()), nullableDecimal(level.volume())))
                .toList();
    }

    private MarketDataAdapter.Candle candle(TossApiDtos.Candle source) {
        if (source == null) {
            throw contract();
        }
        return new MarketDataAdapter.Candle(
                instant(source.timestamp()),
                nonNegativeDecimal(source.openPrice()),
                nonNegativeDecimal(source.highPrice()),
                nonNegativeDecimal(source.lowPrice()),
                nonNegativeDecimal(source.closePrice()),
                nonNegativeDecimal(source.volume()),
                currency(source.currency()));
    }

    private MarketDataAdapter.RankingItem rankingItem(TossApiDtos.RankingItem source) {
        if (source == null) {
            throw contract();
        }
        return new MarketDataAdapter.RankingItem(
                integer(source.rank()),
                required(source.symbol()),
                currency(source.currency()),
                source.price() == null ? null : nullableDecimal(source.price().lastPrice()),
                source.price() == null ? null : nullableDecimal(source.price().basePrice()),
                source.price() == null ? null : nullableDecimal(source.price().changeRate()),
                nullableDecimal(source.tradingVolume()),
                nullableDecimal(source.tradingAmount()),
                null);
    }

    private List<Position> validateItems(BrokerAccountRef account, TossApiDtos.Holdings holdings, BrokerCallMetadata metadata) {
        return holdings.items().stream()
                .map(item -> position(account, item, metadata.observedAt()))
                .toList();
    }

    private MoneyByCurrency money(TossApiDtos.PriceAmount source) {
        if (source == null || source.krw() == null) {
            throw contract();
        }
        var amounts = new EnumMap<Currency, BigDecimal>(Currency.class);
        amounts.put(Currency.KRW, decimal(source.krw()));
        if (source.usd() != null) {
            amounts.put(Currency.USD, decimal(source.usd()));
        }
        return new MoneyByCurrency(amounts);
    }

    private String maskAccountNo(String accountNo) {
        if (accountNo == null || !accountNo.matches("\\d{5,}")) {
            throw contract();
        }
        return "*".repeat(accountNo.length() - 4) + accountNo.substring(accountNo.length() - 4);
    }

    private Currency currency(String raw) {
        try {
            return Currency.valueOf(required(raw).toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw contract();
        }
    }

    private Instant instant(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(required(raw));
        } catch (DateTimeParseException exception) {
            throw contract();
        }
    }

    private BigDecimal nonNegativeDecimal(String raw) {
        var value = decimal(raw);
        if (value.signum() < 0) {
            throw contract();
        }
        return value;
    }

    private BigDecimal decimal(String raw) {
        try {
            return new BigDecimal(required(raw));
        } catch (RuntimeException exception) {
            throw contract();
        }
    }

    private BigDecimal nullableDecimal(String raw) {
        return raw == null ? null : decimal(raw);
    }

    private int integer(String raw) {
        try {
            return Integer.parseInt(required(raw));
        } catch (RuntimeException exception) {
            throw contract();
        }
    }

    private String required(String raw) {
        if (raw == null || raw.isBlank()) {
            throw contract();
        }
        return raw;
    }

    private BrokerException contract() {
        return new BrokerException(
                BrokerErrorCategory.CONTRACT,
                200,
                null,
                null,
                null,
                false,
                "Toss API response was invalid");
    }

    private BrokerException invalidRequest(String message) {
        return new BrokerException(
                BrokerErrorCategory.INVALID_REQUEST,
                null,
                null,
                null,
                null,
                false,
                message);
    }
}
