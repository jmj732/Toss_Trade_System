package com.jmj.trade.connector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Builds the small, calculation-ready contract consumed by Investment OS. */
public final class PortfolioStateBuilder {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PortfolioStateBuilder() {
    }

    public static ConnectorResponse.PortfolioState build(
            ConnectorResponse.Portfolio portfolio,
            List<ConnectorResponse.Order> openOrders
    ) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(openOrders, "openOrders");

        var currency = primaryCurrency(portfolio);
        var marketValue = amount(portfolio.account(), currency);
        var cash = buyingPower(portfolio, currency);
        var totalValue = marketValue == null || cash == null ? null : marketValue.add(cash);
        var positions = portfolio.positions().stream()
                .map(position -> position(position, currency, totalValue))
                .toList();
        var largestPositionPct = largestPositionPct(positions, totalValue);

        var missing = new LinkedHashSet<>(portfolio.missingSections());
        var unknown = new LinkedHashSet<>(portfolio.unknownFields());
        if (marketValue == null) unknown.add("account.totalValue");
        if (cash == null) missing.add("CASH");
        if (totalValue == null) unknown.add("account.totalValue");
        portfolio.positions().stream()
                .filter(position -> currency != null && position.currency() != null
                        && !currency.equalsIgnoreCase(position.currency()))
                .map(ConnectorResponse.Position::symbol)
                .map(symbol -> "positions[" + symbol + "].weightPct")
                .forEach(unknown::add);
        var partial = portfolio.partial() || totalValue == null;
        var sourceAccount = portfolio.account();

        return new ConnectorResponse.PortfolioState(
                portfolio.completedAt(),
                sourceAsOf(portfolio),
                portfolio.completedAt(),
                currency,
                new ConnectorResponse.StateAccount(
                        totalValue,
                        cash,
                        percentage(cash, totalValue),
                        sourceAccount == null ? null : sourceAccount.profitLossAmounts(),
                        sourceAccount == null ? null : sourceAccount.dailyProfitLossAmounts(),
                        sourceAccount == null ? null : sourceAccount.profitLossRate(),
                        sourceAccount == null ? null : sourceAccount.dailyProfitLossRate()),
                positions,
                List.copyOf(openOrders),
                new ConnectorResponse.Risk(
                        largestPositionPct,
                        percentage(marketValue, totalValue),
                        percentage(cash, totalValue),
                        positions.size()),
                portfolio.stale(),
                portfolio.staleReason(),
                partial,
                List.copyOf(missing),
                List.copyOf(unknown));
    }

    /** Returns zero only for a known empty position set; unknown weights never become zero. */
    private static BigDecimal largestPositionPct(
            List<ConnectorResponse.StatePosition> positions,
            BigDecimal totalValue
    ) {
        if (totalValue == null) return null;
        if (positions.isEmpty()) return BigDecimal.ZERO;
        return positions.stream()
                .map(ConnectorResponse.StatePosition::weightPct)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    /**
     * The aggregate is conservative: it is valid only as of the oldest constituent broker
     * observation. A persisted sync completion remains the separate {@code syncedAt} timestamp.
     */
    private static Instant sourceAsOf(ConnectorResponse.Portfolio portfolio) {
        var observed = new ArrayList<Instant>();
        if (portfolio.account() != null && portfolio.account().observedAt() != null) {
            observed.add(portfolio.account().observedAt());
        }
        portfolio.positions().stream()
                .map(ConnectorResponse.Position::observedAt)
                .filter(Objects::nonNull)
                .forEach(observed::add);
        portfolio.buyingPower().values().stream()
                .map(ConnectorResponse.BuyingPower::observedAt)
                .filter(Objects::nonNull)
                .forEach(observed::add);
        return observed.stream().min(Instant::compareTo).orElse(null);
    }

    private static ConnectorResponse.StatePosition position(
            ConnectorResponse.Position source,
            String primaryCurrency,
            BigDecimal totalValue
    ) {
        var sameCurrency = primaryCurrency != null
                && primaryCurrency.equalsIgnoreCase(source.currency());
        var marketValue = source.marketValueAmount();
        return new ConnectorResponse.StatePosition(
                source.symbol(),
                source.quantity(),
                source.averagePrice(),
                source.lastPrice(),
                marketValue,
                sameCurrency ? percentage(marketValue, totalValue) : null,
                source.profitLossRate() == null
                        ? null
                        : source.profitLossRate().multiply(HUNDRED).stripTrailingZeros(),
                source.currency());
    }

    private static String primaryCurrency(ConnectorResponse.Portfolio portfolio) {
        var account = portfolio.account();
        var marketValues = account == null ? null : account.marketValueAmounts();
        if (contains(marketValues, "USD") && portfolio.buyingPower().containsKey("USD")) return "USD";
        if (contains(marketValues, "KRW") && portfolio.buyingPower().containsKey("KRW")) return "KRW";
        if (contains(marketValues, "USD") || portfolio.buyingPower().containsKey("USD")) return "USD";
        if (contains(marketValues, "KRW") || portfolio.buyingPower().containsKey("KRW")) return "KRW";
        if (account != null && account.marketValueAmounts() != null && !account.marketValueAmounts().isEmpty()) {
            return account.marketValueAmounts().keySet().stream().sorted().findFirst().orElse(null);
        }
        return portfolio.positions().stream()
                .map(ConnectorResponse.Position::currency)
                .filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .findFirst()
                .orElse(null);
    }

    private static boolean contains(java.util.Map<String, BigDecimal> values, String currency) {
        return values != null && values.containsKey(currency);
    }

    private static BigDecimal amount(ConnectorResponse.Account account, String currency) {
        return account == null || currency == null || account.marketValueAmounts() == null
                ? null : account.marketValueAmounts().get(currency);
    }

    private static BigDecimal buyingPower(ConnectorResponse.Portfolio portfolio, String currency) {
        if (currency == null) return null;
        var value = portfolio.buyingPower().get(currency);
        return value == null ? null : value.cashBuyingPower();
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null) return null;
        if (denominator.signum() == 0) return numerator.signum() == 0 ? BigDecimal.ZERO : null;
        return numerator.multiply(HUNDRED)
                .divide(denominator, 8, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }
}
