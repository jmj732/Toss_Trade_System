package com.jmj.trade.sheets;

import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.connector.ConnectorResponse;
import com.jmj.trade.connector.ConnectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final AccountSyncService accountSync;
    private final ConnectorService connector;
    private final GoogleSheetsClient sheets;
    private final Supplier<Instant> now;

    public InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            AccountSyncService accountSync,
            ConnectorService connector,
            GoogleSheetsClient sheets,
            Clock clock
    ) {
        this(properties, lease, accountSync, connector, sheets, Objects.requireNonNull(clock, "clock")::instant);
    }

    InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            AccountSyncService accountSync,
            ConnectorService connector,
            GoogleSheetsClient sheets,
            Supplier<Instant> now
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.accountSync = Objects.requireNonNull(accountSync, "accountSync");
        this.connector = Objects.requireNonNull(connector, "connector");
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
                .addKeyValue("account", InvestmentOsSheetModel.ACCOUNT_1)
                .addKeyValue("connection_id", connectionId).log("investment os sheet sync started");
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
        String failure = null;

        try {
            accountSync.sync(userId, connectionId);
            portfolio = connector.portfolio(userId, connectionId);
            LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("account", InvestmentOsSheetModel.ACCOUNT_1)
                    .addKeyValue("broker_fetch_result", portfolio == null ? "empty" : "success")
                    .log("Toss portfolio fetch completed");
        } catch (RuntimeException exception) {
            failure = safeError(exception);
            LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("account", InvestmentOsSheetModel.ACCOUNT_1)
                    .addKeyValue("broker_fetch_result", "failure").addKeyValue("failure_reason", failure)
                    .log("Toss portfolio fetch failed; existing sheet state preserved");
        }

        if (portfolio != null && authoritative(portfolio)) {
            try {
                open = connector.orders(userId, connectionId, "OPEN");
            } catch (RuntimeException exception) {
                failure = "OPEN_ORDERS_FETCH_FAILED";
                LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("section", "open_orders")
                        .addKeyValue("failure_reason", safeError(exception)).log("Toss open orders fetch failed");
            }
            try {
                closed = connector.orders(userId, connectionId, "CLOSED");
            } catch (RuntimeException exception) {
                failure = failure == null ? "CLOSED_ORDERS_FETCH_FAILED" : failure + "+CLOSED_ORDERS_FETCH_FAILED";
                LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("section", "closed_orders")
                        .addKeyValue("failure_reason", safeError(exception)).log("Toss closed orders fetch failed");
            }
        } else if (failure == null) {
            failure = portfolio == null ? "EMPTY_PORTFOLIO" : "NON_AUTHORITATIVE_PORTFOLIO";
        }

        try {
            var account = current.account();
            var orders = current.orders();
            var aggregate = current.aggregate();
            var authoritative = portfolio != null && authoritative(portfolio);
            var nextAccount = authoritative
                    ? InvestmentOsSheetModel.accountState(account, portfolio, syncedAt) : account;
            var nextOrders = authoritative
                    ? InvestmentOsSheetModel.orders(orders, open, closed, syncedAt) : orders;
            var nextAggregate = authoritative
                    ? InvestmentOsSheetModel.aggregate(aggregate, nextAccount, syncedAt) : aggregate;
            var allOrderReadsSucceeded = open != null && closed != null;
            var status = reconciliationStatus(authoritative, portfolio, open, closed);
            var nextRecon = InvestmentOsSheetModel.reconciliation(
                    current.reconciliation(), syncId.toString(), InvestmentOsSheetModel.ACCOUNT_1, "" + status.holdings,
                    status.cash, status.orders, status.fills,
                    rowDelta(account, nextAccount) + rowDelta(orders, nextOrders) + rowDelta(aggregate, nextAggregate),
                    failure == null ? "NONE" : failure, failure == null && authoritative && allOrderReadsSucceeded,
                    syncedAt, failure);
            var updates = new ArrayList<GoogleSheetsClient.SheetValueRange>();
            if (authoritative) {
                updates.add(toRange("Account State", account, nextAccount));
                updates.add(toRange("Orders", orders, nextOrders));
                updates.add(toRange("Portfolio Aggregate", aggregate, nextAggregate));
            }
            updates.add(toRange("Reconciliation Log", current.reconciliation(), nextRecon));
            sheets.batchUpdateValues(properties.spreadsheetId(), updates);
            var rowsChanged = rowDelta(account, nextAccount) + rowDelta(orders, nextOrders)
                    + rowDelta(aggregate, nextAggregate);
            var ordersChanged = open == null ? 0 : open.size();
            ordersChanged += closed == null ? 0 : closed.size();
            var result = new InvestmentOsSheetSyncResult(
                    failure == null && authoritative ? InvestmentOsSheetSyncResult.Outcome.SUCCEEDED
                            : InvestmentOsSheetSyncResult.Outcome.FAILED,
                    syncId, rowsChanged, ordersChanged, ordersChanged, failure);
            LOG.atInfo().addKeyValue("operation", OPERATION)
                    .addKeyValue("outcome", result.outcome().name().toLowerCase())
                    .addKeyValue("rows_changed", rowsChanged)
                    .addKeyValue("orders_changed", ordersChanged)
                    .addKeyValue("fills_changed", ordersChanged)
                    .addKeyValue("aggregate_recalculation", authoritative)
                    .addKeyValue("reconciliation_result", status.resolved)
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
                read("Portfolio Aggregate", InvestmentOsSheetModel.aggregateHeaders()),
                read("Reconciliation Log", InvestmentOsSheetModel.reconciliationHeaders()));
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

    private static boolean authoritative(ConnectorResponse.Portfolio portfolio) {
        if (portfolio.stale() || portfolio.partial() || portfolio.buyingPower() == null || portfolio.positions() == null) {
            return false;
        }
        if (!portfolio.buyingPower().keySet().containsAll(List.of("USD", "KRW"))
                || portfolio.buyingPower().values().stream().anyMatch(Objects::isNull)) {
            return false;
        }
        return portfolio.positions().stream().allMatch(position -> position != null
                && position.symbol() != null && !position.symbol().isBlank()
                && position.currency() != null && !position.currency().isBlank()
                && position.quantity() != null);
    }

    private static Status reconciliationStatus(boolean authoritative, ConnectorResponse.Portfolio portfolio,
                                               List<?> open, List<?> closed) {
        var holdings = authoritative ? "OK" : "FAILED";
        var cash = authoritative && portfolio.buyingPower().keySet().containsAll(List.of("USD", "KRW")) ? "OK" : "PARTIAL";
        var orders = open != null && closed != null ? "OK" : "PARTIAL";
        var fills = orders;
        return new Status(holdings, cash, orders, fills, authoritative && "OK".equals(cash)
                && "OK".equals(orders));
    }

    private static int rowDelta(InvestmentOsSheetModel.SheetTable before, InvestmentOsSheetModel.SheetTable after) {
        return before.rows().equals(after.rows()) ? 0 : Math.max(before.rows().size(), after.rows().size());
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

    private static String safeError(RuntimeException exception) { return exception.getClass().getSimpleName(); }

    private record Tables(InvestmentOsSheetModel.SheetTable account, InvestmentOsSheetModel.SheetTable orders,
                          InvestmentOsSheetModel.SheetTable aggregate, InvestmentOsSheetModel.SheetTable reconciliation) { }
    private record Status(String holdings, String cash, String orders, String fills, boolean resolved) { }
}
