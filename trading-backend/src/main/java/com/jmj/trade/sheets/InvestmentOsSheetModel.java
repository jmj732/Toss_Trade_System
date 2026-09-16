package com.jmj.trade.sheets;

import com.jmj.trade.connector.ConnectorResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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

    private static final List<String> ACCOUNT_HEADERS = List.of(
            "Account", "Ticker", "Asset Type", "Currency", "Quantity", "Avg Cost",
            "Current Price", "Market Value", "Cash", "Source", "Confidence", "Synced At");
    private static final List<String> ORDER_HEADERS = List.of(
            "Account", "Order ID", "Ticker", "Side", "Type", "Currency", "Quantity",
            "Filled Quantity", "Order Price", "Average Filled Price", "Status", "Filled At",
            "Source", "Synced At");
    private static final List<String> AGGREGATE_HEADERS = List.of(
            "Ticker", "Currency", "Quantity", "Combined Avg Cost", "Market Value", "Cash",
            "Accounts Included", "Source Coverage", "Confidence", "Synced At");
    private static final List<String> RECON_HEADERS = List.of(
            "Sync ID", "Account", "Broker", "Holdings", "Cash", "Orders", "Fills",
            "Rows Changed", "Mismatch/Gap", "Resolved", "Checked At", "Error");

    private InvestmentOsSheetModel() {
    }

    public static SheetTable accountState(
            SheetTable current,
            ConnectorResponse.Portfolio portfolio,
            Instant syncedAt
    ) {
        var table = current.withHeaders(ACCOUNT_HEADERS);
        if (portfolio == null) return table;

        var rows = table.rows().stream()
                .filter(row -> !ACCOUNT_1.equalsIgnoreCase(value(table, row, "Account")))
                .collect(Collectors.toCollection(ArrayList::new));
        var observed = syncedAt == null ? portfolio.completedAt() : syncedAt;
        for (var position : safe(portfolio.positions())) {
            var row = table.emptyRow();
            put(table, row, "Account", ACCOUNT_1);
            put(table, row, "Ticker", position.symbol());
            put(table, row, "Asset Type", "HOLDING");
            put(table, row, "Currency", position.currency());
            put(table, row, "Quantity", decimal(position.quantity()));
            put(table, row, "Avg Cost", decimal(position.averagePrice()));
            put(table, row, "Current Price", decimal(position.lastPrice()));
            put(table, row, "Market Value", decimal(position.marketValueAmount()));
            put(table, row, "Source", "TOSS_API");
            put(table, row, "Confidence", "HIGH");
            put(table, row, "Synced At", instant(observed));
            rows.add(row);
        }
        if (portfolio.buyingPower() != null) {
            portfolio.buyingPower().entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        var row = table.emptyRow();
                        put(table, row, "Account", ACCOUNT_1);
                        put(table, row, "Ticker", "CASH_" + entry.getKey());
                        put(table, row, "Asset Type", "CASH");
                        put(table, row, "Currency", entry.getKey());
                        put(table, row, "Cash", decimal(entry.getValue().cashBuyingPower()));
                        put(table, row, "Source", "TOSS_API");
                        put(table, row, "Confidence", "HIGH");
                        put(table, row, "Synced At", instant(observed));
                        rows.add(row);
                    });
        }
        return table.withRows(rows);
    }

    public static SheetTable orders(
            SheetTable current,
            List<ConnectorResponse.Order> open,
            List<ConnectorResponse.Order> closed,
            Instant syncedAt
    ) {
        var table = current.withHeaders(ORDER_HEADERS);
        if (open == null && closed == null) return table;
        var complete = open != null && closed != null;
        var rows = table.rows().stream()
                .filter(row -> !complete || !ACCOUNT_1.equalsIgnoreCase(value(table, row, "Account")))
                .collect(Collectors.toCollection(ArrayList::new));
        var byId = new LinkedHashMap<String, List<String>>();
        if (!complete) {
            rows.forEach(row -> {
                var id = value(table, row, "Order ID");
                if (!id.isBlank()) byId.put(id, row);
            });
        }
        var brokerOrders = new LinkedHashMap<String, ConnectorResponse.Order>();
        if (open != null) safe(open).forEach(order -> brokerOrders.put(order.brokerOrderId(), order));
        if (closed != null) safe(closed).forEach(order -> brokerOrders.put(order.brokerOrderId(), order));
        var observed = syncedAt;
        brokerOrders.values().stream()
                .sorted(Comparator.comparing(ConnectorResponse.Order::brokerOrderId))
                .forEach(order -> {
                    var row = table.emptyRow();
                    put(table, row, "Account", ACCOUNT_1);
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
                    put(table, row, "Synced At", instant(observed));
                    byId.put(order.brokerOrderId(), row);
                });
        if (!complete) {
            var result = new ArrayList<List<String>>();
            var emitted = new LinkedHashSet<String>();
            for (var row : rows) {
                var id = value(table, row, "Order ID");
                result.add(byId.getOrDefault(id, row));
                if (!id.isBlank()) emitted.add(id);
            }
            byId.values().stream()
                    .filter(row -> !emitted.contains(value(table, row, "Order ID")))
                    .forEach(result::add);
            return table.withRows(result);
        }
        return table.withRows(new ArrayList<>(byId.values()));
    }

    public static SheetTable aggregate(SheetTable accountState, Instant syncedAt) {
        return aggregate(new SheetTable(List.of(), List.of()), accountState, syncedAt);
    }

    public static SheetTable aggregate(
            SheetTable current,
            SheetTable accountState,
            Instant syncedAt
    ) {
        var table = current.withHeaders(AGGREGATE_HEADERS);
        var source = accountState.withHeaders(ACCOUNT_HEADERS);
        var holdings = new LinkedHashMap<String, Aggregate>();
        var cash = new LinkedHashMap<String, Aggregate>();
        for (var row : source.rows()) {
            var account = value(source, row, "Account");
            if (!ACCOUNT_1.equalsIgnoreCase(account) && !ACCOUNT_2.equalsIgnoreCase(account)) continue;
            var ticker = value(source, row, "Ticker");
            var currency = value(source, row, "Currency");
            if (ticker.isBlank() || currency.isBlank()) continue;
            var target = "CASH".equalsIgnoreCase(value(source, row, "Asset Type"))
                    || ticker.toUpperCase(Locale.ROOT).startsWith("CASH_") ? cash : holdings;
            var key = ticker + "|" + currency;
            target.computeIfAbsent(key, ignored -> new Aggregate(ticker, currency))
                    .add(account, value(source, row, "Source"), value(source, row, "Confidence"),
                            decimalValue(value(source, row, "Quantity")),
                            decimalValue(value(source, row, "Avg Cost")),
                            decimalValue(value(source, row, "Market Value")),
                            decimalValue(value(source, row, "Cash")));
        }
        var rows = new ArrayList<List<String>>();
        var observed = instant(syncedAt);
        holdings.values().stream().sorted(Aggregate.ORDER).forEach(item -> rows.add(item.row(table, observed, false)));
        cash.values().stream().sorted(Aggregate.ORDER).forEach(item -> rows.add(item.row(table, observed, true)));
        return table.withRows(rows);
    }

    public static SheetTable reconciliation(
            SheetTable current,
            String syncId,
            String account,
            String holdings,
            String cash,
            String orders,
            String fills,
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
        put(table, row, "Rows Changed", Integer.toString(rowsChanged));
        put(table, row, "Mismatch/Gap", mismatch);
        put(table, row, "Resolved", Boolean.toString(resolved));
        put(table, row, "Checked At", instant(checkedAt));
        put(table, row, "Error", error);
        var rows = new ArrayList<>(table.rows());
        rows.add(row);
        return table.withRows(rows);
    }

    public static List<String> accountHeaders() { return ACCOUNT_HEADERS; }
    public static List<String> orderHeaders() { return ORDER_HEADERS; }
    public static List<String> aggregateHeaders() { return AGGREGATE_HEADERS; }
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
        BigDecimal costBasis;
        boolean avgKnown = true;
        BigDecimal marketValue;
        BigDecimal cash;
        boolean marketKnown = true;
        boolean cashKnown = true;

        Aggregate(String ticker, String currency) {
            this.ticker = ticker;
            this.currency = currency;
        }

        void add(String account, String source, String confidence, BigDecimal qty, BigDecimal avg,
                 BigDecimal market, BigDecimal cashAmount) {
            accounts.add(account);
            if (!source.isBlank()) sources.add(source);
            allHigh &= "HIGH".equalsIgnoreCase(confidence);
            if (qty != null) {
                quantity = zero(quantity).add(qty);
                if (avg == null) avgKnown = false;
                else costBasis = zero(costBasis).add(qty.multiply(avg));
            } else avgKnown = false;
            if (market == null) marketKnown = false;
            else marketValue = zero(marketValue).add(market);
            if (cashAmount == null) cashKnown = false;
            else cash = zero(cash).add(cashAmount);
        }

        List<String> row(SheetTable table, String syncedAt, boolean isCash) {
            var row = table.emptyRow();
            put(table, row, "Ticker", ticker);
            put(table, row, "Currency", currency);
            if (!isCash) {
                put(table, row, "Quantity", decimal(quantity));
                put(table, row, "Combined Avg Cost", !avgKnown || quantity == null || quantity.signum() == 0
                        || costBasis == null ? null : decimal(costBasis.divide(quantity, 8, RoundingMode.HALF_UP)));
                put(table, row, "Market Value", marketKnown ? decimal(marketValue) : null);
            } else {
                put(table, row, "Cash", cashKnown ? decimal(cash) : null);
            }
            put(table, row, "Accounts Included", String.join(",", accounts));
            put(table, row, "Source Coverage", String.join("+", sources));
            put(table, row, "Confidence", allHigh ? "HIGH" : "MEDIUM");
            put(table, row, "Synced At", syncedAt);
            return row;
        }

        private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    }
}
