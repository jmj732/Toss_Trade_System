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
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class TossResponseMapper {

    private static final String BROKER = "toss";

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
        return List.copyOf(holdings.items().stream()
                .filter(item -> "US".equals(item.marketCountry()))
                .map(item -> position(account, item, metadata.observedAt()))
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

    BrokerConnectionRef requireToss(BrokerConnectionRef connection) {
        Objects.requireNonNull(connection, "connection");
        if (!BROKER.equals(connection.broker().toLowerCase(Locale.ROOT))) {
            throw new BrokerException(
                    BrokerErrorCategory.INVALID_REQUEST,
                    null,
                    null,
                    null,
                    null,
                    false,
                    "Broker connection is not Toss");
        }
        return connection;
    }

    BrokerAccountRef requireToss(BrokerAccountRef account) {
        Objects.requireNonNull(account, "account");
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
