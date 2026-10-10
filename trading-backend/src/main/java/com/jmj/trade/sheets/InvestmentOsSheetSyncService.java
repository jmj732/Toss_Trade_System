package com.jmj.trade.sheets;

import com.jmj.trade.account.AccountSyncException;
import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.connector.ConnectorResponse;
import com.jmj.trade.connector.ConnectorService;
import com.jmj.trade.investment.DeclaredMarketGap;
import com.jmj.trade.investment.InvestmentContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** Reads one confirmed Toss snapshot and mirrors the broker plus configured manual account. */
public final class InvestmentOsSheetSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(InvestmentOsSheetSyncService.class);
    private static final String OPERATION = "investment_os_sheet_sync";
    // ponytail: refresh the unbounded CLOSED order history less often; OPEN orders and holdings stay on the 5-minute loop.
    private static final Duration CLOSED_ORDER_REFRESH_INTERVAL = Duration.ofMinutes(30);
    private static final Duration CLOSED_ORDER_RATE_LIMIT_BACKOFF = Duration.ofMinutes(30);
    // The official calendar for a New York date is reused briefly so the 5-minute loop does not re-request it.
    private static final Duration MARKET_CALENDAR_REUSE = Duration.ofHours(1);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    // Initial attempt plus two retries per declared closed-market gap, as in the other bounded refresh paths.
    private static final int POST_CLOSE_CAPTURE_ATTEMPT_LIMIT = 3;

    private final InvestmentOsSheetProperties properties;
    private final InvestmentOsSheetLease lease;
    private final ConnectorService connector;
    private final BrokerSurfaceService brokerSurface;
    private final GoogleSheetsClient sheets;
    private final InvestmentOsResearchSheetSync researchSheets;
    private final JdbcTemplate portfolioSnapshotJdbc;
    private final ObjectMapper objectMapper;
    private final Supplier<Instant> now;
    private Instant closedOrdersFetchedAt;
    private Instant closedOrdersRetryNotBefore;
    private List<ConnectorResponse.Order> cachedClosedOrders = List.of();
    private AccountSyncService accountSync;
    private final Map<LocalDate, CachedCalendar> marketCalendars = new LinkedHashMap<>();
    private Instant postCloseCaptureGapEnd;
    private int postCloseCaptureAttempts;

    public InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets,
            Clock clock
    ) {
        this(properties, lease, connector, brokerSurface, sheets,
                Objects.requireNonNull(clock, "clock")::instant, null);
    }

    InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets,
            Clock clock,
            InvestmentOsResearchSheetSync researchSheets
    ) {
        this(properties, lease, connector, brokerSurface, sheets,
                Objects.requireNonNull(clock, "clock")::instant, researchSheets);
    }

    InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets,
            Supplier<Instant> now
    ) {
        this(properties, lease, connector, brokerSurface, sheets, now, null);
    }

    InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets,
            Supplier<Instant> now,
            InvestmentOsResearchSheetSync researchSheets
    ) {
        this(properties, lease, connector, brokerSurface, sheets, now, researchSheets, null, null);
    }

    InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets,
            Clock clock,
            InvestmentOsResearchSheetSync researchSheets,
            JdbcTemplate portfolioSnapshotJdbc,
            ObjectMapper objectMapper
    ) {
        this(properties, lease, connector, brokerSurface, sheets, Objects.requireNonNull(clock, "clock")::instant,
                researchSheets, portfolioSnapshotJdbc, objectMapper);
    }

    InvestmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets,
            Supplier<Instant> now,
            InvestmentOsResearchSheetSync researchSheets,
            JdbcTemplate portfolioSnapshotJdbc,
            ObjectMapper objectMapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.brokerSurface = brokerSurface;
        this.sheets = Objects.requireNonNull(sheets, "sheets");
        this.researchSheets = researchSheets;
        this.portfolioSnapshotJdbc = portfolioSnapshotJdbc;
        this.objectMapper = objectMapper;
        this.now = Objects.requireNonNull(now, "now");
    }

    /** Optional read-only account sync used once per declared closed-market gap (post-close capture). */
    void setAccountSync(AccountSyncService accountSync) {
        this.accountSync = accountSync;
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
            persistFailedSnapshot(userId, syncedAt, "SHEET_READ_FAILED");
            LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("outcome", "failure")
                    .addKeyValue("failure_reason", error)
                    .log("Google Sheets read failed; no state writes attempted");
            return new InvestmentOsSheetSyncResult(InvestmentOsSheetSyncResult.Outcome.FAILED,
                    syncId, 0, 0, 0, error);
        }
        var manualReadAt = now.get();
        ConnectorResponse.Portfolio portfolio = null;
        List<ConnectorResponse.Order> open = null;
        List<ConnectorResponse.Order> closed = null;
        List<ConnectorResponse.Fill> fills = null;
        String failure = null;
        boolean closedFromCache = false;

        Function<LocalDate, JsonNode> calendars = date -> marketCalendar(userId, connectionId, date, syncedAt);
        try {
            portfolio = readPortfolio(userId, connectionId, syncedAt, calendars);
            LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                    .addKeyValue("broker_fetch_result", portfolio == null ? "empty" : "success")
                    .log("Toss portfolio fetch completed");
        } catch (RuntimeException exception) {
            failure = safeError(exception);
            LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                    .addKeyValue("broker_fetch_result", "failure").addKeyValue("failure_reason", failure)
                    .log("Toss portfolio fetch failed; existing sheet state preserved");
        }
        var portfolioAccepted = portfolio != null && (authoritative(portfolio)
                || closedMarketCurrent(portfolio, syncedAt, calendars));

        if (portfolioAccepted) {
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
                if (shouldRefreshClosedOrders(syncedAt)) {
                    try {
                        closed = connector.orders(orderAccount, "CLOSED");
                        cachedClosedOrders = List.copyOf(closed == null ? List.of() : closed);
                        closedOrdersFetchedAt = syncedAt;
                        closedOrdersRetryNotBefore = null;
                        LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("section", "closed_orders")
                                .addKeyValue("fetch_mode", "fresh").addKeyValue("orders", cachedClosedOrders.size())
                                .log("Toss closed orders fetch completed");
                    } catch (RuntimeException exception) {
                        closedOrdersRetryNotBefore = syncedAt.plus(CLOSED_ORDER_RATE_LIMIT_BACKOFF);
                        if (closedOrdersFetchedAt != null) {
                            closed = cachedClosedOrders;
                            closedFromCache = true;
                            failure = appendFailure(failure, "CLOSED_ORDERS_FETCH_FAILED_" + safeError(exception)
                                    + "+CLOSED_ORDERS_USING_CACHE");
                        } else {
                            failure = appendFailure(failure, "CLOSED_ORDERS_FETCH_FAILED_" + safeError(exception));
                        }
                        LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("section", "closed_orders")
                                .addKeyValue("failure_reason", safeError(exception))
                                .addKeyValue("fetch_mode", closedFromCache ? "cache" : "failed")
                                .log("Toss closed orders fetch failed; cached history retained when available");
                    }
                } else if (closedOrdersFetchedAt != null) {
                    closed = cachedClosedOrders;
                    closedFromCache = true;
                    LOG.atDebug().addKeyValue("operation", OPERATION).addKeyValue("section", "closed_orders")
                            .addKeyValue("fetch_mode", "cache").addKeyValue("fetched_at", closedOrdersFetchedAt)
                            .log("Toss closed orders fetch skipped; refresh interval has not elapsed");
                } else {
                    failure = appendFailure(failure, "CLOSED_ORDERS_RATE_LIMIT_BACKOFF");
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

        boolean acceptedSnapshotPersisted = false;
        try {
            var account = current.account();
            var orders = current.orders();
            var orderHistory = current.orderHistory();
            var aggregate = current.aggregate();
            var metrics = current.metrics();
            var registry = current.registry();
            var authoritative = portfolioAccepted;
            var nextAccount = authoritative
                    ? InvestmentOsSheetModel.accountState(account, portfolio, portfolio.completedAt(), properties.accountLabel()) : account;
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
            var manualConfigured = InvestmentOsSheetModel.manualAccountEnabled(registry);
            var manualRowsValid = manualConfigured
                    && InvestmentOsSheetModel.manualRowsStructurallyValid(nextAccount);
            if (!manualConfigured) failure = appendFailure(failure, "MANUAL_REGISTRY_UNCONFIRMED");
            else if (!manualRowsValid) failure = appendFailure(failure, "MANUAL_ACCOUNT_INVALID");
            var portfolioAccountState = manualRowsValid ? nextAccount
                    : InvestmentOsSheetModel.withoutManualAccount(nextAccount);
            var expectedSymbols = authoritative ? InvestmentOsSheetModel.heldSymbols(portfolioAccountState) : List.<String>of();
            var priceSnapshot = authoritative && brokerSurface != null
                    ? fetchPrices(userId, connectionId, portfolioAccountState) : PriceSnapshot.notConfigured();
            if (authoritative && brokerSurface != null) {
                portfolioAccountState = InvestmentOsSheetModel.refreshPrices(portfolioAccountState,
                        new ArrayList<>(priceSnapshot.prices().values()), syncedAt);
                nextAccount = manualRowsValid ? portfolioAccountState
                        : mergeManualRows(portfolioAccountState, nextAccount);
            }
            var completePrices = authoritative && (expectedSymbols.isEmpty()
                    || brokerSurface != null && priceSnapshot.complete()
                    && InvestmentOsSheetModel.hasCompleteQuotes(portfolioAccountState));
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
            var updateOrderHistory = authoritative && open != null && closed != null && !closedFromCache;
            var nextOrders = updateOrders
                    ? InvestmentOsSheetModel.openOrders(orders, open, syncedAt, properties.accountLabel()) : orders;
            var nextOrderHistory = updateOrderHistory
                    ? InvestmentOsSheetModel.orderHistory(
                            orderHistory, open, closed, orders, syncedAt, properties.accountLabel()) : orderHistory;
            var updateAggregate = authoritative && manualRowsValid && completePrices;
            var nextAggregate = updateAggregate
                    ? InvestmentOsSheetModel.aggregate(aggregate, portfolioAccountState, syncedAt) : aggregate;
            var updateMetrics = authoritative && manualRowsValid && completePrices;
            var nextMetrics = updateMetrics
                    ? portfolioMetricsKeepingVerifiedScopes(metrics, portfolioAccountState, syncedAt) : metrics;
            var allOrderReadsSucceeded = open != null && closed != null;
            var status = reconciliationStatus(authoritative, portfolio, open, closed, fills, priceStatus);
            if (authoritative && manualRowsValid) {
                var manualMetadataVerified = InvestmentOsSheetModel.manualRowsHaveVerifiedMetadata(
                        portfolioAccountState, syncedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
                var snapshotAggregate = InvestmentOsSheetModel.aggregate(
                        new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.aggregateHeaders(), List.of()),
                        portfolioAccountState, syncedAt);
                var snapshotMetrics = portfolioMetricsKeepingVerifiedScopes(metrics, portfolioAccountState, syncedAt);
                var snapshotStatus = completePrices && hasCombinedTotal(snapshotMetrics) && manualMetadataVerified
                        ? "SUCCEEDED" : "PARTIAL";
                persistAcceptedSnapshot(userId, syncedAt, manualReadAt, portfolio.completedAt(), snapshotStatus,
                        portfolioAccountState, snapshotAggregate, snapshotMetrics, failure, calendars);
                acceptedSnapshotPersisted = portfolioSnapshotJdbc != null && objectMapper != null;
            } else {
                persistFailedSnapshot(userId, syncedAt, !authoritative ? "PORTFOLIO_NOT_AUTHORITATIVE"
                        : !manualConfigured ? "MANUAL_REGISTRY_UNCONFIRMED" : "MANUAL_ACCOUNT_INVALID");
            }
            var primarySyncSucceeded = failure == null && authoritative && allOrderReadsSucceeded
                    && fills != null && completePrices;
            var skippedResearchTabs = List.<String>of();
            var researchMirrorFailed = false;
            try {
                if (researchSheets != null) {
                    var skipped = researchSheets.sync(userId);
                    if (skipped != null) skippedResearchTabs = skipped;
                }
            } catch (RuntimeException exception) {
                researchMirrorFailed = true;
                failure = appendFailure(failure, "RESEARCH_MIRROR_FAILED_" + safeError(exception));
                LOG.atWarn().addKeyValue("operation", OPERATION)
                        .addKeyValue("failure_reason", safeError(exception))
                        .log("Research sheet mirror failed");
            }
            var conflictingResearchTabs = List.of("Thesis State", "Decision Ledger").stream()
                    .filter(skippedResearchTabs::contains).toList();
            if (!conflictingResearchTabs.isEmpty()) {
                failure = appendFailure(failure,
                        "RESEARCH_MIRROR_SCHEMA_CONFLICT_" + String.join(";", conflictingResearchTabs));
            }
            var rowsChanged = rowDelta(account, nextAccount) + rowDelta(orders, nextOrders)
                    + rowDelta(orderHistory, nextOrderHistory)
                    + rowDelta(aggregate, nextAggregate) + rowDelta(metrics, nextMetrics)
                    + rowDelta(registry, nextRegistry);
            var nextRecon = InvestmentOsSheetModel.reconciliation(
                    current.reconciliation(), syncId.toString(), properties.accountLabel(), "" + status.holdings,
                    status.cash, status.orders, status.fills, status.prices, rowsChanged,
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
            try {
                sheets.batchUpdateValues(properties.spreadsheetId(), updates);
            } catch (RuntimeException exception) {
                if (acceptedSnapshotPersisted) persistFailedSnapshot(userId, syncedAt, "SHEET_MIRROR_FAILED");
                throw exception;
            }
            var ordersChanged = open == null ? 0 : open.size();
            ordersChanged += closed == null || closedFromCache ? 0 : closed.size();
            var result = new InvestmentOsSheetSyncResult(
                    primarySyncSucceeded && !researchMirrorFailed
                            ? conflictingResearchTabs.isEmpty() ? InvestmentOsSheetSyncResult.Outcome.SUCCEEDED
                                    : InvestmentOsSheetSyncResult.Outcome.PARTIAL
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
        return !portfolio.stale() && completeAccountSnapshot(portfolio);
    }

    /**
     * While the market is closed, a persisted account snapshot that is stale only by age stays authoritative when
     * the official Toss calendar shows it was captured outside every declared interval and no declared interval
     * has started since: holdings and cash cannot have traded in between. Any other stale reason, a missing
     * calendar, or an open interval keeps the snapshot non-authoritative.
     */
    private static boolean closedMarketCurrent(
            ConnectorResponse.Portfolio portfolio, Instant now, Function<LocalDate, JsonNode> calendars
    ) {
        if (!portfolio.stale() || !"SNAPSHOT_TOO_OLD".equals(portfolio.staleReason())
                || portfolio.completedAt() == null || portfolio.completedAt().isAfter(now)
                || !completeAccountSnapshot(portfolio)) {
            return false;
        }
        var nextInterval = DeclaredMarketGap.nextIntervalStartIfOutside(portfolio.completedAt(), calendars);
        return nextInterval != null && now.isBefore(nextInterval);
    }

    /**
     * Reads the account for this attempt. Outside a verified closed-market gap (or when the calendar cannot verify
     * one) this is the existing read-through, which synchronizes from the broker. Inside a gap the persisted
     * snapshot is read without any broker call when it was captured in that same gap, because holdings and cash
     * cannot trade until the next declared interval; otherwise (including a later failed account sync) the bounded
     * post-close capture runs.
     */
    private ConnectorResponse.Portfolio readPortfolio(
            UUID userId, UUID connectionId, Instant syncedAt, Function<LocalDate, JsonNode> calendars
    ) {
        var gapEnd = DeclaredMarketGap.nextIntervalStartIfOutside(syncedAt, calendars);
        if (gapEnd == null || !syncedAt.isBefore(gapEnd)) return connector.portfolio(userId, connectionId);
        ConnectorResponse.Portfolio persisted;
        try {
            persisted = connector.persistedPortfolio(userId, connectionId);
        } catch (RuntimeException exception) {
            // No readable persisted snapshot (e.g. no successful sync yet): keep the existing read-through.
            return connector.portfolio(userId, connectionId);
        }
        if (persisted == null) return null;
        // A later failed account sync (dashboard read-through, scheduled refresh) leaves LATEST_SYNC_FAILED, which is
        // never accepted; the bounded capture replaces the read-through retry that used to clear it.
        if (capturedInGap(persisted, gapEnd, calendars) && !"LATEST_SYNC_FAILED".equals(persisted.staleReason())) {
            return persisted;
        }
        return capturedAfterClose(userId, connectionId, persisted, gapEnd);
    }

    private boolean capturedInGap(
            ConnectorResponse.Portfolio portfolio, Instant gapEnd, Function<LocalDate, JsonNode> calendars
    ) {
        var completedAt = portfolio.completedAt();
        return completedAt != null && !completedAt.isAfter(now.get())
                && gapEnd.equals(DeclaredMarketGap.nextIntervalStartIfOutside(completedAt, calendars));
    }

    /**
     * Post-close capture: the persisted account snapshot predates the current closed-market gap, so run the existing
     * read-only account sync (no orders) and re-read the persisted snapshot. Bounded per gap; any failure keeps the
     * previous snapshot and the existing freshness checks, and never falls back to the read-through.
     */
    private ConnectorResponse.Portfolio capturedAfterClose(
            UUID userId, UUID connectionId, ConnectorResponse.Portfolio portfolio, Instant gapEnd
    ) {
        if (accountSync == null) return portfolio;
        if (!gapEnd.equals(postCloseCaptureGapEnd)) {
            postCloseCaptureGapEnd = gapEnd;
            postCloseCaptureAttempts = 0;
        }
        if (postCloseCaptureAttempts >= POST_CLOSE_CAPTURE_ATTEMPT_LIMIT) return portfolio;
        postCloseCaptureAttempts++;
        try {
            accountSync.syncForMonitoring(userId, connectionId);
            var captured = connector.persistedPortfolio(userId, connectionId);
            LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                    .addKeyValue("post_close_capture", "success")
                    .log("Toss account captured after the last declared interval ended");
            return captured == null ? portfolio : captured;
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", OPERATION).addKeyValue("account", properties.accountLabel())
                    .addKeyValue("post_close_capture", "failure")
                    .addKeyValue("failure_reason", safeError(exception))
                    .log("post-close account capture failed; previous snapshot remains subject to freshness checks");
            return portfolio;
        }
    }

    private JsonNode marketCalendar(UUID userId, UUID connectionId, LocalDate date, Instant now) {
        if (brokerSurface == null || date == null) return null;
        var cached = marketCalendars.get(date);
        if (cached != null && !cached.fetchedAt().isAfter(now)
                && now.isBefore(cached.fetchedAt().plus(MARKET_CALENDAR_REUSE))) {
            return cached.payload();
        }
        JsonNode payload;
        String unavailable = null;
        try {
            var response = brokerSurface.marketCalendar(userId, connectionId, "US", date);
            payload = response == null || response.unavailable() || response.data() == null
                    || !"US".equals(response.data().market()) ? null : response.data().payload();
            if (payload == null) {
                unavailable = response == null ? "EMPTY_RESPONSE" : response.unavailableReason() == null
                        ? "CALENDAR_UNAVAILABLE" : response.unavailableReason();
            }
        } catch (RuntimeException exception) {
            payload = null;
            unavailable = safeError(exception);
        }
        if (payload == null) {
            LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("market_calendar_date", date)
                    .addKeyValue("failure_reason", unavailable)
                    .log("official market calendar unavailable; closed-market state stays unverified");
            return null;
        }
        marketCalendars.put(date, new CachedCalendar(now, payload));
        marketCalendars.keySet().removeIf(key -> key.isBefore(now.atZone(NEW_YORK).toLocalDate().minusDays(7)));
        return payload;
    }

    private record CachedCalendar(Instant fetchedAt, JsonNode payload) {
    }

    private static boolean completeAccountSnapshot(ConnectorResponse.Portfolio portfolio) {
        if (portfolio.partial() || portfolio.buyingPower() == null || portfolio.positions() == null) {
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

    private void persistAcceptedSnapshot(
            UUID userId,
            Instant attemptedAt,
            Instant manualReadAt,
            Instant account1AsOf,
            String status,
            InvestmentOsSheetModel.SheetTable portfolioAccountState,
            InvestmentOsSheetModel.SheetTable aggregate,
            InvestmentOsSheetModel.SheetTable metrics,
            String failure,
            Function<LocalDate, JsonNode> calendars
    ) {
        if (portfolioSnapshotJdbc == null || objectMapper == null) return;
        var payload = objectMapper.createObjectNode();
        payload.set("accountState", tableJson(portfolioAccountState));
        payload.set("aggregate", tableJson(aggregate));
        payload.set("metrics", tableJson(metrics));
        var manualRows = portfolioAccountState.rows().stream()
                .filter(row -> InvestmentOsSheetModel.ACCOUNT_2.equalsIgnoreCase(
                        cell(portfolioAccountState, row, "Account"))).toList();
        var manualStatus = manualRows.isEmpty() ? "EMPTY_CONFIRMED"
                : InvestmentOsSheetModel.manualRowsHaveVerifiedMetadata(portfolioAccountState,
                attemptedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()) ? "OK" : "UNVERIFIED";
        payload.put("manualStatus", manualStatus);
        var manualAsOf = InvestmentOsSheetModel.manualAsOf(portfolioAccountState);
        if (manualAsOf == null) payload.putNull("manualAsOf");
        else payload.put("manualAsOf", manualAsOf.toString());
        if (manualReadAt == null) payload.putNull("manualReadAt");
        else payload.put("manualReadAt", manualReadAt.toString());
        if (account1AsOf == null) payload.putNull("account1AsOf");
        else payload.put("account1AsOf", account1AsOf.toString());
        // The account and quotes are read after the attempt starts (the read-through sync completes seconds later),
        // so the facts are bounded by the time the payload is recorded, not by attemptedAt.
        putClosedMarketFacts(payload, now.get(), account1AsOf, portfolioAccountState, calendars);
        payload.put("source", "TOSS_API+MANUAL_SHEET");
        payload.put("attemptStatus", status);
        payload.put("failurePresent", failure != null);
        insertPortfolioSnapshot(userId, attemptedAt, status,
                "PARTIAL".equals(status) ? "OPTIONAL_SOURCE_FAILURE" : null, payload);
    }

    /**
     * Records calendar facts only when the earliest snapshot timestamp (ACCOUNT_1 as-of and every row's Price
     * Synced At) lies outside every declared Toss interval and the next declared interval has not started when
     * the payload is recorded ({@code recordedAt}). The read path then keeps these timestamps current until that
     * interval starts. A timestamp later than {@code recordedAt} is never used as the reference.
     */
    private static void putClosedMarketFacts(
            ObjectNode payload, Instant recordedAt, Instant account1AsOf,
            InvestmentOsSheetModel.SheetTable accountState, Function<LocalDate, JsonNode> calendars
    ) {
        if (account1AsOf == null) return;
        var reference = account1AsOf;
        if (accountState.hasColumn("Price Synced At")) {
            for (var row : accountState.rows()) {
                var synced = parseInstant(cell(accountState, row, "Price Synced At"));
                if (synced != null && synced.isBefore(reference)) reference = synced;
            }
        }
        String skipped = null;
        Instant nextInterval = null;
        if (recordedAt == null || reference.isAfter(recordedAt)) {
            skipped = "REFERENCE_AFTER_RECORDED_AT";
        } else {
            nextInterval = DeclaredMarketGap.nextIntervalStartIfOutside(reference, calendars);
            if (nextInterval == null) skipped = "INSIDE_DECLARED_INTERVAL_OR_CALENDAR_UNVERIFIED";
            else if (!recordedAt.isBefore(nextInterval)) skipped = "NEXT_DECLARED_INTERVAL_STARTED";
        }
        if (skipped != null) {
            LOG.atInfo().addKeyValue("operation", OPERATION).addKeyValue("closed_market_facts", "not_recorded")
                    .addKeyValue("reason", skipped).addKeyValue("reference_at", reference)
                    .log("closed-market facts not recorded; the 15-minute freshness rule applies");
            return;
        }
        payload.put("sessionReason", InvestmentContextService.PORTFOLIO_CAPTURED_OUTSIDE_DECLARED_INTERVALS);
        payload.put("sessionReferenceAt", reference.toString());
        payload.put("nextDeclaredIntervalStartsAt", nextInterval.toString());
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value.trim()).toInstant();
            } catch (RuntimeException ignoredOffset) {
                return null;
            }
        }
    }

    private void persistFailedSnapshot(UUID userId, Instant attemptedAt, String errorCode) {
        if (portfolioSnapshotJdbc == null) return;
        insertPortfolioSnapshot(userId, attemptedAt, "FAILED", errorCode, null);
    }

    private void insertPortfolioSnapshot(
            UUID userId, Instant attemptedAt, String status, String errorCode, ObjectNode payload
    ) {
        try {
            var payloadText = payload == null ? null : objectMapper.writeValueAsString(payload);
            portfolioSnapshotJdbc.update("""
                    INSERT INTO investment_os_portfolio_snapshots (
                        id, user_id, attempt_status, attempted_at, error_code, payload, created_at
                    ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """, UUID.randomUUID(), userId, status, databaseTimestamp(attemptedAt), errorCode,
                    payloadText, databaseTimestamp(now.get()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("portfolio snapshot serialization failed");
        }
    }

    private ObjectNode tableJson(InvestmentOsSheetModel.SheetTable table) {
        var result = objectMapper.createObjectNode();
        result.set("headers", objectMapper.valueToTree(table.headers()));
        result.set("rows", objectMapper.valueToTree(table.rows()));
        return result;
    }

    private static boolean hasCombinedTotal(InvestmentOsSheetModel.SheetTable metrics) {
        return metrics.rows().stream().anyMatch(row -> "COMBINED".equalsIgnoreCase(cell(metrics, row, "Scope"))
                && !cell(metrics, row, "Total Value").isBlank());
    }

    private static InvestmentOsSheetModel.SheetTable portfolioMetricsKeepingVerifiedScopes(
            InvestmentOsSheetModel.SheetTable prior,
            InvestmentOsSheetModel.SheetTable accountState,
            Instant syncedAt
    ) {
        var verified = InvestmentOsSheetModel.portfolioMetrics(
                new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.metricsHeaders(), List.of()),
                accountState, syncedAt);
        var scopes = verified.rows().stream().map(row -> cell(verified, row, "Scope").toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        var withHistory = InvestmentOsSheetModel.portfolioMetrics(prior, accountState, syncedAt);
        return withHistory.withRows(withHistory.rows().stream()
                .filter(row -> scopes.contains(cell(withHistory, row, "Scope").toUpperCase(java.util.Locale.ROOT)))
                .toList());
    }

    private static OffsetDateTime databaseTimestamp(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static InvestmentOsSheetModel.SheetTable mergeManualRows(
            InvestmentOsSheetModel.SheetTable refreshed,
            InvestmentOsSheetModel.SheetTable original
    ) {
        var rows = new ArrayList<>(refreshed.rows());
        original.rows().stream().filter(row -> InvestmentOsSheetModel.ACCOUNT_2.equalsIgnoreCase(
                        cell(original, row, "Account")))
                .forEach(rows::add);
        return new InvestmentOsSheetModel.SheetTable(refreshed.headers(), rows);
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
        for (var start = 0; start < expected.size(); start += 20) {
            var symbols = expected.subList(start, Math.min(start + 20, expected.size()));
            try {
                var response = brokerSurface.prices(userId, connectionId, String.join(",", symbols));
                if (response == null || response.data() == null || response.stale()) {
                    var reason = response == null ? "EMPTY_RESPONSE" : response.stale()
                            ? "STALE_PRICE_RESPONSE"
                            : response.unavailableReason() == null ? "PRICE_UNAVAILABLE" : response.unavailableReason();
                    symbols.forEach(symbol -> errors.put(symbol, reason));
                    continue;
                }
                for (var price : response.data()) {
                    if (price != null && price.symbol() != null && price.lastPrice() != null
                            && price.lastPrice().signum() > 0) {
                        prices.putIfAbsent(price.symbol().toUpperCase(java.util.Locale.ROOT), price);
                    }
                }
                for (var symbol : symbols) {
                    if (!prices.containsKey(symbol)) {
                        var missingPrice = response.unknownFields().stream()
                                .filter(field -> field.equalsIgnoreCase(symbol + ".lastPrice"))
                                .findFirst().orElse("LAST_PRICE_MISSING");
                        errors.put(symbol, missingPrice);
                    }
                }
            } catch (RuntimeException exception) {
                symbols.forEach(symbol -> errors.put(symbol, safeError(exception)));
            }
        }
        var missing = expected.stream().filter(symbol -> !prices.containsKey(symbol)).toList();
        var failures = errors.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
        return new PriceSnapshot(Map.copyOf(prices), missing, failures);
    }

    private boolean shouldRefreshClosedOrders(Instant at) {
        if (closedOrdersRetryNotBefore != null && at.isBefore(closedOrdersRetryNotBefore)) return false;
        return closedOrdersFetchedAt == null
                || !at.isBefore(closedOrdersFetchedAt.plus(CLOSED_ORDER_REFRESH_INTERVAL));
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
