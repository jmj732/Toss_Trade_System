package com.jmj.trade.sheets;

import com.jmj.trade.connector.ConnectorResponse;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Pure, deterministic mapping between the canonical broker snapshot and Sheet rows. */
public final class InvestmentOsSheetModel {

    public static final String ACCOUNT_1 = "ACCOUNT_1";
    public static final String ACCOUNT_2 = "ACCOUNT_2";
    private static final List<String> CANONICAL_SOURCE_ORDER = List.of(
            "USER_SCREENSHOT", "TOSS_API", "TOSS_QUOTE_API");
    // One-time history cutover; keep fixed across restarts and routine deployments.
    public static final Instant ORDER_HISTORY_CUTOVER = Instant.parse("2026-09-21T12:35:53Z");

    private static final List<String> ACCOUNT_HEADERS = List.of(
            "Account", "Ticker", "Asset Type", "Currency", "Quantity", "Avg Cost",
            "Current Price", "Market Value", "Cash", "Source", "Confidence", "Synced At",
            "Price Source", "Price Synced At", "State");
    private static final List<String> LEGACY_ACCOUNT_HEADERS = List.of(
            "asOf", "Account", "Asset", "Quantity", "Avg Cost", "Currency", "State", "Source",
            "Confidence", "Synced At", "Notes", "Current Price", "Market Value", "Price Source", "Price Synced At");
    private static final List<String> ORDER_HEADERS = List.of(
            "Account", "Order ID", "Ticker", "Side", "Type", "Currency", "Quantity",
            "Filled Quantity", "Order Price", "Average Filled Price", "Status", "Filled At",
            "Source", "Synced At", "Ordered At");
    private static final List<String> AGGREGATE_HEADERS = List.of(
            "Ticker", "Currency", "Quantity", "Combined Avg Cost", "Market Value", "Cash",
            "Accounts Included", "Source Coverage", "Confidence", "Synced At");
    private static final List<String> LEGACY_AGGREGATE_HEADERS = List.of(
            "asOf", "Asset", "Total Quantity", "Combined Avg Cost", "Currency", "State", "Confidence",
            "Source Coverage", "Accounts Included", "Synced At", "Notes", "Market Value");
    private static final List<String> METRICS_HEADERS = List.of(
            "asOf", "Scope", "Total Value", "Cash USD", "Cash %", "High-water Mark", "Drawdown %",
            "SPY Reference", "QQQ Reference", "Notes", "Source Coverage", "Synced At");
    private static final List<String> RECON_HEADERS = List.of(
            "Sync ID", "Account", "Broker", "Holdings", "Cash", "Orders", "Fills",
            "Prices", "Rows Changed", "Mismatch/Gap", "Resolved", "Checked At", "Error");
    private static final List<String> REGISTRY_HEADERS = List.of(
            "Account", "Label", "Sync Mode", "Source", "Default Confidence", "Enabled", "Last Sync", "Notes");

    private InvestmentOsSheetModel() {
    }

    public static SheetTable accountState(
            SheetTable current,
            ConnectorResponse.Portfolio portfolio,
            Instant syncedAt
    ) {
        return accountState(current, portfolio, syncedAt, ACCOUNT_1);
    }

