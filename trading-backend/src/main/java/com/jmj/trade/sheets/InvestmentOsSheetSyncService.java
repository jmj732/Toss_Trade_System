package com.jmj.trade.sheets;

import com.jmj.trade.account.AccountSyncException;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.connector.ConnectorResponse;
import com.jmj.trade.connector.ConnectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Reads one confirmed Toss snapshot and publishes only broker-authoritative rows. */
public final class InvestmentOsSheetSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(InvestmentOsSheetSyncService.class);
    private static final String OPERATION = "investment_os_sheet_sync";

    private final InvestmentOsSheetProperties properties;
    private final InvestmentOsSheetLease lease;
    private final ConnectorService connector;
    private final BrokerSurfaceService brokerSurface;
    private final GoogleSheetsClient sheets;
    private final Supplier<Instant> now;

    public InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets,
            Clock clock
    ) {
        this(properties, lease, connector, brokerSurface, sheets,
                Objects.requireNonNull(clock, "clock")::instant);
    }

    InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets,
            Supplier<Instant> now
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.brokerSurface = brokerSurface;
        this.sheets = Objects.requireNonNull(sheets, "sheets");
        this.now = Objects.requireNonNull(now, "now");
    }

    public InvestmentOsSheetSyncResult sync() {
        return sync(properties.userId(), properties.connectionId());
    }

    public InvestmentOsSheetSyncResult sync(UUID userId, UUID connectionId) {
        if (connectionId == null) connectionId = properties.connectionId();
        requireConfiguredUser(userId, connectionId);
        var owner = UUID.randomUUID();
        if (!lease.acquire(owner)) {
            LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("outcome", "skipped")
                    .log("investment os sheet sync skipped; lease held elsewhere");
            return new InvestmentOsSheetSyncResult(InvestmentOsSheetSyncResult.Outcome.SKIPPED,
                    null, 0, 0, 0, null);
        }
        var started = System.nanoTime();
        var syncId = UUID.randomUUID();
        var syncedAt = now.get();
        LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("outcome", "started")
                .addKeyValue("account", properties.accountLabel())
                .addKeyValue("connection_id", connectionId)
                .addKeyValue("user_id", userId).log("investment os sheet sync started");
        try {
            return run(syncId, userId, connectionId, syncedAt, started);
        } finally {
            lease.release(owner);
        }
    }

    private InvestmentOsSheetSyncResult run(
            UUID syncId, UUID userId, UUID connectionId, Instant syncedAt, long started
    ) {
        final Tables current;
        try {
            current = readTables();
        } catch (RuntimeException exception) {
            var error = safeError(exception);
            LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("outcome", "failure")
                    .addKeyValue("failure_reason", error)
                    .log("Google Sheets read failed; no state writes attempted");
            return new InvestmentOsSheetSyncResult(InvestmentOsSheetSyncResult.Outcome.FAILED,
                    syncId, 0, 0, 0, error);
        }
        ConnectorResponse.Portfolio portfolio = null;
        List<ConnectorResponse.Order> open = null;
        List<ConnectorResponse.Order> closed = null;
        List<ConnectorResponse.Fill> fills = null;
        String failure = null;

        try {
            portfolio = connector.portfolio(userId, connectionId);
            LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                    .addKeyValue("broker_fetch_result", portfolio == null ? "empty" : "success")
                    .log("Toss portfolio fetch completed");
        } catch (RuntimeException exception) {
            failure = safeError(exception);
            LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                    .addKeyValue("broker_fetch_result", "failure").addKeyValue("failure_reason", failure)
                    .log("Toss portfolio fetch failed; existing sheet state preserved");
        }

        if (portfolio != null && authoritative(portfolio)) {
            BrokerAccountRef orderAccount = null;
            try {
                orderAccount = connector.brokerAccount(connectionId);
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, "BROKER_ACCOUNT_FETCH_FAILED_" + safeError(exception));
                LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("section", "broker_account")
                        .addKeyValue("failure_reason", safeError(exception)).log("Toss broker account fetch failed");
            }
            if (orderAccount != null) {
                try {
                    open = connector.orders(orderAccount, "OPEN");
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, "OPEN_ORDERS_FETCH_FAILED_" + safeError(exception));
                    LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("section", "open_orders")
                            .addKeyValue("failure_reason", safeError(exception)).log("Toss open orders fetch failed");
                }
                try {
                    closed = connector.orders(orderAccount, "CLOSED");
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, "CLOSED_ORDERS_FETCH_FAILED_" + safeError(exception));
                    LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("section", "closed_orders")
                            .addKeyValue("failure_reason", safeError(exception)).log("Toss closed orders fetch failed");
                }
            }
            if (open != null && closed != null) {
                fills = ConnectorService.fills(open, closed, syncedAt.minus(Duration.ofDays(1)));
                LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                        .addKeyValue("broker_fetch_result", "success").addKeyValue("fills", fills.size())
                        .log("Toss recent fills fetch completed");
            } else {
                failure = appendFailure(failure, "FILLS_NOT_DERIVED_ORDERS_UNAVAILABLE");
                LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("section", "fills")
                        .addKeyValue("failure_reason", "ORDERS_UNAVAILABLE")
                        .log("Toss recent fills unavailable; order history fetch failed");
            }
        } else if (failure == null) {
            failure = portfolio == null ? "EMPTY_PORTFOLIO" : "NON_AUTHORITATIVE_PORTFOLIO";
        }

        try {
            var account = current.account();
            var orders = current.orders();
            var orderHistory = current.orderHistory();
            var aggregate = current.aggregate();
            var metrics = current.metrics();
            var registry = current.registry();
            var authoritative = portfolio != null && authoritative(portfolio);
            var nextAccount = authoritative
                    ? InvestmentOsSheetModel.accountState(account, portfolio, syncedAt, properties.accountLabel()) : account;
            var registryReady = authoritative
                    && InvestmentOsSheetModel.hasAccountRegistryAccount(registry, properties.accountLabel());
            var nextRegistry = registryReady
                    ? InvestmentOsSheetModel.accountRegistry(registry, syncedAt) : registry;
            if (authoritative && !registryReady) {
                failure = appendFailure(failure, "ACCOUNT_REGISTRY_ROW_MISSING_OR_DUPLICATE");
                LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                        .addKeyValue("failure_reason", "ACCOUNT_REGISTRY_ROW_MISSING_OR_DUPLICATE")
                        .log("Account Registry Last Sync not updated; registry config preserved");
            }
            var expectedSymbols = authoritative ? InvestmentOsSheetModel.heldSymbols(nextAccount) : List.<String>of();
            var priceSnapshot = authoritative && brokerSurface != null
                    ? fetchPrices(userId, connectionId, nextAccount) : PriceSnapshot.notConfigured();
            if (authoritative && brokerSurface != null) {
                nextAccount = InvestmentOsSheetModel.refreshPrices(nextAccount,
                        new ArrayList<>(priceSnapshot.prices().values()), syncedAt);
            }
            var completePrices = authoritative && (expectedSymbols.isEmpty()
                    || brokerSurface != null && priceSnapshot.complete()
                    && InvestmentOsSheetModel.hasCompleteQuotes(nextAccount));
            var priceStatus = !authoritative ? "SKIPPED"
                    : expectedSymbols.isEmpty() ? "NOT_REQUIRED"
                    : brokerSurface == null ? "NOT_CONFIGURED" : completePrices ? "OK" : "PARTIAL";
            if (authoritative && !expectedSymbols.isEmpty() && brokerSurface == null) {
                failure = appendFailure(failure, "PRICE_SOURCE_NOT_CONFIGURED");
            }
            if (authoritative && brokerSurface != null && !completePrices) {
                failure = appendFailure(failure, "PRICE_FETCH_PARTIAL");
                if (!priceSnapshot.errors().isEmpty()) {
                    failure = appendFailure(failure, "PRICE_FETCH_ERRORS_" + String.join(",", priceSnapshot.errors()));
                }
                LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                        .addKeyValue("broker_fetch_result", "partial")
                        .addKeyValue("price_symbols_missing", priceSnapshot.missingSymbols().size())
                        .addKeyValue("price_fetch_errors", priceSnapshot.errors())
                        .log("Toss quote fetch incomplete; existing price and valuation data preserved");
            }
            var archiveReady = isCanonicalOrderTable(orders) || closed != null;
            var updateOrders = authoritative && open != null && archiveReady;
            var updateOrderHistory = authoritative && open != null && closed != null;
            var nextOrders = updateOrders
                    ? InvestmentOsSheetModel.openOrders(orders, open, syncedAt, properties.accountLabel()) : orders;
            var nextOrderHistory = updateOrderHistory
                    ? InvestmentOsSheetModel.orderHistory(
                            orderHistory, open, closed, orders, syncedAt, properties.accountLabel()) : orderHistory;
            var updateAggregate = authoritative && completePrices;
            var nextAggregate = updateAggregate
                    ? InvestmentOsSheetModel.aggregate(aggregate, nextAccount, syncedAt) : aggregate;
            var updateMetrics = authoritative && completePrices;
            var nextMetrics = updateMetrics
                    ? InvestmentOsSheetModel.portfolioMetrics(metrics, nextAccount, syncedAt) : metrics;
            var allOrderReadsSucceeded = open != null && closed != null;
            var status = reconciliationStatus(authoritative, portfolio, open, closed, fills, priceStatus);
            var nextRecon = InvestmentOsSheetModel.reconciliation(
                    current.reconciliation(), syncId.toString(), properties.accountLabel(), "" + status.holdings,
                    status.cash, status.orders, status.fills, status.prices,
                    rowDelta(account, nextAccount) + rowDelta(orders, nextOrders) + rowDelta(orderHistory, nextOrderHistory)
                            + rowDelta(aggregate, nextAggregate) + rowDelta(metrics, nextMetrics)
                            + rowDelta(registry, nextRegistry),
                    failure == null ? "NONE" : failure,
                    failure == null && authoritative && allOrderReadsSucceeded && fills != null && completePrices,
                    syncedAt, failure);
            var updates = new ArrayList<GoogleSheetsClient.SheetValueRange>();
            if (authoritative) {
                updates.add(toRange("Account State", account, nextAccount));
                if (updateOrders) updates.add(toOrderRange("Orders", orders, nextOrders));
                if (updateOrderHistory) updates.add(toOrderRange("Order History", orderHistory, nextOrderHistory));
                if (updateAggregate) updates.add(toRange("Portfolio Aggregate", aggregate, nextAggregate));
                if (updateMetrics) updates.add(toRange("Portfolio Metrics", metrics, nextMetrics));
                if (registryReady) updates.add(toRange("Account Registry", registry, nextRegistry));
            }
            updates.add(toRange("Reconciliation Log", current.reconciliation(), nextRecon));
            sheets.batchUpdateValues(properties.spreadsheetId(), updates);
            var rowsChanged = rowDelta(account, nextAccount) + rowDelta(orders, nextOrders)
                    + rowDelta(orderHistory, nextOrderHistory)
                    + rowDelta(aggregate, nextAggregate) + rowDelta(metrics, nextMetrics)
                    + rowDelta(registry, nextRegistry);
            var ordersChanged = open == null ? 0 : open.size();
            ordersChanged += closed == null ? 0 : closed.size();
            var result = new InvestmentOsSheetSyncResult(
                    failure == null && authoritative && allOrderReadsSucceeded && fills != null && completePrices
                            ? InvestmentOsSheetSyncResult.Outcome.SUCCEEDED
                            : InvestmentOsSheetSyncResult.Outcome.FAILED,
                    syncId, rowsChanged, ordersChanged, fills == null ? 0 : fills.size(), failure);
            LOG.atInfo().addKeyValue("operation", OPERATION)
                    .addKeyValue("outcome", result.outcome().name().toLowerCase())
                    .addKeyValue("rows_changed", rowsChanged)
                    .addKeyValue("orders_changed", ordersChanged)
                    .addKeyValue("fills_changed", fills == null ? 0 : fills.size())
                    .addKeyValue("quote_fetch_result", priceStatus.toLowerCase())
                    .addKeyValue("failure_reason", failure == null ? "NONE" : failure)
                    .addKeyValue("aggregate_recalculation", updateAggregate)
                    .addKeyValue("metrics_recalculation", updateMetrics)
                    .addKeyValue("reconciliation_result", failure == null && status.resolved)
                    .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("investment os sheet sync completed");
            return result;
        } catch (RuntimeException exception) {
            var error = safeError(exception);
            LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("outcome", "failure")
                    .addKeyValue("failure_reason", error)
                    .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("investment os sheet sync failed");
            return new InvestmentOsSheetSyncResult(InvestmentOsSheetSyncResult.Outcome.FAILED,
                    syncId, 0, 0, 0, error);
        }
    }

    private Tables readTables() {
        return new Tables(
                read("Account State", InvestmentOsSheetModel.accountHeaders()),
                read("Orders", InvestmentOsSheetModel.orderHeaders()),
                read("Order History", InvestmentOsSheetModel.orderHeaders()),
                read("Portfolio Aggregate", InvestmentOsSheetModel.aggregateHeaders()),
                read("Portfolio Metrics", InvestmentOsSheetModel.metricsHeaders()),
                read("Reconciliation Log", InvestmentOsSheetModel.reconciliationHeaders()),
                read("Account Registry", List.of("Account", "Label", "Sync Mode", "Source", "Default Confidence",
                        "Enabled", "Last Sync", "Notes")));
    }

    private InvestmentOsSheetModel.SheetTable read(String tab, List<String> defaults) {
        var values = sheets.readValues(properties.spreadsheetId(), quote(tab) + "!A:Z").values();
        if (values.isEmpty()) return new InvestmentOsSheetModel.SheetTable(defaults, List.of());
        var headers = values.getFirst().stream().map(value -> value == null ? "" : String.valueOf(value)).toList();
        var rows = values.stream().skip(1).map(row -> row.stream()
                .map(value -> value == null ? "" : String.valueOf(value)).toList()).toList();
        return new InvestmentOsSheetModel.SheetTable(headers, rows);
    }

    private static GoogleSheetsClient.SheetValueRange toRange(
            String tab, InvestmentOsSheetModel.SheetTable previous, InvestmentOsSheetModel.SheetTable next
    ) {
        var rows = new ArrayList<List<Object>>();
        rows.add(new ArrayList<>(next.headers()));
        rows.addAll(next.rows().stream().map(row -> new ArrayList<Object>(row)).toList());
        var required = Math.max(previous.rows().size(), next.rows().size()) + 1;
        while (rows.size() < required) rows.add(new ArrayList<>(java.util.Collections.nCopies(next.headers().size(), "")));
        return new GoogleSheetsClient.SheetValueRange(
                quote(tab) + "!A1:" + column(next.headers().size()) + rows.size(), rows);
    }

    private static GoogleSheetsClient.SheetValueRange toOrderRange(
            String tab, InvestmentOsSheetModel.SheetTable previous, InvestmentOsSheetModel.SheetTable next
    ) {
        var width = Math.max(previous.headers().size(), next.headers().size());
        var rows = new ArrayList<List<Object>>();
        var header = new ArrayList<Object>(next.headers());
        while (header.size() < width) header.add("");
        rows.add(header);
        next.rows().forEach(row -> {
            var cells = new ArrayList<Object>(row);
            while (cells.size() < width) cells.add("");
            rows.add(cells);
        });
        var required = Math.max(previous.rows().size(), next.rows().size()) + 1;
        while (rows.size() < required) rows.add(new ArrayList<>(java.util.Collections.nCopies(width, "")));
        return new GoogleSheetsClient.SheetValueRange(
                quote(tab) + "!A1:" + column(width) + rows.size(), rows);
    }

    private static boolean isCanonicalOrderTable(InvestmentOsSheetModel.SheetTable table) {
        var canonical = InvestmentOsSheetModel.orderHeaders();
        if (table.headers().size() < canonical.size()
                || !table.headers().subList(0, canonical.size()).equals(canonical)) return false;
        return table.headers().subList(canonical.size(), table.headers().size()).stream().allMatch(String::isBlank);
    }

    private static boolean authoritative(ConnectorResponse.Portfolio portfolio) {
        if (portfolio.stale() || portfolio.partial() || portfolio.buyingPower() == null || portfolio.positions() == null) {
            return false;
        }
        if (!portfolio.buyingPower().keySet().containsAll(List.of("USD", "KRW"))
                || portfolio.buyingPower().values().stream().anyMatch(value -> value == null
                || value.cashBuyingPower() == null)) {
            return false;
        }
        return portfolio.positions().stream().allMatch(position -> position != null
                && position.symbol() != null && !position.symbol().isBlank()
                && position.currency() != null && !position.currency().isBlank()
                && position.quantity() != null && position.averagePrice() != null);
    }

    private static Status reconciliationStatus(boolean authoritative, ConnectorResponse.Portfolio portfolio,
                                               List<?> open, List<?> closed, List<?> fills, String prices) {
        var holdings = authoritative ? "OK" : "FAILED";
        var cash = authoritative && portfolio.buyingPower().keySet().containsAll(List.of("USD", "KRW")) ? "OK" : "PARTIAL";
        var orders = open != null && closed != null ? "OK" : "PARTIAL";
        var fillStatus = fills != null ? "OK" : "PARTIAL";
        return new Status(holdings, cash, orders, fillStatus, authoritative && "OK".equals(cash)
                && "OK".equals(orders) && "OK".equals(fillStatus)
                && ("OK".equals(prices) || "NOT_REQUIRED".equals(prices)), prices);
    }

    private PriceSnapshot fetchPrices(UUID userId, UUID connectionId, InvestmentOsSheetModel.SheetTable account) {
        var expected = InvestmentOsSheetModel.heldSymbols(account);
        var prices = new LinkedHashMap<String, BrokerSurfaceResponse.PriceView>();
        var errors = new LinkedHashMap<String, String>();
        for (var symbol : expected) {
            try {
                var response = brokerSurface.prices(userId, connectionId, symbol);
                if (response == null || response.data() == null || response.stale()) {
                    errors.put(symbol, response == null ? "EMPTY_RESPONSE" : response.stale()
                            ? "STALE_PRICE_RESPONSE"
                            : response.unavailableReason() == null ? "PRICE_UNAVAILABLE" : response.unavailableReason());
                    continue;
                }
                for (var price : response.data()) {
                    if (price != null && symbol.equalsIgnoreCase(price.symbol()) && price.lastPrice() != null
                            && price.lastPrice().signum() > 0) {
                        prices.putIfAbsent(price.symbol().toUpperCase(java.util.Locale.ROOT), price);
                    }
                }
                if (!prices.containsKey(symbol)) {
                    var missingPrice = response.unknownFields().stream()
                            .filter(field -> field.equalsIgnoreCase(symbol + ".lastPrice"))
                            .findFirst().orElse("LAST_PRICE_MISSING");
                    errors.put(symbol, missingPrice);
                }
            } catch (RuntimeException exception) {
                errors.put(symbol, safeError(exception));
            }
        }
        var missing = expected.stream().filter(symbol -> !prices.containsKey(symbol)).toList();
        var failures = errors.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
        return new PriceSnapshot(Map.copyOf(prices), missing, failures);
    }

    private static String appendFailure(String failure, String next) {
        return failure == null ? next : failure + "+" + next;
    }

    private static int rowDelta(InvestmentOsSheetModel.SheetTable before, InvestmentOsSheetModel.SheetTable after) {
        var identity = identityColumns(before, after);
        var previous = keyedRows(before, identity);
        var next = keyedRows(after, identity);
        var keys = new java.util.LinkedHashSet<>(previous.keySet());
        keys.addAll(next.keySet());
        return (int) keys.stream().filter(key -> !Objects.equals(previous.get(key), next.get(key))).count();
    }

    private static List<String> identityColumns(
            InvestmentOsSheetModel.SheetTable before, InvestmentOsSheetModel.SheetTable after
    ) {
        for (var candidate : List.of(List.of("Account", "Order ID"), List.of("Scope"),
                List.of("Account", "Ticker", "Currency"), List.of("Account", "Asset", "Currency"),
                List.of("Ticker", "Currency"), List.of("Asset", "Currency"), List.of("Account"))) {
            if (candidate.stream().allMatch(name -> before.hasColumn(name) && after.hasColumn(name))) return candidate;
        }
        return List.of();
    }

    private static Map<String, List<String>> keyedRows(
            InvestmentOsSheetModel.SheetTable table, List<String> identity
    ) {
        var rows = new LinkedHashMap<String, List<String>>();
        for (var index = 0; index < table.rows().size(); index++) {
            var row = table.rows().get(index);
            var key = identity.isEmpty() ? "row:" + index
                    : identity.stream().map(name -> cell(table, row, name))
                    .collect(java.util.stream.Collectors.joining("|"));
            rows.put(key, row);
        }
        return rows;
    }

    private static String cell(InvestmentOsSheetModel.SheetTable table, List<String> row, String column) {
        var index = table.column(column);
        return index >= row.size() || row.get(index) == null ? "" : row.get(index);
    }

    private void requireConfiguredUser(UUID userId, UUID connectionId) {
        if (!Objects.equals(properties.userId(), userId) || !Objects.equals(properties.connectionId(), connectionId)) {
            throw new IllegalArgumentException("configured Sheet sync account mismatch");
        }
    }

    private static String quote(String tab) { return "'" + tab.replace("'", "''") + "'"; }

    private static String column(int count) {
        var result = new StringBuilder();
        for (var n = count; n > 0; n = (n - 1) / 26) result.append((char) ('A' + (n - 1) % 26));
        return result.reverse().toString();
    }

    private static String safeError(RuntimeException exception) {
        if (exception instanceof AccountSyncException sync) return sync.code().name();
        if (exception instanceof BrokerConnectionException connection) return connection.code().publicCode();
        if (exception instanceof BrokerException broker) {
            return "BROKER_" + broker.category().name()
                    + broker.httpStatus().map(status -> "_HTTP_" + status).orElse("");
        }
        return exception.getClass().getSimpleName();
    }

    private record Tables(InvestmentOsSheetModel.SheetTable account, InvestmentOsSheetModel.SheetTable orders,
                          InvestmentOsSheetModel.SheetTable orderHistory,
                          InvestmentOsSheetModel.SheetTable aggregate, InvestmentOsSheetModel.SheetTable metrics,
                          InvestmentOsSheetModel.SheetTable reconciliation,
                          InvestmentOsSheetModel.SheetTable registry) { }
    private record Status(String holdings, String cash, String orders, String fills, boolean resolved, String prices) { }
    private record PriceSnapshot(Map<String, BrokerSurfaceResponse.PriceView> prices, List<String> missingSymbols,
                                 List<String> errors) {
        static PriceSnapshot notConfigured() { return new PriceSnapshot(Map.of(), List.of(), List.of()); }
        boolean complete() { return missingSymbols.isEmpty(); }
    }
}