    public static SheetTable accountState(
            SheetTable current,
            ConnectorResponse.Portfolio portfolio,
            Instant syncedAt,
            String accountLabel
    ) {
        var table = accountTable(current);
        if (portfolio == null) return table;
        var managedAccount = normalizedAccountLabel(accountLabel);
        var previousRows = table.rows().stream()
                .filter(row -> managedAccount.equalsIgnoreCase(value(table, row, "Account")))
                .collect(Collectors.toMap(row -> field(table, row, "Ticker", "Asset"),
                        row -> padded(table, row), (first, ignored) -> first, LinkedHashMap::new));
        var rows = table.rows().stream()
                .filter(row -> !managedAccount.equalsIgnoreCase(value(table, row, "Account")))
                .collect(Collectors.toCollection(ArrayList::new));
        var observed = syncedAt == null ? portfolio.completedAt() : syncedAt;
        for (var position : safe(portfolio.positions())) {
            var row = previousRows.getOrDefault(position.symbol(), table.emptyRow());
            put(table, row, "Account", managedAccount);
            putAlias(table, row, position.symbol(), "Ticker", "Asset");
            putOptional(table, row, "Asset Type", "HOLDING");
            putOptional(table, row, "State", "HELD");
            putOptional(table, row, "asOf", observed == null ? null
                    : observed.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString());
            put(table, row, "Currency", position.currency());
            put(table, row, "Quantity", decimal(position.quantity()));
            put(table, row, "Avg Cost", decimal(position.averagePrice()));
            put(table, row, "Source", "TOSS_API");
            put(table, row, "Confidence", "HIGH");
            put(table, row, "Synced At", instant(observed));
            putOptional(table, row, "Notes", "");
            rows.add(row);
        }
        if (portfolio.buyingPower() != null) {
            portfolio.buyingPower().entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        var row = table.emptyRow();
                        put(table, row, "Account", managedAccount);
                        putAlias(table, row, "CASH_" + entry.getKey(), "Ticker", "Asset");
                        putOptional(table, row, "Asset Type", "CASH");
                        putOptional(table, row, "State", "CASH");
                        putOptional(table, row, "asOf", observed == null ? null
                                : observed.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString());
                        put(table, row, "Currency", entry.getKey());
                        var amount = decimal(entry.getValue().cashBuyingPower());
                        putOptional(table, row, "Cash", amount);
                        if (!table.hasColumn("Cash")) put(table, row, "Quantity", amount);
                        put(table, row, "Source", "TOSS_API");
                        put(table, row, "Confidence", "HIGH");
                        put(table, row, "Synced At", instant(observed));
                        putOptional(table, row, "Notes", "");
                        rows.add(row);
                    });
        }
        return table.withRows(rows);
    }

    public static SheetTable openOrders(SheetTable current, List<ConnectorResponse.Order> open, Instant syncedAt) {
        return openOrders(current, open, syncedAt, ACCOUNT_1);
    }

    public static SheetTable openOrders(
            SheetTable current, List<ConnectorResponse.Order> open, Instant syncedAt, String accountLabel
    ) {
        if (open == null) return current;
        var managedAccount = normalizedAccountLabel(accountLabel);
        var source = current;
        var table = new SheetTable(ORDER_HEADERS, List.of());
        var rows = source.rows().stream()
                .filter(row -> !managedAccount.equalsIgnoreCase(value(source, row, "Account"))
                        && isOpenOrder(source, row))
                .map(row -> copyOrderRow(source, row, table))
                .collect(Collectors.toCollection(ArrayList::new));
        var byId = new LinkedHashMap<String, ConnectorResponse.Order>();
        safe(open).stream().filter(InvestmentOsSheetModel::isOpenOrder)
                .filter(order -> order.brokerOrderId() != null && !order.brokerOrderId().isBlank())
                .forEach(order -> byId.put(order.brokerOrderId(), order));
        byId.values().stream().sorted(Comparator.comparing(ConnectorResponse.Order::brokerOrderId))
                .forEach(order -> rows.add(brokerOrderRow(table, order, managedAccount, syncedAt)));
        return table.withRows(rows);
    }

    public static SheetTable orderHistory(SheetTable current, List<ConnectorResponse.Order> closed, Instant syncedAt) {
        return orderHistory(current, List.of(), closed, new SheetTable(List.of(), List.of()), syncedAt, ACCOUNT_1);
    }

    public static SheetTable orderHistory(
            SheetTable current, List<ConnectorResponse.Order> closed, Instant syncedAt, String accountLabel
    ) {
        return orderHistory(current, List.of(), closed, new SheetTable(ORDER_HEADERS, List.of()), syncedAt, accountLabel);
    }

    public static SheetTable orderHistory(
            SheetTable current, List<ConnectorResponse.Order> closed, SheetTable legacyOrders,
            Instant syncedAt, String accountLabel
    ) {
        return orderHistory(current, List.of(), closed, legacyOrders, syncedAt, accountLabel);
    }

    public static SheetTable orderHistory(
            SheetTable current, List<ConnectorResponse.Order> open, List<ConnectorResponse.Order> closed,
            SheetTable legacyOrders, Instant syncedAt, String accountLabel
    ) {
        if (open == null || closed == null) return current;
        var managedAccount = normalizedAccountLabel(accountLabel);
        var source = current;
        var table = new SheetTable(ORDER_HEADERS, List.of());
        var byKey = new LinkedHashMap<String, List<String>>();
        var unkeyed = new ArrayList<List<String>>();
        source.rows().forEach(row -> {
            var copied = copyOrderRow(source, row, table);
            var orderId = value(table, copied, "Order ID");
            if (managedAccount.equalsIgnoreCase(value(table, copied, "Account"))
                    && (!isPostCutoverOrder(table, copied) || !isTerminalOrder(table, copied) || orderId.isBlank())) return;
            if (orderId.isBlank()) unkeyed.add(copied);
            else byKey.put(value(table, copied, "Account") + "|" + orderId, copied);
        });
        var legacy = legacyOrders == null ? new SheetTable(List.of(), List.of()) : legacyOrders;
        legacy.rows().stream()
                .filter(row -> managedAccount.equalsIgnoreCase(value(legacy, row, "Account")))
                .filter(row -> "TOSS_API".equalsIgnoreCase(value(legacy, row, "Source")))
                .filter(row -> isPostCutoverOrder(legacy, row))
                .filter(row -> isTerminalOrder(legacy, row))
                .map(row -> copyOrderRow(legacy, row, table))
                .filter(row -> !value(table, row, "Order ID").isBlank())
                .forEach(row -> byKey.put(value(table, row, "Account") + "|" + value(table, row, "Order ID"), row));
        safe(closed).stream().filter(InvestmentOsSheetModel::isTerminalOrder)
                .filter(InvestmentOsSheetModel::isPostCutoverOrder)
                .filter(order -> order.brokerOrderId() != null && !order.brokerOrderId().isBlank())
                .sorted(Comparator.comparing(ConnectorResponse.Order::brokerOrderId))
                .forEach(order -> byKey.put(managedAccount + "|" + order.brokerOrderId(),
                        brokerOrderRow(table, order, managedAccount, syncedAt)));
        var rows = new ArrayList<>(byKey.values());
        rows.addAll(unkeyed);
        return table.withRows(rows);
    }

    private static boolean isOpenOrder(ConnectorResponse.Order order) {
        return order != null && order.group() == ConnectorResponse.BrokerOrderGroup.OPEN
                && (order.status() == ConnectorResponse.BrokerOrderLifecycle.PENDING
                || order.status() == ConnectorResponse.BrokerOrderLifecycle.PARTIALLY_FILLED
                || order.status() == ConnectorResponse.BrokerOrderLifecycle.CANCELING
                || order.status() == ConnectorResponse.BrokerOrderLifecycle.REPLACING);
    }

    private static boolean isOpenOrder(SheetTable table, List<String> row) {
        return switch (value(table, row, "Status").toUpperCase(Locale.ROOT)) {
            case "OPEN", "PENDING", "PARTIALLY_FILLED", "CANCELING", "REPLACING" -> true;
            default -> false;
        };
    }

    private static boolean isPostCutoverOrder(ConnectorResponse.Order order) {
        return order != null && order.orderedAt() != null && !order.orderedAt().isBefore(ORDER_HISTORY_CUTOVER);
    }

    private static boolean isPostCutoverOrder(SheetTable table, List<String> row) {
        var orderedAt = value(table, row, "Ordered At");
        if (orderedAt.isBlank()) return false;
        try {
            return !Instant.parse(orderedAt).isBefore(ORDER_HISTORY_CUTOVER);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean isTerminalOrder(ConnectorResponse.Order order) {
        return order != null && order.group() == ConnectorResponse.BrokerOrderGroup.CLOSED
                && order.status() != null && switch (order.status()) {
            case FILLED, CANCELED, REJECTED, CANCEL_REJECTED, REPLACE_REJECTED, REPLACED -> true;
            default -> false;
        };
    }

    private static boolean isTerminalOrder(SheetTable table, List<String> row) {
        return switch (value(table, row, "Status").toUpperCase(Locale.ROOT)) {
            case "FILLED", "CANCELED", "REJECTED", "CANCEL_REJECTED", "REPLACE_REJECTED", "REPLACED" -> true;
            default -> false;
        };
    }

    private static List<String> brokerOrderRow(
            SheetTable table, ConnectorResponse.Order order, String account, Instant syncedAt
    ) {
        var row = table.emptyRow();
        put(table, row, "Account", account);
        put(table, row, "Order ID", order.brokerOrderId());
        put(table, row, "Ticker", order.symbol());
        put(table, row, "Side", order.side() == null ? null : order.side().name());
        put(table, row, "Type", order.type() == null ? null : order.type().name());
        put(table, row, "Currency", order.currency());
        put(table, row, "Quantity", decimal(order.quantity()));
        put(table, row, "Filled Quantity", decimal(order.filledQuantity()));
        put(table, row, "Order Price", decimal(order.limitPrice()));
        put(table, row, "Average Filled Price", decimal(order.averageFilledPrice()));
        put(table, row, "Status", order.status() == null ? null : order.status().name());
        put(table, row, "Filled At", instant(order.filledAt()));
        put(table, row, "Source", "TOSS_API");
        put(table, row, "Synced At", instant(syncedAt));
        put(table, row, "Ordered At", instant(order.orderedAt()));
        return row;
    }

    private static List<String> copyOrderRow(SheetTable source, List<String> row, SheetTable target) {
        var result = target.emptyRow();
        put(target, result, "Account", value(source, row, "Account"));
        put(target, result, "Order ID", value(source, row, "Order ID"));
        putAlias(target, result, firstNonblank(source, row, "Ticker", "Asset"), "Ticker");
        put(target, result, "Side", value(source, row, "Side"));
        put(target, result, "Type", value(source, row, "Type"));
        put(target, result, "Currency", value(source, row, "Currency"));
        put(target, result, "Quantity", value(source, row, "Quantity"));
        put(target, result, "Filled Quantity", value(source, row, "Filled Quantity"));
        put(target, result, "Order Price", firstNonblank(source, row, "Order Price", "Condition/Price"));
        put(target, result, "Average Filled Price", firstNonblank(source, row,
                "Average Filled Price", "Filled Price"));
        put(target, result, "Status", value(source, row, "Status"));
        put(target, result, "Filled At", value(source, row, "Filled At"));
        put(target, result, "Source", value(source, row, "Source"));
        put(target, result, "Synced At", value(source, row, "Synced At"));
        put(target, result, "Ordered At", value(source, row, "Ordered At"));
        return result;
    }

    public static SheetTable aggregate(SheetTable accountState, Instant syncedAt) {
        return aggregate(new SheetTable(List.of(), List.of()), accountState, syncedAt);
    }

    public static SheetTable aggregate(
            SheetTable current,
            SheetTable accountState,
            Instant syncedAt
    ) {
        var table = aggregateTable(current);
        var source = accountTable(accountState);
        var holdings = new LinkedHashMap<String, Aggregate>();
        var cash = new LinkedHashMap<String, Aggregate>();
        for (var row : source.rows()) {
            var account = value(source, row, "Account");
            if (!ACCOUNT_1.equalsIgnoreCase(account) && !ACCOUNT_2.equalsIgnoreCase(account)) continue;
            var ticker = field(source, row, "Ticker", "Asset");
            var currency = value(source, row, "Currency");
            if (ticker.isBlank() || currency.isBlank()) continue;
            var target = isCash(source, row, ticker) ? cash : holdings;
            var key = ticker + "|" + currency;
            target.computeIfAbsent(key, ignored -> new Aggregate(ticker, currency))
                    .add(account, coverageSource(source, row), value(source, row, "Confidence"),
                            decimalValue(field(source, row, "Quantity")),
                            decimalValue(value(source, row, "Avg Cost")),
                            decimalValue(value(source, row, "Current Price")),
                            decimalValue(field(source, row, "Cash", "Quantity")));
        }
        var rows = new ArrayList<List<String>>();
        var observed = instant(syncedAt);
        holdings.values().stream().sorted(Aggregate.ORDER).forEach(item -> rows.add(item.row(table, observed, false)));
        cash.values().stream().sorted(Aggregate.ORDER).forEach(item -> rows.add(item.row(table, observed, true)));
        return table.withRows(rows);
    }

    public static SheetTable accountRegistry(SheetTable current, Instant syncedAt) {
        var table = current.withHeaders(REGISTRY_HEADERS);
        if (!hasAccountRegistryAccount(table, ACCOUNT_1)) return table;
        var rows = new ArrayList<List<String>>();
        for (var sourceRow : table.rows()) {
            var row = padded(table, sourceRow);
            if (ACCOUNT_1.equalsIgnoreCase(value(table, row, "Account"))) {
                put(table, row, "Last Sync", instant(syncedAt));
                putOptional(table, row, "Notes", "");
            }
            rows.add(row);
        }
        return table.withRows(rows);
    }

    public static boolean hasAccountRegistryAccount(SheetTable registry, String account) {
        return registry.rows().stream()
                .filter(row -> account.equalsIgnoreCase(value(registry, row, "Account"))).count() == 1;
    }

    public static SheetTable refreshPrices(
            SheetTable current,
            List<BrokerSurfaceResponse.PriceView> prices,
            Instant syncedAt
    ) {
        var table = accountTable(current);
        var bySymbol = safe(prices).stream().filter(price -> price != null && price.symbol() != null
                        && price.lastPrice() != null && price.lastPrice().signum() > 0)
                .collect(Collectors.toMap(price -> price.symbol().toUpperCase(Locale.ROOT), price -> price,
                        (first, ignored) -> first, LinkedHashMap::new));
        var rows = new ArrayList<List<String>>();
        for (var sourceRow : table.rows()) {
            if (!isPortfolioAccount(value(table, sourceRow, "Account"))) {
                rows.add(sourceRow);
                continue;
            }
            var row = padded(table, sourceRow);
            var ticker = field(table, row, "Ticker", "Asset");
            var price = bySymbol.get(ticker.toUpperCase(Locale.ROOT));
            if (!ticker.isBlank() && price != null && !isCash(table, row, ticker)
                    && (price.currency() == null || price.currency().equalsIgnoreCase(value(table, row, "Currency")))) {
                put(table, row, "Current Price", decimal(price.lastPrice()));
                put(table, row, "Price Source", "TOSS_QUOTE_API");
                put(table, row, "Price Synced At", instant(price.observedAt() == null ? syncedAt : price.observedAt()));
                var quantity = decimalValue(field(table, row, "Quantity"));
                if (quantity != null) {
                    put(table, row, "Market Value", decimal(quantity.multiply(price.lastPrice()).setScale(2, RoundingMode.HALF_UP)));
                }
            }
            rows.add(row);
        }
        return table.withRows(rows);
    }

    public static List<String> heldSymbols(SheetTable accountState) {
        var source = accountTable(accountState);
        var symbols = new java.util.TreeSet<String>();
        for (var row : source.rows()) {
            if (!isPortfolioAccount(value(source, row, "Account"))) continue;
            var ticker = field(source, row, "Ticker", "Asset");
            if (!ticker.isBlank() && !isCash(source, row, ticker)) symbols.add(ticker.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(symbols);
    }

    public static boolean hasCompleteQuotes(SheetTable accountState) {
        var source = accountTable(accountState);
        for (var row : source.rows()) {
            if (!isPortfolioAccount(value(source, row, "Account"))) continue;
            var ticker = field(source, row, "Ticker", "Asset");
            if (ticker.isBlank() || isCash(source, row, ticker)) continue;
            var currentPrice = decimalValue(value(source, row, "Current Price"));
            if (!"TOSS_QUOTE_API".equals(value(source, row, "Price Source"))
                    || currentPrice == null || currentPrice.signum() <= 0
                    || decimalValue(value(source, row, "Market Value")) == null) return false;
        }
        return true;
    }

    private static boolean isPortfolioAccount(String account) {
        return ACCOUNT_1.equalsIgnoreCase(account) || ACCOUNT_2.equalsIgnoreCase(account);
    }

    public static SheetTable portfolioMetrics(
            SheetTable current,
            SheetTable accountState,
            Instant syncedAt
    ) {
        var table = current.withHeaders(METRICS_HEADERS);
        var source = accountTable(accountState);
        var prior = new LinkedHashMap<String, List<String>>();
        for (var row : table.rows()) prior.putIfAbsent(value(table, row, "Scope").toUpperCase(Locale.ROOT), padded(table, row));
        var account1 = totals(source, ACCOUNT_1);
        var account2 = totals(source, ACCOUNT_2);
        var replacements = new LinkedHashMap<String, List<String>>();
        if (account1 != null) replacements.put(ACCOUNT_1, metricRow(table, prior.get(ACCOUNT_1), ACCOUNT_1, account1, syncedAt));
        if (account2 != null) replacements.put(ACCOUNT_2, metricRow(table, prior.get(ACCOUNT_2), ACCOUNT_2, account2, syncedAt));
        var first = account1;
        var second = account2;
        if (first != null && second != null) {
            var coverage = new LinkedHashSet<String>();
            coverage.addAll(first.coverage());
            coverage.addAll(second.coverage());
            replacements.put("COMBINED", metricRow(table, prior.get("COMBINED"), "COMBINED",
                    new PortfolioTotals(first.totalValue().add(second.totalValue()),
                            first.cashUsd().add(second.cashUsd()), coverage), syncedAt));
        }
        var rows = table.rows().stream()
                .filter(row -> !replacements.containsKey(value(table, row, "Scope").toUpperCase(Locale.ROOT)))
                .collect(Collectors.toCollection(ArrayList::new));
        rows.addAll(replacements.values());
        return table.withRows(rows);
    }

    private static PortfolioTotals totals(SheetTable accountState, String account) {
        BigDecimal holdings = BigDecimal.ZERO;
        BigDecimal cashUsd = null;
        var coverage = new LinkedHashSet<String>();
        for (var row : accountState.rows()) {
            if (!account.equalsIgnoreCase(value(accountState, row, "Account"))) continue;
            var ticker = field(accountState, row, "Ticker", "Asset");
            if (ticker.isBlank()) continue;
            var source = value(accountState, row, "Source");
            var priceSource = value(accountState, row, "Price Source");
            if (!source.isBlank()) coverage.add(account + ":" + source);
            if (!priceSource.isBlank()) coverage.add(account + ":" + priceSource);
            if (isCash(accountState, row, ticker)) {
                if ("USD".equalsIgnoreCase(value(accountState, row, "Currency"))) {
                    cashUsd = decimalValue(field(accountState, row, "Cash", "Quantity"));
                    if (cashUsd == null) return null;
                }
                continue;
            }
            if (!"USD".equalsIgnoreCase(value(accountState, row, "Currency"))) return null;
            var marketValue = decimalValue(value(accountState, row, "Market Value"));
            if (marketValue == null) return null;
            holdings = holdings.add(marketValue);
        }
        if (cashUsd == null) return null;
        return new PortfolioTotals(holdings.add(cashUsd), cashUsd, coverage);
    }

    private static List<String> metricRow(
            SheetTable table, List<String> previous, String scope, PortfolioTotals totals, Instant syncedAt
    ) {
        var row = previous == null ? table.emptyRow() : padded(table, previous);
        var priorHigh = decimalValue(value(table, row, "High-water Mark"));
        var highWater = priorHigh == null || totals.totalValue().compareTo(priorHigh) > 0
                ? totals.totalValue() : priorHigh;
        var drawdown = highWater.signum() == 0 ? BigDecimal.ZERO
                : totals.totalValue().subtract(highWater).multiply(BigDecimal.valueOf(100))
                .divide(highWater, 2, RoundingMode.HALF_UP);
        var note = "USD total = USD holdings market value + USD cash; KRW cash excluded.";
        if (priorHigh == null) note += " HWM starts at first verified quote; older history unavailable.";
        putOptional(table, row, "asOf", syncedAt.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString());
        put(table, row, "Scope", scope);
        put(table, row, "Total Value", decimal(totals.totalValue().setScale(2, RoundingMode.HALF_UP)));
        put(table, row, "Cash USD", decimal(totals.cashUsd().setScale(2, RoundingMode.HALF_UP)));
        put(table, row, "Cash %", percent(totals.cashUsd(), totals.totalValue()));
        put(table, row, "High-water Mark", decimal(highWater.setScale(2, RoundingMode.HALF_UP)));
        put(table, row, "Drawdown %", decimal(drawdown) + "%");
        put(table, row, "Notes", note);
        put(table, row, "Source Coverage", String.join("; ", totals.coverage()));
        put(table, row, "Synced At", instant(syncedAt));
        return row;
    }

    private static String percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) return null;
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP) + "%";
    }

    private static SheetTable accountTable(SheetTable current) {
        return current.withHeaders(current.hasColumn("Asset") && !current.hasColumn("Ticker")
                ? LEGACY_ACCOUNT_HEADERS : ACCOUNT_HEADERS);
    }

    private static SheetTable aggregateTable(SheetTable current) {
        return current.withHeaders(current.hasColumn("Asset") && !current.hasColumn("Ticker")
                ? LEGACY_AGGREGATE_HEADERS : AGGREGATE_HEADERS);
    }

    private static boolean isCash(SheetTable table, List<String> row, String ticker) {
        return "CASH".equalsIgnoreCase(field(table, row, "Asset Type", "State"))
                || ticker.toUpperCase(Locale.ROOT).startsWith("CASH_");
    }

    private static String coverageSource(SheetTable table, List<String> row) {
        var source = value(table, row, "Source");
        var priceSource = value(table, row, "Price Source");
        return priceSource.isBlank() ? source : source.isBlank() ? priceSource : source + "+" + priceSource;
    }

    private static void addSources(String raw, LinkedHashSet<String> target) {
        if (raw == null || raw.isBlank()) return;
        for (var source : raw.split("[+;]")) {
            var normalized = source.trim();
            if (!normalized.isBlank()) target.add(normalized);
        }
    }

    private static List<String> padded(SheetTable table, List<String> row) {
        var result = new ArrayList<>(row);
        while (result.size() < table.headers().size()) result.add("");
        return result;
    }

    private static String field(SheetTable table, List<String> row, String... names) {
        for (var name : names) if (table.hasColumn(name)) return value(table, row, name);
        return "";
    }

    private static String firstNonblank(SheetTable table, List<String> row, String... names) {
        for (var name : names) {
            var value = value(table, row, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static void putAlias(SheetTable table, List<String> row, String value, String... names) {
        for (var name : names) {
            if (table.hasColumn(name)) {
                put(table, row, name, value);
                return;
            }
        }
        throw new IllegalArgumentException("missing sheet column: " + String.join("/", names));
    }

    private static void putOptional(SheetTable table, List<String> row, String header, String value) {
        if (table.hasColumn(header)) put(table, row, header, value);
    }

    public static SheetTable reconciliation(
            SheetTable current,
            String syncId,
            String account,
            String holdings,
            String cash,
            String orders,
            String fills,
            String prices,
            int rowsChanged,
            String mismatch,
            boolean resolved,
            Instant checkedAt,
            String error
    ) {
        var table = current.withHeaders(RECON_HEADERS);
        var row = table.emptyRow();
        put(table, row, "Sync ID", syncId);
        put(table, row, "Account", account);
        put(table, row, "Broker", "TOSS_API");
        put(table, row, "Holdings", holdings);
        put(table, row, "Cash", cash);
        put(table, row, "Orders", orders);
        put(table, row, "Fills", fills);
        put(table, row, "Prices", prices);
        put(table, row, "Rows Changed", Integer.toString(rowsChanged));
        put(table, row, "Mismatch/Gap", mismatch);
        put(table, row, "Resolved", Boolean.toString(resolved));
        put(table, row, "Checked At", instant(checkedAt));
        put(table, row, "Error", error);
        var rows = new ArrayList<>(table.rows());
        if (!rows.isEmpty() && sameReconciliationState(table, rows.getLast(), row)) {
            rows.set(rows.size() - 1, row);
        } else {
            rows.add(row);
        }
        return table.withRows(rows);
    }

    private static boolean sameReconciliationState(SheetTable table, List<String> previous, List<String> current) {
        for (var header : List.of("Account", "Broker", "Holdings", "Cash", "Orders", "Fills", "Prices",
                "Mismatch/Gap", "Resolved", "Error")) {
            if (!value(table, previous, header).equals(value(table, current, header))) return false;
        }
        return true;
    }

    public static List<String> accountHeaders() { return ACCOUNT_HEADERS; }
    public static List<String> orderHeaders() { return ORDER_HEADERS; }
    public static List<String> aggregateHeaders() { return AGGREGATE_HEADERS; }
    public static List<String> metricsHeaders() { return METRICS_HEADERS; }
    public static List<String> reconciliationHeaders() { return RECON_HEADERS; }

    public record SheetTable(List<String> headers, List<List<String>> rows) {
        public SheetTable {
            headers = headers == null ? List.of() : List.copyOf(headers);
            rows = rows == null ? List.of() : rows.stream()
                    .map(row -> row == null ? List.<String>of() : List.copyOf(row))
                    .toList();
        }

        SheetTable withHeaders(List<String> required) {
            var result = new ArrayList<>(headers);
            for (var header : required) {
                if (find(result, header) < 0) result.add(header);
            }
            return new SheetTable(result, rows);
        }

        SheetTable withRows(List<List<String>> values) {
            return new SheetTable(headers, values);
        }

        List<String> emptyRow() {
            return new ArrayList<>(java.util.Collections.nCopies(headers.size(), ""));
        }

        public int column(String header) {
            var index = find(headers, header);
            if (index < 0) throw new IllegalArgumentException("missing sheet column: " + header);
            return index;
        }

        public boolean hasColumn(String header) {
            return find(headers, header) >= 0;
        }
    }

    private static int find(List<String> headers, String wanted) {
        var normalized = normalize(wanted);
        for (var i = 0; i < headers.size(); i++) {
            if (normalize(headers.get(i)).equals(normalized)) return i;
        }
        return -1;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String normalizedAccountLabel(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNT_1.equals(normalized) && !ACCOUNT_2.equals(normalized)) {
            throw new IllegalArgumentException("accountLabel must be ACCOUNT_1 or ACCOUNT_2");
        }
        return normalized;
    }

    private static String value(SheetTable table, List<String> row, String header) {
        var index = find(table.headers(), header);
        return index < 0 || index >= row.size() || row.get(index) == null ? "" : row.get(index);
    }

    private static void put(SheetTable table, List<String> row, String header, String value) {
        var index = table.column(header);
        row.set(index, value == null ? "" : value);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String instant(Instant value) { return value == null ? null : value.toString(); }

    private static String localDate(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            return Instant.parse(value).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private static BigDecimal decimalValue(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new BigDecimal(value.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    private static final class Aggregate {
        static final Comparator<Aggregate> ORDER = Comparator.comparing(item -> item.ticker + item.currency);
        final String ticker;
        final String currency;
        final LinkedHashSet<String> accounts = new LinkedHashSet<>();
        final LinkedHashSet<String> sources = new LinkedHashSet<>();
        boolean allHigh = true;
        BigDecimal quantity;
        boolean quantityKnown = true;
        BigDecimal costBasis;
        boolean avgKnown = true;
        BigDecimal currentPrice;
        boolean priceKnown = true;
        BigDecimal cash;
        boolean cashKnown = true;

        Aggregate(String ticker, String currency) {
            this.ticker = ticker;
            this.currency = currency;
        }

        void add(String account, String source, String confidence, BigDecimal qty, BigDecimal avg,
                 BigDecimal price, BigDecimal cashAmount) {
            accounts.add(account);
            addSources(source, sources);
            allHigh &= "HIGH".equalsIgnoreCase(confidence);
            if (qty != null) {
                quantity = zero(quantity).add(qty);
                if (avg == null) avgKnown = false;
                else costBasis = zero(costBasis).add(qty.multiply(avg));
            } else {
                quantityKnown = false;
                avgKnown = false;
            }
            if (price == null || (currentPrice != null && currentPrice.compareTo(price) != 0)) priceKnown = false;
            else if (currentPrice == null) currentPrice = price;
            if (cashAmount == null) cashKnown = false;
            else cash = zero(cash).add(cashAmount);
        }

        List<String> row(SheetTable table, String syncedAt, boolean isCash) {
            var row = table.emptyRow();
            putAlias(table, row, ticker, "Ticker", "Asset");
            put(table, row, "Currency", currency);
            if (!isCash) {
                putAlias(table, row, quantityKnown ? decimal(quantity) : null, "Quantity", "Total Quantity");
                put(table, row, "Combined Avg Cost", !quantityKnown || !avgKnown || quantity == null || quantity.signum() == 0
                        || costBasis == null ? null : decimal(costBasis.divide(quantity, 8, RoundingMode.HALF_UP)));
                var marketValue = !quantityKnown || !priceKnown || quantity == null || currentPrice == null
                        ? null : quantity.multiply(currentPrice).setScale(2, RoundingMode.HALF_UP);
                put(table, row, "Market Value", decimal(marketValue));
            } else if (table.hasColumn("Cash")) {
                put(table, row, "Cash", cashKnown ? decimal(cash) : null);
            } else {
                putAlias(table, row, cashKnown ? decimal(cash) : null, "Quantity", "Total Quantity");
            }
            putOptional(table, row, "Asset Type", isCash ? "CASH" : "HOLDING");
            putOptional(table, row, "State", isCash ? "CASH" : "HELD");
            put(table, row, "Accounts Included", String.join(",", accounts));
            put(table, row, "Source Coverage", canonicalSources(sources));
            put(table, row, "Confidence", allHigh ? "HIGH" : "MEDIUM");
            put(table, row, "Synced At", syncedAt);
            putOptional(table, row, "asOf", localDate(syncedAt));
            putOptional(table, row, "Notes", isCash ? "Combined reported cash; currencies remain separate."
                    : "Market value uses latest quote × combined quantity.");
            return row;
        }

        private static String canonicalSources(LinkedHashSet<String> sources) {
            var ordered = new ArrayList<String>();
            for (var preferred : CANONICAL_SOURCE_ORDER) {
                if (sources.contains(preferred)) ordered.add(preferred);
            }
            sources.stream()
                    .filter(source -> !CANONICAL_SOURCE_ORDER.contains(source))
                    .forEach(ordered::add);
            return String.join("+", ordered);
        }

        private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    }

    private record PortfolioTotals(BigDecimal totalValue, BigDecimal cashUsd, LinkedHashSet<String> coverage) {
    }
}
