package com.jmj.trade.investment;

import com.jmj.trade.investment.InvestmentDataCalculator.DataStatus;
import com.jmj.trade.investment.tactical.TacticalOverlayService;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.analysis.StockAnalysisSnapshotHasher;
import com.jmj.trade.marketdata.StockAnalysisInput;
import com.jmj.trade.marketdata.StockAnalysisInputAssembler;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import com.jmj.trade.sheets.InvestmentOsSheetModel;
import com.jmj.trade.sheets.InvestmentOsSheetProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public final class InvestmentContextService {

    private static final Pattern TICKER = Pattern.compile("[A-Z0-9._-]{1,32}");
    private static final Pattern TOSS_MARKET_SYMBOL = Pattern.compile("[A-Z0-9.-]+");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId MANUAL_SHEET_ZONE = ZoneId.of("Asia/Seoul");
    private static final Duration MANUAL_SHEET_STALE_AFTER = Duration.ofDays(7);
    private static final List<String> FUNDAMENTAL_FIELDS = List.of(
            "marketCap", "fullyDilutedMarketCap", "enterpriseValue", "cash", "debt",
            "basicShares", "dilutedShares",
            "revenueTTM", "revenueGrowthYoY", "ebitdaTTM", "eps", "fcfTTM");
    private static final List<String> CONSENSUS_FIELDS = List.of(
            "revenueConsensus", "epsConsensus", "ebitdaConsensus", "fcfConsensus");
    private static final List<String> PRICE_SESSIONS = List.of(
            "REGULAR_CLOSE", "LIVE_REGULAR", "AFTER_HOURS", "PREMARKET");
    private static final Set<String> QUOTE_UPDATE_FIELDS = Set.of(
            "quote.price", "quote.volume", "quote.change-percent",
            "price.latestPrice", "price.session");
    private static final Set<String> ALPHA_PROVIDER_FAILURE_CODES = Set.of(
            "DAILY_QUOTA_EXHAUSTED", "REQUEST_IN_PROGRESS", "CACHE_UNAVAILABLE", "CACHE_CORRUPT",
            "API_ERROR", "INVALID_RESPONSE", "SOURCE_CONFLICT", "SYMBOL_MISMATCH",
            "NETWORK", "EMPTY_RESPONSE", "INTERRUPTED", "INVALID_API_KEY", "API_KEY_UNAVAILABLE",
            "RATE_LIMITED", "PREMIUM_ENDPOINT");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final StockAnalysisInputAssembler assembler;
    private final BrokerSurfaceService brokerSurface;
    private final StockAnalysisSnapshotHasher hasher;
    private final PortfolioReadService portfolios;
    private final MonitoringWatchlistService watchlist;
    private final RiskPolicyService riskPolicies;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration priceStaleAfter;
    private final Duration regularCloseStaleAfter;
    private final Duration fundamentalStaleAfter;
    private final Duration consensusStaleAfter;
    private InvestmentOsSheetProperties investmentOsSheetProperties;
    private TacticalOverlayService tacticalOverlayService;

    @Value("${investment.data.additional-symbols:}")
    private String additionalSymbols = "";

    public InvestmentContextService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            StockDataProviderRegistry providers,
            ObjectProvider<BrokerSurfaceService> brokerSurfaceProvider,
            PortfolioReadService portfolios,
            MonitoringWatchlistService watchlist,
            RiskPolicyService riskPolicies,
            @Value("${investment.data.price-stale-after:PT15M}") Duration priceStaleAfter,
            @Value("${investment.data.regular-close-stale-after:P7D}") Duration regularCloseStaleAfter,
            @Value("${investment.data.fundamental-stale-after:P210D}") Duration fundamentalStaleAfter,
            @Value("${investment.data.consensus-stale-after:P10D}") Duration consensusStaleAfter
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.assembler = new StockAnalysisInputAssembler(Objects.requireNonNull(providers, "providers"), Clock.systemUTC());
        this.brokerSurface = brokerSurfaceProvider == null ? null : brokerSurfaceProvider.getIfAvailable();
        this.hasher = new StockAnalysisSnapshotHasher(objectMapper);
        this.portfolios = Objects.requireNonNull(portfolios, "portfolios");
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
        this.riskPolicies = Objects.requireNonNull(riskPolicies, "riskPolicies");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Clock.systemUTC();
        this.priceStaleAfter = positive(priceStaleAfter, "priceStaleAfter");
        this.regularCloseStaleAfter = positive(regularCloseStaleAfter, "regularCloseStaleAfter");
        this.fundamentalStaleAfter = positive(fundamentalStaleAfter, "fundamentalStaleAfter");
        this.consensusStaleAfter = positive(consensusStaleAfter, "consensusStaleAfter");
    }

    @Autowired(required = false)
    public void setInvestmentOsSheetProperties(InvestmentOsSheetProperties properties) {
        this.investmentOsSheetProperties = properties;
    }

    @Autowired(required = false)
    public void setTacticalOverlayService(TacticalOverlayService service) {
        this.tacticalOverlayService = service;
    }

    private boolean configuredSheetOwner(UUID userId) {
        return investmentOsSheetProperties != null && investmentOsSheetProperties.enabled()
                && userId != null && userId.equals(investmentOsSheetProperties.userId());
    }

    private PortfolioView readSheetPortfolio(UUID userId) {
        var snapshots = latestSheetSnapshots(userId);
        var payload = snapshots.payload();
        if (payload == null) {
            var missing = snapshots.latestStatus() == null
                    ? List.of("SHEET_PORTFOLIO_NOT_SYNCED") : List.of("SHEET_REFRESH_FAILED");
            return new PortfolioView(null, List.of(), Map.of(), snapshots.latestStatus() != null,
                    missing, "DATA_MISSING", "TOSS_API+MANUAL_SHEET", null, null, null,
                    snapshots.latestStatus() == null ? "NOT_SYNCED" : "READ_FAILED",
                    snapshots.latestStatus(), snapshots.latestStatus() != null, false);
        }

        var accountState = sheetTable(payload.get("accountState"));
        var aggregate = sheetTable(payload.get("aggregate"));
        var metrics = sheetTable(payload.get("metrics"));
        var account1AsOf = instant(payload.get("account1AsOf"));
        var manualAsOf = localDate(text(payload.get("manualAsOf")));
        if (manualAsOf == null) manualAsOf = manualAsOfFromRows(accountState);
        var manualReadAt = instant(payload.get("manualReadAt"));
        var manualStatus = clean(text(payload.get("manualStatus")));
        if (manualStatus == null) manualStatus = "UNVERIFIED";
        if (manualAsOf == null) manualAsOf = InvestmentOsSheetModel.manualAsOf(accountState);
        var now = clock.instant();
        var manualRowsPresent = accountState.rows().stream().anyMatch(row ->
                InvestmentOsSheetModel.ACCOUNT_2.equalsIgnoreCase(sheetCell(accountState, row, "Account")));
        var today = now.atZone(MANUAL_SHEET_ZONE).toLocalDate();
        var manualStale = manualRowsPresent && "OK".equals(manualStatus)
                && (manualAsOf == null || manualAsOf.isAfter(today)
                || manualAsOf.isBefore(today.minusDays(MANUAL_SHEET_STALE_AFTER.toDays())));
        var latestAttemptFailed = "FAILED".equals(snapshots.latestStatus());
        var visibleManualStatus = latestAttemptFailed ? "LATEST_REFRESH_FAILED" : manualStatus;
        var missing = new ArrayList<String>();
        if (latestAttemptFailed) missing.add("SHEET_REFRESH_FAILED");
        if (!"OK".equals(manualStatus) && !"EMPTY_CONFIRMED".equals(manualStatus)) {
            missing.add("MANUAL_ACCOUNT_" + safeStatus(manualStatus));
        }
        if (manualRowsPresent && "OK".equals(manualStatus) && manualAsOf == null) {
            missing.add("MANUAL_AS_OF_MISSING");
        }
        if (manualStale) missing.add("MANUAL_AS_OF_STALE");

        var combinedTotal = combinedUsdTotal(metrics);
        if (combinedTotal == null || combinedTotal.signum() <= 0) missing.add("COMBINED_USD_TOTAL_UNAVAILABLE");
        var account1Fresh = isFresh(account1AsOf, priceStaleAfter, now);
        if (!account1Fresh) missing.add(account1AsOf == null ? "ACCOUNT1_AS_OF_MISSING" : "ACCOUNT1_AS_OF_STALE");
        var sheetPositions = sheetPositions(aggregate, accountState, combinedTotal, account1AsOf, now, missing);
        var acceptedStatus = snapshots.payloadStatus();
        if ("PARTIAL".equals(acceptedStatus)) missing.add("SHEET_SNAPSHOT_PARTIAL");
        var stale = latestAttemptFailed || manualStale || !account1Fresh || !sheetPositions.pricesFresh();
        var weightsComplete = combinedTotal != null && combinedTotal.signum() > 0
                && !sheetPositions.positions().isEmpty()
                && missing.stream().noneMatch(reason -> reason.startsWith("MIXED_CURRENCY_POSITION:"))
                && sheetPositions.positions().stream().allMatch(position -> position.quantity() != null
                && position.marketValue() != null && position.weight() != null);
        var riskNumbersAvailable = account1Fresh && sheetPositions.pricesFresh() && weightsComplete;
        var partial = !missing.isEmpty() || !"SUCCEEDED".equals(acceptedStatus);
        var status = stale ? "STALE" : partial ? "PARTIAL" : "OK";
        var totals = combinedTotal == null ? Map.<String, BigDecimal>of() : Map.of("USD", combinedTotal);
        return new PortfolioView(account1AsOf, sheetPositions.positions(), totals, stale, List.copyOf(missing), status,
                clean(text(payload.get("source"))) == null ? "TOSS_API+MANUAL_SHEET" : text(payload.get("source")),
                account1AsOf, manualAsOf, manualReadAt, visibleManualStatus, snapshots.latestStatus(), manualStale,
                riskNumbersAvailable);
    }

    private SheetSnapshotRows latestSheetSnapshots(UUID userId) {
        var rows = jdbc.query("""
                SELECT attempt.attempt_status AS latest_status,
                       attempt.attempted_at AS latest_attempt_at,
                       accepted.attempt_status AS payload_status,
                       accepted.attempted_at AS payload_at,
                       accepted.payload::text AS payload
                  FROM LATERAL (
                      SELECT attempt_status, attempted_at
                        FROM investment_os_portfolio_snapshots
                       WHERE user_id = ?
                       ORDER BY attempted_at DESC, created_at DESC, id DESC
                       LIMIT 1
                  ) attempt
                  LEFT JOIN LATERAL (
                      SELECT attempt_status, attempted_at, payload
                        FROM investment_os_portfolio_snapshots
                       WHERE user_id = ? AND payload IS NOT NULL
                       ORDER BY attempted_at DESC, created_at DESC, id DESC
                       LIMIT 1
                  ) accepted ON true
                """, (resultSet, rowNum) -> {
            var payloadText = resultSet.getString("payload");
            JsonNode payload = null;
            if (payloadText != null) {
                try {
                    payload = objectMapper.readTree(payloadText);
                } catch (JacksonException ignored) {
                    // A corrupt stored payload is treated as unavailable; the failed attempt remains auditable.
                }
            }
            return new SheetSnapshotRows(
                    resultSet.getString("latest_status"),
                    instant(resultSet.getObject("latest_attempt_at", OffsetDateTime.class)),
                    resultSet.getString("payload_status"),
                    instant(resultSet.getObject("payload_at", OffsetDateTime.class)), payload);
        }, userId, userId);
        return rows.isEmpty() ? SheetSnapshotRows.missing() : rows.getFirst();
    }

    private JsonNode latestAcceptedSheetPayload(UUID userId) {
        var snapshots = latestSheetSnapshots(userId);
        return snapshots.payload();
    }

    private SheetPositions sheetPositions(
            InvestmentOsSheetModel.SheetTable aggregate,
            InvestmentOsSheetModel.SheetTable accountState,
            BigDecimal combinedTotal,
            Instant account1AsOf,
            Instant now,
            List<String> missing
    ) {
        var positions = new LinkedHashMap<String, PositionView>();
        var mixedCurrency = new LinkedHashSet<String>();
        boolean pricesFresh = true;
        for (var row : aggregate.rows()) {
            var tickerValue = sheetCell(aggregate, row, "Ticker", "Asset");
            var ticker = safeTicker(tickerValue);
            if (ticker == null) continue;
            var currency = clean(sheetCell(aggregate, row, "Currency"));
            var sourceRows = matchingAccountRows(accountState, ticker, currency);
            var sourceClassifiesCash = !sourceRows.isEmpty() && sourceRows.stream()
                    .allMatch(source -> InvestmentOsSheetModel.isCash(accountState, source, tickerValue));
            if (ticker.startsWith("CASH_") || sheetDecimal(sheetCell(aggregate, row, "Cash")) != null
                    || sourceClassifiesCash) continue;
            if (mixedCurrency.contains(ticker)) continue;
            var quantity = sheetDecimal(sheetCell(aggregate, row, "Quantity", "Total Quantity"));
            var marketValue = sheetDecimal(sheetCell(aggregate, row, "Market Value"));
            var sourceCoverage = clean(sheetCell(aggregate, row, "Source Coverage"));
            var accountsIncluded = clean(sheetCell(aggregate, row, "Accounts Included"));
            var account1Rows = sourceRows.stream().filter(source -> InvestmentOsSheetModel.ACCOUNT_1.equalsIgnoreCase(
                    sheetCell(accountState, source, "Account"))).toList();
            var manualRows = sourceRows.stream().filter(source -> InvestmentOsSheetModel.ACCOUNT_2.equalsIgnoreCase(
                    sheetCell(accountState, source, "Account"))).toList();
            var quantityAsOf = account1Rows.isEmpty() ? null : account1AsOf;
            var priceRows = sourceRows.stream().map(source -> sheetDecimal(
                            sheetCell(accountState, source, "Current Price")))
                    .filter(Objects::nonNull).distinct().toList();
            var lastPrice = priceRows.size() == 1 ? priceRows.getFirst() : null;
            var priceInstants = sourceRows.stream().map(source -> sheetInstant(
                    sheetCell(accountState, source, "Price Synced At"))).toList();
            var priceAsOf = priceInstants.stream().filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            var tickerManualAsOf = manualRows.isEmpty() ? null
                    : InvestmentOsSheetModel.manualAsOf(accountState, ticker);
            var tickerManualStale = !manualRows.isEmpty() && !isFreshDate(tickerManualAsOf, now);
            var tickerPriceFresh = !sourceRows.isEmpty() && priceRows.size() == 1
                    && priceInstants.stream().allMatch(value -> isFresh(value, priceStaleAfter, now));
            pricesFresh &= tickerPriceFresh;
            if (!tickerPriceFresh) missing.add("POSITION_PRICE_AS_OF_STALE:" + ticker);
            if (tickerManualStale) missing.add(tickerManualAsOf == null
                    ? "POSITION_MANUAL_AS_OF_MISSING:" + ticker : "POSITION_MANUAL_AS_OF_STALE:" + ticker);
            var asOf = latestInstant(quantityAsOf, priceAsOf);
            var weight = combinedTotal != null && combinedTotal.signum() > 0
                    && "USD".equalsIgnoreCase(currency) && marketValue != null && marketValue.signum() >= 0
                    ? marketValue.divide(combinedTotal, MathContext.DECIMAL128) : null;
            var position = new PositionView(ticker, ticker, quantity, currency, marketValue, weight, lastPrice, asOf,
                    accountsIncluded, sourceCoverage, quantityAsOf, priceAsOf,
                    manualRows.isEmpty() ? null : tickerManualAsOf);
            var prior = positions.get(ticker);
            if (prior == null) positions.put(ticker, position);
            else if (Objects.equals(prior.currency(), currency)) {
                positions.put(ticker, new PositionView(ticker, prior.name(), add(prior.quantity(), quantity), currency,
                        add(prior.marketValue(), marketValue), add(prior.weight(), weight),
                        Objects.equals(prior.lastPrice(), lastPrice) ? prior.lastPrice() : null,
                        latestInstant(prior.asOf(), asOf), mergeText(prior.accountsIncluded(), accountsIncluded),
                        mergeText(prior.sourceCoverage(), sourceCoverage), latestInstant(prior.quantityAsOf(), quantityAsOf),
                        latestInstant(prior.priceAsOf(), priceAsOf),
                        earlierDate(prior.manualAsOf(), position.manualAsOf())));
            } else {
                positions.remove(ticker);
                mixedCurrency.add(ticker);
            }
            if (quantity == null) missing.add("POSITION_QUANTITY_UNKNOWN:" + ticker);
            if (marketValue == null) missing.add("POSITION_MARKET_VALUE_UNKNOWN:" + ticker);
        }
        mixedCurrency.forEach(ticker -> missing.add("MIXED_CURRENCY_POSITION:" + ticker));
        return new SheetPositions(positions.values().stream().sorted(Comparator.comparing(PositionView::ticker)).toList(),
                pricesFresh);
    }

    private List<List<String>> matchingAccountRows(
            InvestmentOsSheetModel.SheetTable accountState, String ticker, String currency
    ) {
        return accountState.rows().stream().filter(row -> ticker.equalsIgnoreCase(
                        sheetCell(accountState, row, "Ticker", "Asset")))
                .filter(row -> currency == null || currency.equalsIgnoreCase(sheetCell(accountState, row, "Currency")))
                .filter(row -> InvestmentOsSheetModel.ACCOUNT_1.equalsIgnoreCase(
                        sheetCell(accountState, row, "Account"))
                        || InvestmentOsSheetModel.ACCOUNT_2.equalsIgnoreCase(
                        sheetCell(accountState, row, "Account")))
                .toList();
    }

    private BigDecimal combinedUsdTotal(InvestmentOsSheetModel.SheetTable metrics) {
        for (var row : metrics.rows()) {
            if ("COMBINED".equalsIgnoreCase(sheetCell(metrics, row, "Scope"))) {
                return sheetDecimal(sheetCell(metrics, row, "Total Value"));
            }
        }
        return null;
    }

    private LocalDate manualAsOfFromRows(InvestmentOsSheetModel.SheetTable accountState) {
        return InvestmentOsSheetModel.manualAsOf(accountState);
    }

    private boolean isFreshDate(LocalDate asOf, Instant now) {
        if (asOf == null) return false;
        var today = now.atZone(MANUAL_SHEET_ZONE).toLocalDate();
        return !asOf.isAfter(today) && !asOf.isBefore(today.minusDays(MANUAL_SHEET_STALE_AFTER.toDays()));
    }

    private static boolean isFresh(Instant asOf, Duration maxAge, Instant now) {
        return asOf != null && !asOf.isAfter(now) && !asOf.isBefore(now.minus(maxAge));
    }

    private static LocalDate earlierDate(LocalDate first, LocalDate second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }

    private static InvestmentOsSheetModel.SheetTable sheetTable(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return new InvestmentOsSheetModel.SheetTable(List.of(), List.of());
        }
        var headers = new ArrayList<String>();
        var headerNodes = node.path("headers");
        if (headerNodes.isArray()) for (var header : headerNodes) headers.add(header.asText(""));
        var rows = new ArrayList<List<String>>();
        var rowNodes = node.path("rows");
        if (rowNodes.isArray()) for (var rowNode : rowNodes) {
            var row = new ArrayList<String>();
            if (rowNode.isArray()) for (var cell : rowNode) row.add(cell.isNull() ? "" : cell.asText(""));
            rows.add(row);
        }
        return new InvestmentOsSheetModel.SheetTable(headers, rows);
    }

    private static String sheetCell(InvestmentOsSheetModel.SheetTable table, List<String> row, String... names) {
        for (var name : names) {
            var column = table.headers().stream().filter(header -> normalizeHeader(header).equals(normalizeHeader(name)))
                    .findFirst().orElse(null);
            if (column == null) continue;
            var index = table.headers().indexOf(column);
            if (index < row.size() && row.get(index) != null && !row.get(index).isBlank()) return row.get(index).trim();
        }
        return "";
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static BigDecimal sheetDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new BigDecimal(value.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Instant sheetInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value.trim()); }
        catch (RuntimeException ignored) {
            try { return OffsetDateTime.parse(value.trim()).toInstant(); }
            catch (RuntimeException ignoredOffset) { return null; }
        }
    }

    private static String safeTicker(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        return TICKER.matcher(normalized).matches() ? normalized : null;
    }

    private static String mergeText(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank() || first.equals(second)) return first;
        return first + "," + second;
    }

    private static String safeStatus(String status) {
        return status == null ? "UNVERIFIED" : status.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private record SheetSnapshotRows(String latestStatus, Instant latestAttemptAt, String payloadStatus,
                                     Instant payloadAt, JsonNode payload) {
        private static SheetSnapshotRows missing() {
            return new SheetSnapshotRows(null, null, null, null, null);
        }
    }

    private record SheetPositions(List<PositionView> positions, boolean pricesFresh) {
    }

    public ContextView context(UUID userId) {
        requireUser(userId);
        var portfolio = readPortfolio(userId);
        var watchEntries = watchlist.list(userId);
        var symbols = new LinkedHashSet<String>();
        portfolio.positions().forEach(position -> symbols.add(position.ticker()));
        watchEntries.stream().filter(entry -> !"INVALIDATED".equals(entry.status()))
                .map(MonitoringWatchlistService.WatchlistEntry::symbol).forEach(symbols::add);
        additionalSymbols().forEach(symbols::add);
        if (tacticalOverlayService != null) tacticalOverlayService.trackedSymbols().forEach(symbols::add);
        var tacticalReadModel = tacticalOverlayService == null
                ? new TacticalOverlayService.ReadModel(TacticalOverlayPortfolioView.notConfigured(), Map.of(), Map.of())
                : tacticalOverlayService.context(userId, List.copyOf(symbols));

        var positions = new LinkedHashMap<String, PositionView>();
        portfolio.positions().forEach(position -> positions.put(position.ticker(), position));
        var analysis = new LinkedHashMap<String, JsonNode>();
        var theses = theses(userId, symbols);
        for (var symbol : symbols) {
            analysis.put(symbol, latestSecuritySnapshot(userId, symbol));
        }
        var weights = portfolioWeights(portfolio);
        var risks = riskContributions(userId, symbols, positions, weights, portfolio, theses, analysis);
        var securities = symbols.stream().sorted().map(symbol -> {
            var snapshot = analysis.get(symbol);
            var risk = risks.get(symbol);
            if (snapshot instanceof ObjectNode objectSnapshot) {
                var readiness = object(objectSnapshot, "readiness");
                readiness.put("riskStatus", risk.status().name());
                var statuses = List.of(
                        dataStatus(text(readiness.get("priceStatus"))),
                        dataStatus(text(readiness.get("trendStatus"))),
                        dataStatus(text(readiness.get("fundamentalStatus"))),
                        dataStatus(text(readiness.get("consensusStatus"))),
                        dataStatus(text(readiness.get("revisionStatus"))),
                        dataStatus(text(readiness.get("valuationStatus"))),
                        dataStatus(text(readiness.get("balanceSheetStatus"))),
                        risk.status());
                readiness.put("overallDataStatus", overall(statuses).name());
            }
            return new SecurityView(
                    symbol,
                    positions.get(symbol),
                    instant(snapshot.get("asOf")),
                    node(snapshot, "price"),
                    node(snapshot, "technical"),
                    node(snapshot, "fundamentals"),
                    node(snapshot, "consensus"),
                    node(snapshot, "revision"),
                    node(snapshot, "valuation"),
                    node(snapshot, "readiness"),
                    theses.get(symbol),
                    risk,
                    tacticalReadModel.securities().getOrDefault(symbol, SecurityTacticalOverlayView.notConfigured()));
        }).toList();
        return new ContextView(
                portfolio,
                securities,
                watchEntries.stream().map(InvestmentContextService::watchlistView).toList(),
                riskPolicies.current(userId),
                decisionLedger(userId, 50),
                pipelineState(userId, "SECURITY_DATA"),
                tacticalReadModel.portfolio(),
                tacticalReadModel.decisions());
    }

    public TacticalOverlayService.TacticalInputsView tacticalOverlayInputs(UUID userId) {
        requireUser(userId);
        return tacticalService().inputs(userId);
    }

    public UUID putTacticalAnchor(UUID userId, String ticker, TacticalOverlayService.AnchorInput input) {
        requireUser(userId);
        return tacticalService().putAnchor(userId, ticker, input);
    }

    public UUID deleteTacticalAnchor(UUID userId, String ticker, String anchorId, Instant sourceAsOf) {
        requireUser(userId);
        return tacticalService().deleteAnchor(userId, ticker, anchorId, sourceAsOf);
    }

    public UUID putTacticalTheme(UUID userId, TacticalOverlayService.ThemeInput input) {
        requireUser(userId);
        return tacticalService().putTheme(userId, input);
    }

    public UUID deleteTacticalTheme(UUID userId, String themeId, Instant sourceAsOf) {
        requireUser(userId);
        return tacticalService().deleteTheme(userId, themeId, sourceAsOf);
    }

    public UUID putTacticalThemeMapping(UUID userId, TacticalOverlayService.ThemeMappingInput input) {
        requireUser(userId);
        return tacticalService().putThemeMapping(userId, input);
    }

    public UUID deleteTacticalThemeMapping(UUID userId, String themeId, String ticker,
                                           LocalDate effectiveDate, Instant sourceAsOf) {
        requireUser(userId);
        return tacticalService().deleteThemeMapping(userId, themeId, ticker, effectiveDate, sourceAsOf);
    }

    public UUID putTacticalPerformanceEntry(UUID userId, String ticker,
                                            TacticalOverlayService.PerformanceInput input) {
        requireUser(userId);
        return tacticalService().putPerformanceEntry(userId, ticker, input);
    }

    public UUID deleteTacticalPerformanceEntry(UUID userId, String ticker, String key, Instant sourceAsOf) {
        requireUser(userId);
        return tacticalService().deletePerformanceEntry(userId, ticker, key, sourceAsOf);
    }

    private TacticalOverlayService tacticalService() {
        if (tacticalOverlayService == null) throw new IllegalStateException("tactical overlay service unavailable");
        return tacticalOverlayService;
    }

    public int capture(UUID userId) {
        return capture(userId, null);
    }

    public int captureQuoteUpdates(UUID userId) {
        return capture(userId, QUOTE_UPDATE_FIELDS);
    }

    private int capture(UUID userId, Set<String> selectedFields) {
        requireUser(userId);
        var pipeline = selectedFields == null ? "SECURITY_DATA" : "SECURITY_QUOTE_UPDATE";
        var symbols = captureSymbols(userId);
        markPipeline(userId, pipeline, "RUNNING", null, null);
        try {
            var captured = 0;
            boolean sourceResponded = false;
            boolean missingOptionalData = false;
            String providerFailure = null;
            boolean canonicalMissing = false;
            boolean quoteCanonicalMissing = false;
            var quotedSymbols = new LinkedHashSet<String>();
            var tossQuotes = tossQuotes(userId, List.copyOf(symbols));
            var calendars = new HashMap<LocalDate, JsonNode>();
            for (var symbol : symbols) {
                var input = assembler.assemble(symbol, Map.of(), selectedFields);
                if (tossQuotes != null) {
                    input = withTossQuote(userId, input, tossQuotes, calendars);
                    if (selectedFields == null) {
                        input = withTossCandles(userId, input, tossQuotes, calendars);
                    }
                }
                var capturedInput = input;
                var missingReadinessFields = transaction.execute(
                        status -> persistCapture(userId, capturedInput, selectedFields == null));
                missingOptionalData |= Boolean.TRUE.equals(missingReadinessFields);
                sourceResponded |= !input.observations().isEmpty();
                missingOptionalData |= input.observations().isEmpty()
                        || input.observations().stream().anyMatch(observation -> !observation.missingData().isEmpty());
                if (providerFailure == null) providerFailure = providerFailure(input);
                if (selectedFields != null && !hasTossLatestPrice(input)) quoteCanonicalMissing = true;
                else if (selectedFields != null) quotedSymbols.add(symbol);
                if (selectedFields == null && !canonicalDataReady(userId, symbol, input.collectedAt())) {
                    canonicalMissing = true;
                }
                captured++;
            }
            if (selectedFields == null && tacticalOverlayService != null) {
                missingOptionalData |= captureTacticalOverlay(userId, tossQuotes, calendars);
            }
            if (selectedFields != null && tacticalOverlayService != null && !quotedSymbols.isEmpty()) {
                try {
                    tacticalOverlayService.refreshPerformanceMarks(userId, List.copyOf(quotedSymbols));
                } catch (RuntimeException ignored) {
                    missingOptionalData = true;
                }
            }
            if (quoteCanonicalMissing) {
                markPipeline(userId, pipeline, "FAILED", null,
                        "CANONICAL_REQUIRED_DATA_MISSING:TOSS_LATEST_PRICE_MISSING");
                return captured;
            }
            if (!sourceResponded) {
                markPipeline(userId, pipeline, "FAILED", null, "NO_DATA_COLLECTED");
                return captured;
            }
            if (selectedFields == null && canonicalMissing) {
                var error = "CANONICAL_REQUIRED_DATA_MISSING"
                        + (providerFailure == null ? "" : ":" + providerFailure);
                markPipeline(userId, pipeline, "FAILED", null, error);
                return captured;
            }
            if (providerFailure != null || missingOptionalData) {
                markPipeline(userId, pipeline, "PARTIAL", clock.instant(),
                        providerFailure == null ? "OPTIONAL_PROVIDER_OR_FIELD_MISSING" : providerFailure);
            } else {
                markPipeline(userId, pipeline, "SUCCEEDED", clock.instant(), null);
            }
            return captured;
        } catch (RuntimeException exception) {
            markPipeline(userId, pipeline, "FAILED", null, safeError(exception));
            throw exception;
        }
    }

    public int captureAll() {
        return captureAll(null);
    }

    public int captureAllQuoteUpdates() {
        return captureAll(QUOTE_UPDATE_FIELDS);
    }

    boolean needsInitialCapture() {
        for (var userId : captureUsers()) {
            for (var ticker : captureSymbols(userId)) {
                var hasPrice = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM investment_price_snapshots
                             WHERE user_id = ? AND ticker = ? AND source = 'TOSS'
                               AND session = 'REGULAR_CLOSE' AND regular_close > 0
                               AND regular_close_as_of >= ? AND regular_close_as_of <= ?
                        )
                        """, Boolean.class, userId, ticker,
                        timestamp(clock.instant().minus(regularCloseStaleAfter)), timestamp(clock.instant()));
                var hasFundamental = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM fundamental_snapshots
                         WHERE user_id = ? AND ticker = ? AND source = 'SEC'
                           AND cash IS NOT NULL AND revenue_ttm IS NOT NULL
                           AND fiscal_period IS NOT NULL AND reported_at IS NOT NULL
                           AND field_provenance #>> '{cash,asOf}' IS NOT NULL
                           AND field_provenance #>> '{revenueTTM,asOf}' IS NOT NULL
                           AND (field_provenance #>> '{cash,asOf}')::timestamptz >= ?
                           AND (field_provenance #>> '{cash,asOf}')::timestamptz <= ?
                           AND (field_provenance #>> '{revenueTTM,asOf}')::timestamptz >= ?
                           AND (field_provenance #>> '{revenueTTM,asOf}')::timestamptz <= ?)
                        """, Boolean.class, userId, ticker,
                        timestamp(clock.instant().minus(fundamentalStaleAfter)), timestamp(clock.instant()),
                        timestamp(clock.instant().minus(fundamentalStaleAfter)), timestamp(clock.instant()));
                if (!Boolean.TRUE.equals(hasPrice) || !Boolean.TRUE.equals(hasFundamental)) return true;
            }
            if (tacticalOverlayService != null
                    && tacticalOverlayService.needsInitialCapture(userId, List.copyOf(captureSymbols(userId)))) {
                return true;
            }
        }
        return false;
    }

    private int captureAll(Set<String> selectedFields) {
        var users = captureUsers();
        var count = 0;
        boolean failed = false;
        var pipeline = selectedFields == null ? "SECURITY_DATA" : "SECURITY_QUOTE_UPDATE";
        for (var userId : users) {
            try {
                count += capture(userId, selectedFields);
                failed |= "FAILED".equals(pipelineState(userId, pipeline).status());
            } catch (RuntimeException ignored) {
                // A user/provider failure is isolated; their pipeline row records the failure.
                failed = true;
            }
        }
        if (failed) throw new IllegalStateException("one or more investment data captures failed");
        return count;
    }

    private List<UUID> captureUsers() {
        return jdbc.query("""
                SELECT user_id FROM broker_connections WHERE status = 'ACTIVE' AND deleted_at IS NULL
                UNION
                SELECT user_id FROM monitoring_watchlist WHERE status IN ('WATCH', 'PREPARE', 'ACTION_CANDIDATE')
                ORDER BY user_id
                """, (resultSet, rowNum) -> resultSet.getObject(1, UUID.class));
    }

    private LinkedHashSet<String> captureSymbols(UUID userId) {
        var symbols = new LinkedHashSet<String>();
        latestHeldSymbols(userId).forEach(symbols::add);
        jdbc.query("""
                SELECT symbol FROM monitoring_watchlist
                 WHERE user_id = ? AND status IN ('WATCH', 'PREPARE', 'ACTION_CANDIDATE')
                 ORDER BY symbol
                """, (resultSet, rowNum) -> resultSet.getString(1), userId).forEach(symbols::add);
        additionalSymbols().forEach(symbols::add);
        if (tacticalOverlayService != null) tacticalOverlayService.trackedSymbols().forEach(symbols::add);
        return symbols;
    }

    private boolean captureTacticalOverlay(UUID userId, TossQuoteSource quotes,
                                           Map<LocalDate, JsonNode> calendars) {
        var attemptedAt = clock.instant();
        boolean benchmarkMissing = quotes == null;
        if (!benchmarkMissing) {
            var emptyInput = new StockAnalysisInput(UUID.randomUUID(), "SPY", "TACTICAL_V1", attemptedAt, List.of());
            var candleInput = withTossCandles(userId, emptyInput, quotes, calendars);
            var history = candleInput.observations().stream()
                    .filter(value -> "price.regularCloseHistory".equals(value.field()))
                    .filter(value -> value.provider() == StockDataProviderId.TOSS)
                    .filter(InvestmentContextService::usableTacticalBarObservation)
                    .findFirst().orElse(null);
            if (history == null) {
                benchmarkMissing = true;
            } else {
                tacticalOverlayService.recordBenchmarkBars(userId, history.value(), candleInput.collectedAt());
            }
        }
        try {
            tacticalOverlayService.refresh(userId);
        } catch (RuntimeException ignored) {
            tacticalOverlayService.recordUnavailableBenchmark(userId, attemptedAt, "TACTICAL_REFRESH_FAILED");
            return true;
        }
        if (benchmarkMissing) {
            tacticalOverlayService.recordUnavailableBenchmark(userId, attemptedAt,
                    quotes == null ? "TOSS_CONNECTION_UNAVAILABLE" : "TOSS_CANDLES_UNAVAILABLE");
        }
        return benchmarkMissing;
    }

    private static boolean usableTacticalBarObservation(StockAnalysisInput.Observation observation) {
        return observation != null && observation.provider() == StockDataProviderId.TOSS
                && "price.regularCloseHistory".equals(observation.field())
                && observation.value() != null && observation.value().isArray()
                && observation.missingData().contains("AS_OF_UNAVAILABLE")
                && observation.missingData().stream().allMatch(reason -> "AS_OF_UNAVAILABLE".equals(reason)
                        || "TOSS_CANDLE_DUPLICATE_CONFLICT".equals(reason));
    }

    private Set<String> additionalSymbols() {
        if (additionalSymbols == null || additionalSymbols.isBlank()) return Set.of();
        var result = new LinkedHashSet<String>();
        for (var raw : additionalSymbols.split(",")) {
            if (raw.isBlank()) continue;
            var symbol = ticker(raw);
            if (!TOSS_MARKET_SYMBOL.matcher(symbol).matches()) {
                throw new IllegalArgumentException("additional investment symbols must be US market symbols");
            }
            result.add(symbol);
        }
        return Set.copyOf(result);
    }

    private TossQuoteSource tossQuotes(UUID userId, List<String> symbols) {
        if (brokerSurface == null || symbols.isEmpty()) return null;
        var connectionId = jdbc.query("""
                SELECT id FROM broker_connections
                 WHERE user_id = ? AND broker_type = 'TOSS_INVEST' AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                 ORDER BY id
                """, (resultSet, rowNum) -> resultSet.getObject(1, UUID.class), userId)
                .stream().findFirst().orElse(null);
        if (connectionId == null) return null;

        var requested = symbols.stream()
                .map(symbol -> symbol.toUpperCase(Locale.ROOT))
                .filter(symbol -> TOSS_MARKET_SYMBOL.matcher(symbol).matches())
                .toList();
        var quotes = new LinkedHashMap<String, BrokerSurfaceResponse.PriceView>();
        for (var symbol : requested) {
            try {
                var response = brokerSurface.prices(userId, connectionId, symbol);
                if (response == null || response.unavailable() || response.data() == null) continue;
                for (var price : response.data()) {
                    if (price == null || price.symbol() == null) continue;
                    if (symbol.equalsIgnoreCase(price.symbol())) quotes.putIfAbsent(symbol, price);
                }
            } catch (RuntimeException ignored) {
                // The broker quote is supplemental; preserve independent provider capture.
            }
        }
        return new TossQuoteSource(connectionId, Map.copyOf(quotes));
    }

    private StockAnalysisInput withTossQuote(
            UUID userId,
            StockAnalysisInput input,
            TossQuoteSource source,
            Map<LocalDate, JsonNode> calendars
    ) {
        var quote = source.quotes().get(input.symbol().toUpperCase(Locale.ROOT));
        if (quote == null) return input;
        var observations = new ArrayList<>(input.observations());
        var asOf = quote.brokerTimestamp();
        var collectedAt = input.collectedAt();
        if (!"USD".equalsIgnoreCase(quote.currency())) {
            observations.add(tossMissing("price.latestPrice", "TOSS_CURRENCY_NOT_USD", collectedAt));
            return withObservations(input, observations);
        }
        if (quote.lastPrice() == null || quote.lastPrice().signum() <= 0) {
            observations.add(tossMissing("price.latestPrice", "TOSS_PRICE_INVALID", collectedAt));
            return withObservations(input, observations);
        }

        var priceValue = objectMapper.valueToTree(quote.lastPrice());
        if (asOf == null) {
            observations.add(new StockAnalysisInput.Observation(
                    "price.latestPrice", priceValue, "USD", null, null, StockDataProviderId.TOSS,
                    null, collectedAt, List.of("TOSS_TIMESTAMP_MISSING")));
            observations.add(tossMissing("price.session", "TOSS_TIMESTAMP_MISSING", collectedAt));
            return withObservations(input, observations);
        }
        if (asOf.isAfter(collectedAt)) {
            observations.add(new StockAnalysisInput.Observation(
                    "price.latestPrice", priceValue, "USD", null, null, StockDataProviderId.TOSS,
                    asOf, collectedAt, List.of("TOSS_TIMESTAMP_FUTURE")));
            observations.add(tossMissing("price.session", "TOSS_TIMESTAMP_FUTURE", collectedAt));
            return withObservations(input, observations);
        }

        observations.add(new StockAnalysisInput.Observation(
                "price.latestPrice", priceValue, "USD", null, null, StockDataProviderId.TOSS,
                asOf, collectedAt, List.of()));
        var marketDate = asOf.atZone(NEW_YORK).toLocalDate();
        var calendar = tossCalendarCached(userId, source.connectionId(), marketDate, calendars);
        var sessionResult = tossSessionResult(asOf, marketDate, calendar);
        if (sessionResult.session() == null) {
            observations.add(tossMissing("price.session", sessionResult.missingReason(), collectedAt));
        } else {
            observations.add(new StockAnalysisInput.Observation(
                    "price.session", objectMapper.valueToTree(sessionResult.session()), null, null, null,
                    StockDataProviderId.TOSS, asOf, collectedAt, List.of()));
        }
        return withObservations(input, observations);
    }

    private StockAnalysisInput withTossCandles(
            UUID userId,
            StockAnalysisInput input,
            TossQuoteSource source,
            Map<LocalDate, JsonNode> calendars
    ) {
        var observations = new ArrayList<>(input.observations());
        var collectedAt = input.collectedAt();
        try {
            var response = brokerSurface.candles(
                    userId, source.connectionId(), input.symbol(), "1d", 100, null, false);
            if (response == null || response.unavailable() || response.data() == null) {
                observations.add(tossMissing(
                        "price.regularCloseHistory", "TOSS_CANDLES_UNAVAILABLE", collectedAt));
                return withObservations(input, observations);
            }
            var series = response.data();
            if (!input.symbol().equalsIgnoreCase(series.symbol()) || !"1d".equals(series.interval())
                    || series.adjusted()) {
                observations.add(tossMissing(
                        "price.regularCloseHistory", "TOSS_CANDLES_CONTRACT_INVALID", collectedAt));
                return withObservations(input, observations);
            }
            if (series.candles().isEmpty()) {
                observations.add(tossMissing("price.regularCloseHistory", "TOSS_CANDLES_EMPTY", collectedAt));
                return withObservations(input, observations);
            }

            var marketDate = collectedAt.atZone(NEW_YORK).toLocalDate();
            var candlesByDate = new java.util.TreeMap<LocalDate, BrokerSurfaceResponse.CandleView>();
            var conflictingCandles = new java.util.TreeMap<LocalDate, List<BrokerSurfaceResponse.CandleView>>();
            var conflictingDates = new java.util.HashSet<LocalDate>();
            var rejected = new LinkedHashSet<String>();
            for (var candle : series.candles()) {
                if (candle == null || candle.timestamp() == null) {
                    rejected.add("TOSS_CANDLE_TIMESTAMP_MISSING");
                    continue;
                }
                if (candle.currency() == null || !"USD".equalsIgnoreCase(candle.currency())) {
                    rejected.add("TOSS_CANDLE_CURRENCY_MISMATCH");
                    continue;
                }
                if (candle.openPrice() == null || candle.highPrice() == null || candle.lowPrice() == null
                        || candle.closePrice() == null || candle.openPrice().signum() <= 0
                        || candle.highPrice().signum() <= 0 || candle.lowPrice().signum() <= 0
                        || candle.closePrice().signum() <= 0
                        || candle.highPrice().compareTo(candle.openPrice()) < 0
                        || candle.highPrice().compareTo(candle.closePrice()) < 0
                        || candle.lowPrice().compareTo(candle.openPrice()) > 0
                        || candle.lowPrice().compareTo(candle.closePrice()) > 0
                        || candle.highPrice().compareTo(candle.lowPrice()) < 0) {
                    rejected.add("TOSS_CANDLE_PRICE_INVALID");
                    continue;
                }
                if (candle.volume() == null || candle.volume().signum() < 0) {
                    rejected.add("TOSS_CANDLE_VOLUME_INVALID");
                    continue;
                }
                if (candle.timestamp().isAfter(collectedAt)) {
                    rejected.add("TOSS_CANDLE_FUTURE");
                    continue;
                }
                var candleTime = candle.timestamp().atZone(NEW_YORK);
                if (!candleTime.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
                    rejected.add("TOSS_CANDLE_TIMESTAMP_INVALID");
                    continue;
                }
                var date = candleTime.toLocalDate();
                if (date.isAfter(marketDate)) {
                    rejected.add("TOSS_CANDLE_FUTURE");
                    continue;
                }
                if (date.equals(marketDate)) {
                    var calendar = tossCalendarCached(userId, source.connectionId(), date, calendars);
                    var finality = tossCandleFinalityReason(date, calendar, collectedAt);
                    if (finality != null) {
                        rejected.add(finality);
                        continue;
                    }
                }
                if (conflictingDates.contains(date)) {
                    var candidates = conflictingCandles.get(date);
                    if (candidates.stream().noneMatch(existing -> sameCandle(existing, candle))) candidates.add(candle);
                    continue;
                }
                var previous = candlesByDate.putIfAbsent(date, candle);
                if (previous != null && !sameCandle(previous, candle)) {
                    conflictingDates.add(date);
                    candlesByDate.remove(date);
                    conflictingCandles.put(date, new ArrayList<>(List.of(previous, candle)));
                }
            }
            if (!conflictingDates.isEmpty()) rejected.add("TOSS_CANDLE_DUPLICATE_CONFLICT");
            if (candlesByDate.isEmpty() && conflictingCandles.isEmpty()) {
                if (rejected.isEmpty()) rejected.add("TOSS_CANDLES_EMPTY");
                rejected.forEach(reason -> observations.add(
                        tossMissing("price.regularCloseHistory", reason, collectedAt)));
                return withObservations(input, observations);
            }

            var rows = objectMapper.createArrayNode();
            candlesByDate.forEach((date, candle) -> {
                addTossCandleRow(rows, date, candle, false);
            });
            conflictingCandles.forEach((date, candidates) -> candidates.forEach(candle ->
                    addTossCandleRow(rows, date, candle, true)));
            observations.add(new StockAnalysisInput.Observation(
                    "price.regularCloseHistory", rows, null, null, null, StockDataProviderId.TOSS,
                    null, collectedAt, List.of("AS_OF_UNAVAILABLE")));
            rejected.forEach(reason -> observations.add(
                    tossMissing("price.regularCloseHistory", reason, collectedAt)));
        } catch (RuntimeException ignored) {
            observations.add(tossMissing("price.regularCloseHistory", "TOSS_CANDLES_UNAVAILABLE", collectedAt));
        }
        return withObservations(input, observations);
    }

    private static void addTossCandleRow(ArrayNode rows, LocalDate date,
                                         BrokerSurfaceResponse.CandleView candle, boolean sourceConflict) {
                var row = rows.addObject();
                row.put("date", date.toString());
                row.put("timestamp", candle.timestamp().toString());
                row.put("session", "REGULAR_CLOSE");
                row.put("open", candle.openPrice());
                row.put("high", candle.highPrice());
                row.put("low", candle.lowPrice());
                row.put("close", candle.closePrice());
                row.put("volume", candle.volume());
                row.put("currency", candle.currency());
                row.put("sourceConflict", sourceConflict);
    }

    private JsonNode tossCalendarCached(
            UUID userId, UUID connectionId, LocalDate date, Map<LocalDate, JsonNode> calendars
    ) {
        if (!calendars.containsKey(date)) calendars.put(date, tossCalendar(userId, connectionId, date));
        return calendars.get(date);
    }

    static String tossCandleFinalityReason(LocalDate date, JsonNode calendar, Instant now) {
        if (date == null || calendar == null || now == null) return "TOSS_CANDLE_SESSION_UNVERIFIED";
        JsonNode matchingDay = null;
        var matchingDays = 0;
        for (var dayName : List.of("today", "previousBusinessDay", "nextBusinessDay")) {
            var day = calendar.path(dayName);
            if (date.toString().equals(day.path("date").asText(null))) {
                matchingDay = day;
                matchingDays++;
            }
        }
        if (matchingDays != 1) return "TOSS_CANDLE_SESSION_UNVERIFIED";
        var interval = matchingDay.path("regularMarket");
        if (!interval.isObject()) return "TOSS_CANDLE_SESSION_UNVERIFIED";
        var start = tossCalendarBound(interval, "startTime", "open", "openTime", "start");
        var end = tossCalendarBound(interval, "endTime", "close", "closeTime", "end");
        if (start == null || end == null || !start.isBefore(end)
                || !date.equals(start.atZone(NEW_YORK).toLocalDate())
                || !date.equals(end.minusNanos(1).atZone(NEW_YORK).toLocalDate())) {
            return "TOSS_CANDLE_SESSION_UNVERIFIED";
        }
        return end.isAfter(now) ? "TOSS_CANDLE_NOT_FINAL" : null;
    }

    private static boolean sameCandle(
            BrokerSurfaceResponse.CandleView left, BrokerSurfaceResponse.CandleView right
    ) {
        return left.openPrice().compareTo(right.openPrice()) == 0
                && left.highPrice().compareTo(right.highPrice()) == 0
                && left.lowPrice().compareTo(right.lowPrice()) == 0
                && left.closePrice().compareTo(right.closePrice()) == 0
                && left.volume().compareTo(right.volume()) == 0
                && left.currency().equalsIgnoreCase(right.currency());
    }

    private JsonNode tossCalendar(UUID userId, UUID connectionId, LocalDate date) {
        try {
            var response = brokerSurface.marketCalendar(userId, connectionId, "US", date);
            return response == null || response.unavailable() || response.data() == null
                    || !"US".equals(response.data().market())
                    ? null : response.data().payload();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String tossSession(Instant quoteTimestamp, LocalDate marketDate, JsonNode calendar) {
        return tossSessionResult(quoteTimestamp, marketDate, calendar).session();
    }

    private static TossSessionResult tossSessionResult(
            Instant quoteTimestamp, LocalDate marketDate, JsonNode calendar
    ) {
        if (quoteTimestamp == null || marketDate == null || calendar == null
                || !marketDate.equals(quoteTimestamp.atZone(NEW_YORK).toLocalDate())) {
            return new TossSessionResult(null, "TOSS_SESSION_UNVERIFIED");
        }
        JsonNode matchingDay = null;
        var matchingDays = 0;
        for (var dayName : List.of("today", "previousBusinessDay", "nextBusinessDay")) {
            var day = calendar.path(dayName);
            if (marketDate.toString().equals(day.path("date").asText(null))) {
                matchingDay = day;
                matchingDays++;
            }
        }
        if (matchingDays != 1) return new TossSessionResult(null, "TOSS_SESSION_UNVERIFIED");
        var matches = new ArrayList<String>();
        var intervalCount = 0;
        var allIntervalsVerified = true;
        var unsupportedSessionMatched = false;
        for (var field : List.of("dayMarket", "preMarket", "regularMarket", "afterMarket")) {
            var interval = matchingDay.get(field);
            if (interval == null || interval.isNull()) continue;
            intervalCount++;
            if (!interval.isObject()) {
                allIntervalsVerified = false;
                continue;
            }
            var start = tossCalendarBound(interval, "startTime", "open", "openTime", "start");
            var end = tossCalendarBound(interval, "endTime", "close", "closeTime", "end");
            if (start == null || end == null || !start.isBefore(end)) {
                allIntervalsVerified = false;
                continue;
            }
            if (!"dayMarket".equals(field)
                    && (!start.atZone(NEW_YORK).toLocalDate().equals(marketDate)
                    || !end.minusNanos(1).atZone(NEW_YORK).toLocalDate().equals(marketDate))) {
                allIntervalsVerified = false;
                continue;
            }
            if (!quoteTimestamp.isBefore(start) && quoteTimestamp.isBefore(end)) {
                if ("dayMarket".equals(field)) {
                    unsupportedSessionMatched = true;
                } else {
                    matches.add(switch (field) {
                        case "preMarket" -> "PREMARKET";
                        case "regularMarket" -> "LIVE_REGULAR";
                        case "afterMarket" -> "AFTER_HOURS";
                        default -> throw new IllegalStateException("unexpected Toss session");
                    });
                }
            }
        }
        if (matches.size() == 1 && !unsupportedSessionMatched) {
            return new TossSessionResult(matches.getFirst(), null);
        }
        if (matches.isEmpty() && !unsupportedSessionMatched && intervalCount > 0 && allIntervalsVerified) {
            return new TossSessionResult(null, "TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS");
        }
        return new TossSessionResult(null, "TOSS_SESSION_UNVERIFIED");
    }

    private record TossSessionResult(String session, String missingReason) {
    }

    private static Instant tossCalendarBound(JsonNode interval, String... keys) {
        Instant bound = null;
        for (var key : keys) {
            var value = interval.get(key);
            if (value == null || value.isNull()) continue;
            try {
                var parsed = OffsetDateTime.parse(value.asText()).toInstant();
                if (bound != null && !bound.equals(parsed)) return null;
                bound = parsed;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return bound;
    }

    private StockAnalysisInput.Observation tossMissing(String field, String reason, Instant collectedAt) {
        return new StockAnalysisInput.Observation(
                field, null, null, null, null, StockDataProviderId.TOSS,
                null, collectedAt, List.of(reason));
    }

    private static StockAnalysisInput withObservations(
            StockAnalysisInput input, List<StockAnalysisInput.Observation> observations
    ) {
        return new StockAnalysisInput(input.snapshotId(), input.symbol(), input.schemaVersion(),
                input.collectedAt(), observations);
    }

    static String providerFailure(StockAnalysisInput input) {
        var reasons = input.observations().stream()
                .flatMap(observation -> observation.missingData().stream()
                        .map(reason -> normalizedProviderFailureReason(observation.provider(), reason)))
                .filter(Objects::nonNull)
                .toList();
        return reasons.stream()
                .filter(reason -> reason.equals("PROVIDER_DAILY_QUOTA_EXHAUSTED")
                        || reason.equals("PROVIDER_REQUEST_IN_PROGRESS")
                        || reason.equals("PROVIDER_CACHE_UNAVAILABLE")
                        || reason.equals("PROVIDER_CACHE_CORRUPT"))
                .findFirst()
                .orElseGet(() -> reasons.stream()
                .filter(reason -> reason.matches("PROVIDER_HTTP_[1-5][0-9]{2}"))
                .findFirst()
                .orElseGet(() -> reasons.stream().findFirst().orElse(null)));
    }

    private static boolean hasTossLatestPrice(StockAnalysisInput input) {
        return input.observations().stream().anyMatch(observation ->
                "price.latestPrice".equals(observation.field())
                        && observation.provider() == StockDataProviderId.TOSS
                        && "USD".equalsIgnoreCase(observation.unit())
                        && observation.asOf() != null
                        && !observation.asOf().isAfter(input.collectedAt())
                        && observation.missingData().isEmpty()
                        && decimal(observation.value()) != null
                        && decimal(observation.value()).signum() > 0);
    }

    private boolean canonicalDataReady(UUID userId, String ticker, Instant capturedAt) {
        var hasClose = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM investment_price_snapshots
                     WHERE user_id = ? AND ticker = ? AND source = 'TOSS'
                       AND session = 'REGULAR_CLOSE' AND regular_close > 0
                       AND regular_close_as_of <= ? AND regular_close_as_of >= ?
                )
                """, Boolean.class, userId, ticker, timestamp(capturedAt),
                timestamp(capturedAt.minus(regularCloseStaleAfter)));
        if (!Boolean.TRUE.equals(hasClose)) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM fundamental_snapshots
                     WHERE user_id = ? AND ticker = ? AND source = 'SEC'
                       AND fiscal_period IS NOT NULL AND reported_at IS NOT NULL
                       AND field_provenance #>> '{cash,asOf}' IS NOT NULL
                       AND field_provenance #>> '{revenueTTM,asOf}' IS NOT NULL
                       AND (field_provenance #>> '{cash,asOf}')::timestamptz <= ?
                       AND (field_provenance #>> '{cash,asOf}')::timestamptz >= ?
                       AND (field_provenance #>> '{revenueTTM,asOf}')::timestamptz <= ?
                       AND (field_provenance #>> '{revenueTTM,asOf}')::timestamptz >= ?
                       AND cash IS NOT NULL AND revenue_ttm IS NOT NULL
                )
                """, Boolean.class, userId, ticker, timestamp(capturedAt),
                timestamp(capturedAt.minus(fundamentalStaleAfter)), timestamp(capturedAt),
                timestamp(capturedAt.minus(fundamentalStaleAfter))));
    }

    public ThesisView putThesis(UUID userId, String rawTicker, ThesisInput input) {
        requireUser(userId);
        var ticker = ticker(rawTicker);
        validateThesis(input);
        var now = timestamp(clock.instant());
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, upside_driver, expectations_gap,
                    fundamental_invalidation, revision_invalidation, price_risk_trigger,
                    price_risk_trigger_price, invalidation_status, expand_trigger,
                    exit_or_discard_trigger, classification, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, ticker) DO UPDATE SET
                    core_thesis = EXCLUDED.core_thesis,
                    upside_driver = EXCLUDED.upside_driver,
                    expectations_gap = EXCLUDED.expectations_gap,
                    fundamental_invalidation = EXCLUDED.fundamental_invalidation,
                    revision_invalidation = EXCLUDED.revision_invalidation,
                    price_risk_trigger = EXCLUDED.price_risk_trigger,
                    price_risk_trigger_price = EXCLUDED.price_risk_trigger_price,
                    invalidation_status = EXCLUDED.invalidation_status,
                    expand_trigger = EXCLUDED.expand_trigger,
                    exit_or_discard_trigger = EXCLUDED.exit_or_discard_trigger,
                    classification = EXCLUDED.classification,
                    updated_at = EXCLUDED.updated_at
                """, userId, ticker, input.coreThesis().trim(), clean(input.upsideDriver()),
                clean(input.expectationsGap()), clean(input.fundamentalInvalidation()),
                clean(input.revisionInvalidation()), clean(input.priceRiskTrigger()),
                input.priceRiskTriggerPrice(), input.invalidationStatus().trim().toUpperCase(Locale.ROOT),
                clean(input.expandTrigger()), clean(input.exitOrDiscardTrigger()), clean(input.classification()), now);
        return thesis(userId, ticker);
    }

    public List<DecisionView> decisionLedger(UUID userId, int limit) {
        requireUser(userId);
        if (limit < 1 || limit > 200) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        return jdbc.query("""
                SELECT decision_id, as_of, asset, action, reference_price, price_session,
                       horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                       risk_policy_check::text, created_at
                  FROM investment_decision_ledger
                 WHERE user_id = ?
                 ORDER BY as_of DESC, decision_id DESC
                 LIMIT ?
                """, decisionRow(), userId, limit);
    }

    public DecisionView recordDecision(UUID userId, DecisionInput input) {
        requireUser(userId);
        var decision = normalizeDecision(input);
        var context = context(userId);
        var security = context.securities().stream().filter(item -> item.ticker().equals(decision.asset()))
                .findFirst().orElse(null);
        var riskCheck = riskCheck(context.riskPolicy(), security == null ? null : security.risk());
        var now = timestamp(clock.instant());
        var inserted = jdbc.update("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session,
                    horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                    risk_policy_check, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (decision_id) DO NOTHING
                """, decision.decisionId(), userId, timestamp(decision.asOf()), decision.asset(), decision.action(),
                decision.referencePrice(), decision.priceSession(), decision.horizon().trim(), decision.alphaThesis().trim(),
                decision.invalidation().trim(), decision.nextReviewTrigger().trim(), decision.confidence(),
                encode(riskCheck), now);
        if (inserted == 0) {
            var existing = jdbc.query("""
                    SELECT decision_id, as_of, asset, action, reference_price, price_session,
                           horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                           risk_policy_check::text, created_at
                      FROM investment_decision_ledger
                     WHERE decision_id = ? AND user_id = ?
                    """, decisionRow(), decision.decisionId(), userId).stream().findFirst().orElse(null);
            if (existing == null || !sameDecision(existing, decision)) {
                throw new InvestmentException(InvestmentException.Code.CONFLICT);
            }
            return existing;
        }
        return jdbc.query("""
                SELECT decision_id, as_of, asset, action, reference_price, price_session,
                       horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                       risk_policy_check::text, created_at
                  FROM investment_decision_ledger
                 WHERE decision_id = ? AND user_id = ?
                """, decisionRow(), decision.decisionId(), userId).getFirst();
    }

    private boolean persistCapture(UUID userId, StockAnalysisInput input) {
        return persistCapture(userId, input, true);
    }

    private boolean persistCapture(UUID userId, StockAnalysisInput input, boolean persistFinancialSnapshots) {
        var now = timestamp(clock.instant());
        var canonical = hasher.canonicalJson(input);
        jdbc.update("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash, collected_at, created_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """, input.snapshotId(), userId, input.symbol(), input.schemaVersion(), canonical,
                hasher.hashCanonical(canonical), timestamp(input.collectedAt()), now);

        var groups = groups(input);
        if (persistFinancialSnapshots && tacticalOverlayService != null) {
            var tossBars = observation(groups.get(StockDataProviderId.TOSS), "price.regularCloseHistory");
            if (usableTacticalBarObservation(tossBars)) {
                tacticalOverlayService.recordTossBars(
                        userId, input.symbol(), tossBars.value(), input.collectedAt(), input.snapshotId());
            }
        }
        var priceQuotes = sourceQuotes(userId, input.symbol(), groups, input.collectedAt());
        var price = InvestmentDataCalculator.assessPrices(
                priceQuotes, input.collectedAt(), priceStaleAfter, regularCloseStaleAfter);
        groups.forEach((provider, values) ->
                persistRegularCloseHistory(userId, input, provider, values, now));
        for (var quote : priceQuotes) {
            persistPrice(userId, input, quote, now);
        }
        var fundamentalConflict = false;
        if (persistFinancialSnapshots) {
            var secValues = groups.get(StockDataProviderId.SEC);
            if (secValues != null) {
                var canonicalSecValues = new LinkedHashMap<>(secValues);
                canonicalSecValues = mergePreviousSecFundamentalObservations(
                        userId, input.symbol(), input.collectedAt(), canonicalSecValues);
                var secDilutedShares = observation(canonicalSecValues, "fundamental.dilutedShares");
                var alphaValues = groups.get(StockDataProviderId.ALPHA_VANTAGE);
                var alphaDilutedShares = observation(alphaValues, "fundamental.dilutedShares");
                if (!validShares(decimal(secDilutedShares == null ? null : secDilutedShares.value()))
                        && validShares(decimal(alphaDilutedShares == null ? null : alphaDilutedShares.value()))) {
                    canonicalSecValues.put("fundamental.dilutedShares", alphaDilutedShares);
                }
                fundamentalConflict |= persistFundamentals(userId, input, canonicalSecValues, now);
            }
            for (var values : groups.values()) {
                persistConsensus(userId, input, values, now);
            }
        }

        var fundamental = latestFundamental(userId, input.symbol());
        if (fundamentalConflict) {
            fundamental = fundamental.withStatus(InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT);
        }
        var consensus = latestConsensus(userId, input.symbol());
        var securityThesis = thesisIfPresent(userId, input.symbol());
        var technical = technical(userId, input.symbol(), price.latestPrice());
        var revision = revisions(userId, input.symbol(), consensus);
        var valuation = valuation(userId, input.symbol(), price, fundamental, consensus, securityThesis);
        var readiness = readiness(price, technical, fundamental, consensus, revision, valuation, input);
        var asOf = latestAsOf(price, technical, fundamental, consensus, input.collectedAt());
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("asOf", asOf);
        snapshot.put("price", price);
        snapshot.put("technical", technical.view());
        snapshot.put("fundamentals", fundamental.view());
        snapshot.put("consensus", consensus.view());
        snapshot.put("revision", revision.view());
        snapshot.put("valuation", valuation.view());
        snapshot.put("readiness", readiness.view());
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (user_id, ticker, as_of) DO NOTHING
                """, UUID.randomUUID(), userId, input.symbol(), timestamp(asOf), encode(snapshot), now);
        return !readiness.missingFields().isEmpty();
    }

    private void persistPrice(UUID userId, StockAnalysisInput input,
                              InvestmentDataCalculator.SourceQuote quote, OffsetDateTime now) {
        var session = normalizeSession(quote.session());
        var asOf = quote.latestPriceAsOf() == null ? quote.regularCloseAsOf() : quote.latestPriceAsOf();
        if (session == null || quote.source() == null || asOf == null
                || (quote.latestPrice() == null && quote.regularClose() == null)) {
            return;
        }
        jdbc.update("""
                INSERT INTO investment_price_snapshots (
                    id, user_id, input_snapshot_id, ticker, as_of, session,
                    latest_price, latest_price_as_of, regular_close, regular_close_as_of, source, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, ticker, session, source, as_of) DO NOTHING
                """, UUID.randomUUID(), userId, input.snapshotId(), input.symbol(), timestamp(asOf), session,
                quote.latestPrice(), timestampOrNull(quote.latestPriceAsOf()), quote.regularClose(),
                timestampOrNull(quote.regularCloseAsOf()), quote.source(), now);
    }

    private boolean persistFundamentals(UUID userId, StockAnalysisInput input,
                                        Map<String, StockAnalysisInput.Observation> values, OffsetDateTime now) {
        var sourceAnchor = java.util.stream.Stream.of(
                        observation(values, "fundamental.fiscalPeriod"),
                        observation(values, "fundamental.cash"),
                        observation(values, "fundamental.revenueTTM"))
                .filter(Objects::nonNull).findFirst().orElse(null);
        var source = sourceAnchor == null ? null : sourceAnchor.provider();
        if (source != StockDataProviderId.SEC) return false;
        var fiscalPeriod = text(value(values, "fundamental.fiscalPeriod"));
        var reportedAt = instant(value(values, "fundamental.reportedAt"));
        var fiscalYear = text(value(values, "fundamental.fiscalYear"));
        var fiscalPeriodCode = text(value(values, "fundamental.fiscalPeriodCode"));
        var cash = decimal(value(values, "fundamental.cash"));
        var debt = decimal(value(values, "fundamental.debt"));
        var revenue = decimal(value(values, "fundamental.revenueTTM"));
        var revenueGrowth = decimal(value(values, "fundamental.revenueGrowthYoY"));
        var basicSharesObservation = observation(values, "fundamental.basicShares");
        var basicShares = decimal(value(values, "fundamental.basicShares"));
        if (!validShares(basicShares)) {
            basicShares = null;
            basicSharesObservation = null;
        }
        var basicSharesBasis = basicSharesObservation == null ? null
                : text(value(values, "fundamental.basicSharesBasis"));
        var dilutedSharesObservation = observation(values, "fundamental.dilutedShares");
        var dilutedShares = decimal(value(values, "fundamental.dilutedShares"));
        if (!validShares(dilutedShares)) {
            dilutedShares = null;
            dilutedSharesObservation = null;
        }
        var dilutedSharesBasis = dilutedSharesObservation == null ? null
                : text(value(values, "fundamental.dilutedSharesBasis"));
        if (dilutedSharesObservation != null && dilutedSharesBasis == null
                && dilutedSharesObservation.provider() == StockDataProviderId.ALPHA_VANTAGE) {
            dilutedSharesBasis = "ALPHA_VANTAGE_" + cleanIdentifier(dilutedSharesObservation.identifier());
        }
        var priceClose = latestTossRegularClose(userId, input.symbol(), input.collectedAt());
        var marketCap = priceClose != null && basicShares != null
                ? priceClose.close().multiply(basicShares) : null;
        var marketCapAsOf = marketCap == null ? null : priceClose.asOf();
        var marketCapFormula = marketCap == null ? null : "TOSS_REGULAR_CLOSE * SEC_BASIC_SHARES";
        var fullyDilutedMarketCap = priceClose != null && dilutedShares != null
                ? priceClose.close().multiply(dilutedShares) : null;
        var fullyDilutedMarketCapAsOf = fullyDilutedMarketCap == null ? null : priceClose.asOf();
        var fullyDilutedFormula = fullyDilutedMarketCap == null ? null
                : "TOSS_REGULAR_CLOSE * " + dilutedSharesObservation.provider().name() + "_DILUTED_SHARES";
        var cashObservation = observation(values, "fundamental.cash");
        var debtObservation = observation(values, "fundamental.debt");
        var balanceSheetAsOf = latestObservationAsOf(cashObservation, debtObservation);
        var balanceSheetCurrencyVerified = cashObservation != null && debtObservation != null
                && "USD".equalsIgnoreCase(cashObservation.unit())
                && "USD".equalsIgnoreCase(debtObservation.unit());
        var enterpriseValue = marketCap != null && cash != null && debt != null && balanceSheetCurrencyVerified
                ? marketCap.add(debt).subtract(cash) : null;
        var enterpriseValueAsOf = enterpriseValue == null ? null
                : latestInstant(marketCapAsOf, balanceSheetAsOf);
        var enterpriseValueSource = enterpriseValue == null ? null : "MARKET_CAP_PLUS_LATEST_DEBT_MINUS_LATEST_CASH";
        var ebitda = decimal(value(values, "fundamental.ebitdaTTM"));
        var ebitdaType = text(value(values, "fundamental.ebitdaTTMType"));
        var ebitdaFormula = text(value(values, "fundamental.ebitdaTTMFormula"));
        var ebitdaSource = text(value(values, "fundamental.ebitdaTTMSource"));
        var currency = text(value(values, "fundamental.currency"));
        var financialAsOf = latestObservationAsOf(
                observation(values, "fundamental.reportedAt"),
                observation(values, "fundamental.fiscalPeriod"));
        var hasFinancialValue = cash != null || debt != null || revenue != null || ebitda != null
                || decimal(value(values, "fundamental.eps")) != null
                || decimal(value(values, "fundamental.fcfTTM")) != null;
        if (!hasFinancialValue || fiscalPeriod == null || reportedAt == null || financialAsOf == null) return false;
        var provenance = fundamentalProvenance(values);
        if (marketCap != null) provenance.put("marketCap", map(
                "source", "TOSS+SEC", "asOf", marketCapAsOf, "unit", "USD",
                "formula", marketCapFormula, "priceSource", "TOSS", "sharesSource", "SEC",
                "sharesAsOf", basicSharesObservation.asOf(),
                "sharesIdentifier", basicSharesObservation.identifier()));
        if (fullyDilutedMarketCap != null) provenance.put("fullyDilutedMarketCap", map(
                "source", "TOSS+" + dilutedSharesObservation.provider().name(),
                "asOf", fullyDilutedMarketCapAsOf, "unit", "USD",
                "formula", fullyDilutedFormula,
                "sharesAsOf", dilutedSharesObservation.asOf(),
                "sharesIdentifier", dilutedSharesObservation.identifier()));
        if (enterpriseValue != null) provenance.put("enterpriseValue", map(
                "source", enterpriseValueSource, "asOf", enterpriseValueAsOf,
                "unit", "USD",
                "marketCapAsOf", marketCapAsOf, "balanceSheetAsOf", balanceSheetAsOf,
                "formula", "marketCap + latestDebt - latestCash"));

        jdbc.update("""
                INSERT INTO fundamental_snapshots (
                    id, user_id, input_snapshot_id, ticker, fiscal_period, fiscal_year, fiscal_period_code,
                    reported_at, as_of, source, market_cap, market_cap_as_of, enterprise_value,
                    enterprise_value_as_of, enterprise_value_source, cash, debt, diluted_shares,
                    diluted_shares_basis, revenue_ttm, revenue_growth_yoy, ebitda_ttm, eps, fcf_ttm,
                    basic_shares, basic_shares_basis, market_cap_formula,
                    fully_diluted_market_cap, fully_diluted_market_cap_as_of,
                    fully_diluted_market_cap_formula, balance_sheet_as_of, currency,
                    ebitda_ttm_type, ebitda_ttm_formula, ebitda_ttm_source, field_provenance, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (user_id, ticker, input_snapshot_id, source) DO NOTHING
                """, UUID.randomUUID(), userId, input.snapshotId(), input.symbol(), fiscalPeriod,
                fiscalYear, fiscalPeriodCode, timestamp(reportedAt), timestamp(financialAsOf), source.name(),
                marketCap, timestampOrNull(marketCapAsOf), enterpriseValue, timestampOrNull(enterpriseValueAsOf),
                enterpriseValueSource, cash, debt, dilutedShares, dilutedSharesBasis, revenue, revenueGrowth,
                ebitda, decimal(value(values, "fundamental.eps")), decimal(value(values, "fundamental.fcfTTM")),
                basicShares, basicSharesBasis, marketCapFormula,
                fullyDilutedMarketCap, timestampOrNull(fullyDilutedMarketCapAsOf), fullyDilutedFormula,
                timestampOrNull(balanceSheetAsOf), currency, ebitdaType, ebitdaFormula, ebitdaSource,
                encode(provenance), now);
        return false;
    }

    private LinkedHashMap<String, StockAnalysisInput.Observation> mergePreviousSecFundamentalObservations(
            UUID userId, String ticker, Instant collectedAt,
            Map<String, StockAnalysisInput.Observation> current
    ) {
        var merged = new LinkedHashMap<>(current);
        var previous = jdbc.query("""
                SELECT DISTINCT ON (observation->>'field') observation::text
                  FROM analysis_input_snapshots snapshot
                  CROSS JOIN LATERAL jsonb_array_elements(snapshot.payload->'observations') AS item(observation)
                 WHERE snapshot.user_id = ? AND snapshot.symbol = ? AND snapshot.collected_at <= ?
                   AND observation->>'provider' = 'SEC'
                   AND observation->>'field' LIKE 'fundamental.%'
                   AND observation->'value' IS NOT NULL
                   AND observation->'value' <> 'null'::jsonb
                   AND COALESCE(observation->'missingData', '[]'::jsonb) = '[]'::jsonb
                   AND observation->>'asOf' IS NOT NULL
                   AND (observation->>'asOf')::timestamptz <= ?
                 ORDER BY observation->>'field', (observation->>'asOf')::timestamptz DESC,
                          snapshot.collected_at DESC, snapshot.id DESC
                """, (resultSet, rowNum) -> resultSet.getString(1), userId, ticker,
                timestamp(collectedAt), timestamp(collectedAt));
        for (var encoded : previous) {
            try {
                var node = objectMapper.readTree(encoded);
                var observation = storedObservation(node);
                if (observation != null) merged.putIfAbsent(observation.field(), observation);
            } catch (RuntimeException ignored) {
                // A malformed legacy observation is ignored; valid current SEC fields remain usable.
            }
        }
        return merged;
    }

    private StockAnalysisInput.Observation storedObservation(JsonNode node) {
        if (node == null || !node.isObject() || node.path("value").isNull()) return null;
        var field = text(node.get("field"));
        var provider = text(node.get("provider"));
        var asOf = instant(node.get("asOf"));
        var collectedAt = instant(node.get("collectedAt"));
        if (field == null || !field.startsWith("fundamental.") || !"SEC".equals(provider)
                || asOf == null || collectedAt == null) return null;
        var missingData = new ArrayList<String>();
        var missingNode = node.get("missingData");
        if (missingNode != null && missingNode.isArray()) {
            missingNode.forEach(reason -> {
                if (reason != null && reason.isTextual()) missingData.add(reason.asText());
            });
        }
        if (!missingData.isEmpty()) return null;
        StockAnalysisInput.AsOfBasis asOfBasis;
        try {
            asOfBasis = StockAnalysisInput.AsOfBasis.valueOf(text(node.get("asOfBasis")));
        } catch (RuntimeException ignored) {
            asOfBasis = StockAnalysisInput.AsOfBasis.SOURCE_AS_OF;
        }
        return new StockAnalysisInput.Observation(field, node.get("value"),
                text(node.get("unit")), text(node.get("period")), text(node.get("identifier")),
                StockDataProviderId.SEC, asOf, collectedAt, List.of(), asOfBasis);
    }

    private BigDecimal revenueGrowth(UUID userId, String ticker, String source, String fiscalPeriod,
                                     String fiscalYear, String fiscalPeriodCode, JsonNode incomeHistory,
                                     BigDecimal revenue) {
        if (revenue == null || fiscalPeriod == null) return null;
        var currentDate = localDate(fiscalPeriod);
        if (currentDate == null) return null;
        var priorRevenue = historyRevenue(incomeHistory, currentDate, fiscalYear, fiscalPeriodCode);
        if (fiscalYear != null || fiscalPeriodCode != null) {
            if (fiscalYear == null || fiscalPeriodCode == null) return null;
            try {
                var priorYear = Integer.toString(Integer.parseInt(fiscalYear) - 1);
                if (priorRevenue == null) {
                    priorRevenue = jdbc.query("""
                        SELECT revenue_ttm FROM fundamental_snapshots
                         WHERE user_id = ? AND ticker = ? AND source = ? AND fiscal_year = ?
                           AND fiscal_period_code = ? AND revenue_ttm IS NOT NULL
                         ORDER BY fiscal_period DESC, reported_at DESC, market_cap_as_of DESC NULLS LAST,
                                  created_at DESC, id DESC LIMIT 1
                        """, (resultSet, rowNum) -> resultSet.getBigDecimal(1),
                            userId, ticker, source, priorYear, fiscalPeriodCode).stream().findFirst().orElse(null);
                }
            } catch (NumberFormatException ignored) {
                if (priorRevenue == null) return null;
            }
        } else {
            if (priorRevenue == null) {
                var priorPeriod = currentDate.minusYears(1).toString();
                priorRevenue = jdbc.query("""
                        SELECT revenue_ttm FROM fundamental_snapshots
                         WHERE user_id = ? AND ticker = ? AND source = ? AND fiscal_period = ?
                           AND revenue_ttm IS NOT NULL
                         ORDER BY reported_at DESC, market_cap_as_of DESC NULLS LAST, created_at DESC, id DESC
                         LIMIT 1
                        """, (resultSet, rowNum) -> resultSet.getBigDecimal(1),
                        userId, ticker, source, priorPeriod).stream().findFirst().orElse(null);
            }
        }
        if (priorRevenue == null || priorRevenue.signum() <= 0) return null;
        return revenue.subtract(priorRevenue).divide(priorRevenue, MathContext.DECIMAL128);
    }

    private PriceBar latestTossRegularClose(UUID userId, String ticker, Instant collectedAt) {
        if (collectedAt == null) return null;
        return jdbc.query("""
                SELECT regular_close, regular_close_as_of
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = ? AND source = 'TOSS'
                   AND session = 'REGULAR_CLOSE' AND regular_close > 0
                   AND regular_close_as_of <= ? AND regular_close_as_of >= ?
                 ORDER BY regular_close_as_of DESC, observed_at DESC
                 LIMIT 1
                """, resultSet -> resultSet.next()
                ? new PriceBar(resultSet.getBigDecimal("regular_close"),
                        resultSet.getTimestamp("regular_close_as_of").toInstant())
                : null, userId, ticker, timestamp(collectedAt),
                timestamp(collectedAt.minus(regularCloseStaleAfter)));
    }

    private static boolean validShares(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static Instant latestObservationAsOf(StockAnalysisInput.Observation... observations) {
        if (observations == null) return null;
        return java.util.Arrays.stream(observations).filter(Objects::nonNull)
                .map(StockAnalysisInput.Observation::asOf).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static LinkedHashMap<String, Object> fundamentalProvenance(
            Map<String, StockAnalysisInput.Observation> values) {
        var result = new LinkedHashMap<String, Object>();
        values.entrySet().stream().filter(entry -> entry.getKey().startsWith("fundamental."))
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    var observation = entry.getValue();
                    result.put(entry.getKey().substring("fundamental.".length()), map(
                            "source", observation.provider().name(),
                            "asOf", observation.asOf(),
                            "collectedAt", observation.collectedAt(),
                            "asOfBasis", observation.asOfBasis().name(),
                            "period", observation.period(),
                            "unit", observation.unit(),
                            "identifier", observation.identifier()));
                });
        return result;
    }

    private static String cleanIdentifier(String value) {
        if (value == null || value.isBlank()) return "DILUTED_SHARES";
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private BigDecimal historyRevenue(JsonNode incomeHistory, LocalDate currentDate,
                                      String fiscalYear, String fiscalPeriodCode) {
        if (incomeHistory == null || !incomeHistory.isArray()) return null;
        var useFiscalMetadata = fiscalYear != null || fiscalPeriodCode != null;
        String priorYear = null;
        if (useFiscalMetadata) {
            if (fiscalYear == null || fiscalPeriodCode == null) return null;
            try {
                priorYear = Integer.toString(Integer.parseInt(fiscalYear) - 1);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        var exactPriorDate = currentDate.minusYears(1);
        LocalDate selectedDate = null;
        BigDecimal selectedRevenue = null;
        for (var row : incomeHistory) {
            var rowDate = localDate(text(row.get("date")));
            if (rowDate == null || rowDate.isAfter(currentDate)) continue;
            var rowYear = text(row.get("fiscalYear"));
            var rowPeriod = text(row.get("period"));
            var matches = useFiscalMetadata
                    ? rowYear != null && rowPeriod != null
                        && priorYear.equals(rowYear) && fiscalPeriodCode.equals(rowPeriod)
                    : exactPriorDate.equals(rowDate);
            var rowRevenue = decimal(row.get("revenue"));
            if (!matches || rowRevenue == null) continue;
            if (selectedDate == null || rowDate.isAfter(selectedDate)) {
                selectedDate = rowDate;
                selectedRevenue = rowRevenue;
            }
        }
        return selectedRevenue;
    }

    private void persistConsensus(UUID userId, StockAnalysisInput input,
                                  Map<String, StockAnalysisInput.Observation> values, OffsetDateTime now) {
        var observations = value(values, "consensus.observations");
        if (observations != null && observations.isArray()) {
            var arrayObservation = observation(values, "consensus.observations");
            if (arrayObservation == null || arrayObservation.provider() != StockDataProviderId.ALPHA_VANTAGE) return;
            for (var row : observations) {
                if (!row.isObject()) continue;
                var type = text(row.get("estimateType"));
                if (!Set.of("ANNUAL", "QUARTERLY").contains(type)) continue;
                var periodEnd = localDate(text(row.get("periodEnd")));
                var rawHorizon = text(row.get("horizon"));
                if (periodEnd == null || rawHorizon == null
                        || !rawHorizon.equals(type + ":" + periodEnd)) continue;
                var observedAt = instant(row.get("observedAt"));
                if (observedAt == null || observedAt.isAfter(input.collectedAt())) continue;
                var eps = decimal(row.get("epsConsensus"));
                var revenue = decimal(row.get("revenueConsensus"));
                if (eps == null && revenue == null) continue;
                var label = text(row.get("sourceEstimateType"));
                var currency = text(row.get("currency"));
                jdbc.update("""
                        INSERT INTO consensus_snapshots (
                            id, user_id, input_snapshot_id, ticker, as_of, horizon, revenue_consensus,
                            eps_consensus, ebitda_consensus, fcf_consensus, source, estimate_type,
                            estimate_label, period_end, eps_analyst_count, revenue_analyst_count,
                            currency, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 'ALPHA_VANTAGE', ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, ticker, as_of, horizon, estimate_type, source) DO NOTHING
                        """, UUID.randomUUID(), userId, input.snapshotId(), input.symbol(), timestamp(observedAt),
                        periodEnd.toString(), revenue, eps, type, label, periodEnd,
                        nonNegativeInteger(row.get("epsAnalystCount")),
                        nonNegativeInteger(row.get("revenueAnalystCount")), currency, now);
            }
            return;
        }
        var horizon = text(value(values, "consensus.horizon"));
        if (horizon == null) {
            horizon = CONSENSUS_FIELDS.stream().map(field -> observation(values, "consensus." + field))
                    .filter(Objects::nonNull).map(StockAnalysisInput.Observation::period)
                    .filter(period -> period != null && !period.isBlank()).findFirst().orElse(null);
        }
        var asOf = latestAsOf(values, "consensus.");
        var source = CONSENSUS_FIELDS.stream().map(field -> observation(values, "consensus." + field))
                .filter(Objects::nonNull).map(StockAnalysisInput.Observation::provider).findFirst().orElse(null);
        var count = CONSENSUS_FIELDS.stream().filter(field -> decimal(value(values, "consensus." + field)) != null).count();
        if (count == 0 || horizon == null || asOf == null || source == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO consensus_snapshots (
                    id, user_id, input_snapshot_id, ticker, as_of, horizon, revenue_consensus,
                    eps_consensus, ebitda_consensus, fcf_consensus, source, estimate_type, period_end, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ANNUAL', ?, ?)
                ON CONFLICT (user_id, ticker, as_of, horizon, estimate_type, source) DO NOTHING
                """, UUID.randomUUID(), userId, input.snapshotId(), input.symbol(), timestamp(asOf), horizon,
                decimal(value(values, "consensus.revenueConsensus")),
                decimal(value(values, "consensus.epsConsensus")),
                decimal(value(values, "consensus.ebitdaConsensus")),
                decimal(value(values, "consensus.fcfConsensus")), source.name(), localDate(horizon), now);
    }

    private PortfolioView readPortfolio(UUID userId) {
        if (configuredSheetOwner(userId)) return readSheetPortfolio(userId);
        var totals = new LinkedHashMap<String, BigDecimal>();
        var combined = new LinkedHashMap<String, PositionView>();
        var missing = new ArrayList<String>();
        Instant asOf = null;
        boolean stale = false;
        var connectionIds = jdbc.query("""
                SELECT id FROM broker_connections
                 WHERE user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                 ORDER BY id
                """, (resultSet, rowNum) -> resultSet.getObject(1, UUID.class), userId);
        for (var connectionId : connectionIds) {
            final PortfolioReadService.PortfolioView snapshot;
            try {
                snapshot = portfolios.read(userId, connectionId);
            } catch (RuntimeException exception) {
                missing.add("PORTFOLIO:" + connectionId);
                continue;
            }
            asOf = asOf == null || snapshot.completedAt().isAfter(asOf) ? snapshot.completedAt() : asOf;
            stale |= snapshot.stale();
            snapshot.missingSections().forEach(section -> missing.add("PORTFOLIO:" + section));
            if (snapshot.account() != null && snapshot.account().marketValueAmounts() != null) {
                snapshot.account().marketValueAmounts().forEach((currency, amount) -> {
                    if (amount != null) totals.merge(currency, amount, BigDecimal::add);
                });
            }
            for (var position : snapshot.positions()) {
                var ticker = ticker(position.symbol());
                var prior = combined.get(ticker);
                if (prior == null) {
                    combined.put(ticker, new PositionView(
                            ticker, position.name(), position.quantity(), position.currency(),
                            position.marketValueAmount(), null, position.lastPrice(), position.observedAt()));
                } else if (Objects.equals(prior.currency(), position.currency())) {
                    var qty = add(prior.quantity(), position.quantity());
                    var value = add(prior.marketValue(), position.marketValueAmount());
                    var last = position.observedAt().isAfter(prior.asOf()) ? position.lastPrice() : prior.lastPrice();
                    var observed = position.observedAt().isAfter(prior.asOf()) ? position.observedAt() : prior.asOf();
                    combined.put(ticker, new PositionView(
                            ticker, prior.name(), qty, prior.currency(), value, null, last, observed));
                } else {
                    missing.add("MIXED_CURRENCY_POSITION:" + ticker);
                }
            }
        }
        var positions = combined.values().stream().sorted(Comparator.comparing(PositionView::ticker)).toList();
        var positionCurrencies = positions.stream().map(PositionView::currency).distinct().toList();
        var commonCurrency = positionCurrencies.size() == 1 ? positionCurrencies.getFirst() : null;
        var accountTotal = commonCurrency == null ? null : totals.get(commonCurrency);
        var otherCurrencyTotalIsNonZero = commonCurrency == null || totals.entrySet().stream()
                .anyMatch(entry -> !entry.getKey().equals(commonCurrency) && entry.getValue().signum() != 0);
        var usableAccountTotal = accountTotal != null && accountTotal.signum() > 0
                && !otherCurrencyTotalIsNonZero;
        var weighted = positions.stream().map(position -> {
            var weight = !usableAccountTotal || !Objects.equals(position.currency(), commonCurrency)
                    || position.marketValue() == null || position.marketValue().signum() < 0
                    ? null : position.marketValue().divide(accountTotal, MathContext.DECIMAL128);
            return new PositionView(position.ticker(), position.name(), position.quantity(),
                    position.currency(), position.marketValue(), weight, position.lastPrice(), position.asOf());
        }).toList();
        var status = missing.size() > 0 ? "PARTIAL" : stale ? "STALE" : asOf == null ? "DATA_MISSING" : "OK";
        return new PortfolioView(asOf, weighted, Map.copyOf(totals), stale, List.copyOf(missing), status);
    }

    private List<String> latestHeldSymbols(UUID userId) {
        if (configuredSheetOwner(userId)) {
            var payload = latestAcceptedSheetPayload(userId);
            var state = sheetTable(payload == null ? null : payload.get("accountState"));
            return InvestmentOsSheetModel.heldSymbols(state);
        }
        return jdbc.query("""
                SELECT DISTINCT upper(position.symbol)
                  FROM broker_connections connection
                  JOIN LATERAL (
                      SELECT run.id
                        FROM account_sync_runs run
                       WHERE run.user_id = connection.user_id
                         AND run.broker_connection_id = connection.id
                         AND run.credential_revision = connection.credential_revision
                         AND run.status = 'SUCCEEDED'
                       ORDER BY run.completed_at DESC, run.id DESC
                       LIMIT 1
                  ) latest ON true
                  JOIN position_snapshots position ON position.sync_run_id = latest.id
                 WHERE connection.user_id = ? AND connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                   AND position.quantity > 0
                 ORDER BY upper(position.symbol)
                """, (resultSet, rowNum) -> resultSet.getString(1), userId);
    }

    private Map<StockDataProviderId, Map<String, StockAnalysisInput.Observation>> groups(StockAnalysisInput input) {
        var result = new LinkedHashMap<com.jmj.trade.marketdata.StockDataProviderId,
                Map<String, StockAnalysisInput.Observation>>();
        for (var observation : input.observations()) {
            if (observation == null || observation.value() == null) continue;
            var onlyOuterAsOfMissing = observation.missingData().stream()
                    .allMatch("AS_OF_UNAVAILABLE"::equals);
            var datedHistory = "price.regularCloseHistory".equals(observation.field())
                    && observation.provider() == StockDataProviderId.TOSS
                    && observation.value().isArray()
                    && observation.missingData().contains("AS_OF_UNAVAILABLE")
                    && observation.missingData().stream().allMatch(reason -> "AS_OF_UNAVAILABLE".equals(reason)
                            || "TOSS_CANDLE_DUPLICATE_CONFLICT".equals(reason));
            var explicitSessionMetadata = "price.session".equals(observation.field())
                    && normalizeSession(text(observation.value())) != null && onlyOuterAsOfMissing;
            if (!observation.missingData().isEmpty() && !datedHistory && !explicitSessionMetadata) continue;
            if ((observation.asOf() == null || observation.asOf().isAfter(input.collectedAt()))
                    && !datedHistory && !explicitSessionMetadata) continue;
            result.computeIfAbsent(observation.provider(), ignored -> new LinkedHashMap<>())
                    .put(observation.field(), observation);
        }
        return result;
    }

    private List<InvestmentDataCalculator.SourceQuote> sourceQuotes(
            UUID userId,
            String ticker,
            Map<com.jmj.trade.marketdata.StockDataProviderId, Map<String, StockAnalysisInput.Observation>> groups,
            Instant collectedAt
    ) {
        var quotes = new ArrayList<InvestmentDataCalculator.SourceQuote>();
        groups.forEach((provider, values) -> {
            var latest = observation(values, "price.latestPrice");
            var close = provider == StockDataProviderId.TOSS
                    ? observation(values, "price.regularClose") : null;
            var session = text(value(values, "price.session"));
            var latestHistory = (provider == StockDataProviderId.TOSS
                    ? regularCloseBars(value(values, "price.regularCloseHistory"), session, provider, collectedAt)
                    : List.<PriceBar>of())
                    .stream().max(Comparator.comparing(PriceBar::asOf)).orElse(null);
            var closePrice = decimal(close == null ? null : close.value());
            var closeAsOf = close == null ? null : close.asOf();
            if (latestHistory != null && (closeAsOf == null || latestHistory.asOf().isAfter(closeAsOf))) {
                closePrice = latestHistory.close();
                closeAsOf = latestHistory.asOf();
            }
            var latestPrice = decimal(latest == null ? null : latest.value());
            var latestAsOf = latest == null ? null : latest.asOf();
            if (latestPrice == null && "REGULAR_CLOSE".equals(normalizeSession(session))
                    && closePrice != null && closeAsOf != null) {
                latestPrice = closePrice;
                latestAsOf = closeAsOf;
            }
            quotes.add(new InvestmentDataCalculator.SourceQuote(
                    provider.name(), latestPrice, latestAsOf, session, closePrice, closeAsOf));
        });
        var hasVerifiedRegularClose = quotes.stream().anyMatch(quote -> quote.regularClose() != null
                && quote.regularClose().signum() > 0 && quote.regularCloseAsOf() != null);
        if (!hasVerifiedRegularClose) {
            var stored = latestPersistedRegularClose(userId, ticker, collectedAt);
            if (stored != null) quotes.add(stored);
        }
        return List.copyOf(quotes);
    }

    private InvestmentDataCalculator.SourceQuote latestPersistedRegularClose(
            UUID userId, String ticker, Instant collectedAt
    ) {
        if (collectedAt == null) return null;
        return jdbc.query("""
                SELECT source, regular_close, regular_close_as_of
                 FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = ? AND session = 'REGULAR_CLOSE'
                   AND source = 'TOSS' AND regular_close > 0
                   AND regular_close_as_of IS NOT NULL AND regular_close_as_of <= ?
                 ORDER BY regular_close_as_of DESC, observed_at DESC, source
                 LIMIT 1
                """, resultSet -> resultSet.next()
                ? new InvestmentDataCalculator.SourceQuote(
                        resultSet.getString("source"),
                        resultSet.getBigDecimal("regular_close"),
                        resultSet.getTimestamp("regular_close_as_of").toInstant(),
                        "REGULAR_CLOSE",
                        resultSet.getBigDecimal("regular_close"),
                        resultSet.getTimestamp("regular_close_as_of").toInstant())
                : null, userId, ticker, timestamp(collectedAt));
    }

    private void persistRegularCloseHistory(UUID userId, StockAnalysisInput input,
                                            StockDataProviderId provider,
                                            Map<String, StockAnalysisInput.Observation> values,
                                            OffsetDateTime observedAt) {
        if (provider != StockDataProviderId.TOSS) return;
        var session = normalizeSession(text(value(values, "price.session")));
        var history = observation(values, "price.regularCloseHistory");
        for (var bar : regularCloseBars(
                history == null ? null : history.value(), session, provider, input.collectedAt())) {
            jdbc.update("""
                    INSERT INTO investment_price_snapshots (
                        id, user_id, input_snapshot_id, ticker, as_of, session,
                        latest_price, latest_price_as_of, regular_close, regular_close_as_of, source, observed_at
                    ) VALUES (?, ?, ?, ?, ?, 'REGULAR_CLOSE', ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, ticker, session, source, as_of) DO NOTHING
                    """, UUID.randomUUID(), userId, input.snapshotId(), input.symbol(), timestamp(bar.asOf()),
                    bar.close(), timestamp(bar.asOf()), bar.close(), timestamp(bar.asOf()), provider.name(), observedAt);
        }
    }

    private static List<PriceBar> regularCloseBars(
            JsonNode value, String session, StockDataProviderId provider, Instant collectedAt
    ) {
        var tossHistory = provider == StockDataProviderId.TOSS;
        if ((!tossHistory && !"REGULAR_CLOSE".equals(normalizeSession(session)))
                || value == null || !value.isArray() || collectedAt == null) {
            return List.of();
        }
        var closesByDate = new java.util.TreeMap<LocalDate, PriceBar>();
        var conflictingDates = new java.util.HashSet<LocalDate>();
        var latestAllowedDate = collectedAt.atZone(ZoneOffset.UTC).toLocalDate();
        for (var row : value) {
            if (row == null || !row.isObject()) continue;
            var sourceConflict = row.get("sourceConflict");
            if (tossHistory && sourceConflict != null && sourceConflict.isBoolean()
                    && sourceConflict.booleanValue()) continue;
            var dateText = text(row.get("date"));
            var close = decimal(row.get("close"));
            if (dateText == null || close == null || close.signum() <= 0) continue;
            final LocalDate date;
            try {
                date = LocalDate.parse(dateText);
            } catch (RuntimeException exception) {
                continue;
            }
            final Instant asOf;
            if (tossHistory) {
                if (!"REGULAR_CLOSE".equals(normalizeSession(text(row.get("session"))))) continue;
                try {
                    asOf = OffsetDateTime.parse(text(row.get("timestamp"))).toInstant();
                } catch (RuntimeException exception) {
                    continue;
                }
                var candleTime = asOf.atZone(NEW_YORK);
                if (asOf.isAfter(collectedAt) || !date.equals(candleTime.toLocalDate())
                        || !candleTime.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) continue;
            } else {
                if (date.isAfter(latestAllowedDate)) continue;
                asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            var previous = closesByDate.putIfAbsent(date, new PriceBar(close, asOf));
            if (previous != null && previous.close().compareTo(close) != 0) conflictingDates.add(date);
        }
        conflictingDates.forEach(closesByDate::remove);
        return List.copyOf(closesByDate.values());
    }

    private void markPipeline(UUID userId, String pipeline, String status, Instant lastSuccess, String error) {
        var now = timestamp(clock.instant());
        jdbc.update("""
                INSERT INTO investment_pipeline_state (
                    user_id, pipeline, status, last_attempt_at, last_success_at, last_error
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, pipeline) DO UPDATE SET
                    status = EXCLUDED.status,
                    last_attempt_at = EXCLUDED.last_attempt_at,
                    last_success_at = COALESCE(EXCLUDED.last_success_at, investment_pipeline_state.last_success_at),
                    last_error = EXCLUDED.last_error
                """, userId, pipeline, status, now, timestampOrNull(lastSuccess), error);
    }

    private PipelineView pipelineState(UUID userId, String pipeline) {
        return jdbc.query("""
                SELECT status, last_attempt_at, last_success_at, last_error
                  FROM investment_pipeline_state WHERE user_id = ? AND pipeline = ?
                """, (resultSet, rowNum) -> new PipelineView(
                resultSet.getString("status"), instant(resultSet.getObject("last_attempt_at", OffsetDateTime.class)),
                instant(resultSet.getObject("last_success_at", OffsetDateTime.class)),
                resultSet.getString("last_error")), userId, pipeline)
                .stream().findFirst().orElse(new PipelineView("NEVER_RUN", null, null, null));
    }

    private JsonNode latestSecuritySnapshot(UUID userId, String ticker) {
        var stored = jdbc.query("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = ?
                 ORDER BY as_of DESC, id DESC LIMIT 1
                """, (resultSet, rowNum) -> resultSet.getString(1), userId, ticker)
                .stream().findFirst().orElse(null);
        if (stored != null) return refreshStoredFreshness(decode(stored));
        var empty = new LinkedHashMap<String, Object>();
        empty.put("price", Map.of("status", "DATA_MISSING"));
        empty.put("technical", Map.of("trendStatus", "DATA_MISSING"));
        empty.put("fundamentals", Map.of("status", "DATA_MISSING"));
        empty.put("consensus", Map.of("status", "DATA_MISSING"));
        empty.put("revision", Map.of("status", "DATA_MISSING"));
        empty.put("valuation", Map.of("status", "DATA_MISSING"));
        empty.put("readiness", Map.of(
                "priceStatus", "DATA_MISSING", "trendStatus", "DATA_MISSING",
                "fundamentalStatus", "DATA_MISSING", "consensusStatus", "DATA_MISSING",
                "revisionStatus", "DATA_MISSING", "valuationStatus", "DATA_MISSING",
                "balanceSheetStatus", "DATA_MISSING", "riskStatus", "DATA_MISSING",
                "overallDataStatus", "DATA_MISSING", "missingFields", List.of("investmentSnapshot")));
        return objectMapper.valueToTree(empty);
    }

    private JsonNode refreshStoredFreshness(JsonNode value) {
        if (!(value instanceof ObjectNode snapshot)) return value;
        var now = clock.instant();
        var price = object(snapshot, "price");
        var priceSession = text(price.get("session"));
        var priceStatus = refreshStatus(price, "status", "latestPriceAsOf",
                "REGULAR_CLOSE".equals(priceSession) ? regularCloseStaleAfter : priceStaleAfter, now);
        var technicalStatus = refreshStatus(object(snapshot, "technical"), "trendStatus", "asOf",
                regularCloseStaleAfter, now);
        var fundamentals = object(snapshot, "fundamentals");
        var fundamentalStatus = refreshStatus(fundamentals, "status", "asOf", fundamentalStaleAfter, now);
        var fieldProvenance = fundamentals.get("fieldProvenance");
        if (hasFieldProvenance(fieldProvenance, "cash") || hasFieldProvenance(fieldProvenance, "revenueTTM")) {
            var requiredStatus = requiredFundamentalStatus(fieldProvenance,
                    fundamentals.get("cash") != null && !fundamentals.get("cash").isNull(),
                    fundamentals.get("revenueTTM") != null && !fundamentals.get("revenueTTM").isNull(), now);
            if (requiredStatus == InvestmentDataCalculator.DataStatus.STALE
                    || requiredStatus == InvestmentDataCalculator.DataStatus.UNVERIFIED
                    || requiredStatus == InvestmentDataCalculator.DataStatus.DATA_MISSING) {
                fundamentalStatus = requiredStatus;
                fundamentals.put("status", fundamentalStatus.name());
            } else if (requiredStatus == InvestmentDataCalculator.DataStatus.PARTIAL
                    && fundamentalStatus == InvestmentDataCalculator.DataStatus.OK) {
                fundamentalStatus = requiredStatus;
                fundamentals.put("status", fundamentalStatus.name());
            }
        }
        var consensusStatus = refreshStatus(object(snapshot, "consensus"), "status", "asOf",
                consensusStaleAfter, now);
        var revision = object(snapshot, "revision");
        var revisionStatus = dataStatus(text(revision.get("status")));
        if (consensusStatus == InvestmentDataCalculator.DataStatus.STALE
                && revisionStatus != InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                && revisionStatus != InvestmentDataCalculator.DataStatus.DATA_MISSING
                && revisionStatus != InvestmentDataCalculator.DataStatus.INSUFFICIENT_HISTORY) {
            revisionStatus = InvestmentDataCalculator.DataStatus.STALE;
            revision.put("status", revisionStatus.name());
        }
        var valuation = object(snapshot, "valuation");
        var valuationStatus = refreshValuationMetrics(valuation, now);
        var readiness = object(snapshot, "readiness");
        var balanceStatus = dataStatus(text(readiness.get("balanceSheetStatus")));
        if (hasFieldProvenance(fieldProvenance, "cash") || hasFieldProvenance(fieldProvenance, "debt")) {
            var cashStatus = fieldFreshness(fieldProvenance, "cash",
                    fundamentals.get("cash") != null && !fundamentals.get("cash").isNull(), now);
            var debtStatus = fieldFreshness(fieldProvenance, "debt",
                    fundamentals.get("debt") != null && !fundamentals.get("debt").isNull(), now);
            if (balanceStatus != InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT) {
                balanceStatus = cashStatus == InvestmentDataCalculator.DataStatus.STALE
                        || debtStatus == InvestmentDataCalculator.DataStatus.STALE
                        ? InvestmentDataCalculator.DataStatus.STALE
                        : cashStatus == InvestmentDataCalculator.DataStatus.DATA_MISSING
                        && debtStatus == InvestmentDataCalculator.DataStatus.DATA_MISSING
                        ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                        : cashStatus == InvestmentDataCalculator.DataStatus.UNVERIFIED
                        || debtStatus == InvestmentDataCalculator.DataStatus.UNVERIFIED
                        ? InvestmentDataCalculator.DataStatus.UNVERIFIED
                        : cashStatus != InvestmentDataCalculator.DataStatus.OK
                        || debtStatus != InvestmentDataCalculator.DataStatus.OK
                        ? InvestmentDataCalculator.DataStatus.PARTIAL
                        : InvestmentDataCalculator.DataStatus.OK;
            }
        } else if (fundamentalStatus == InvestmentDataCalculator.DataStatus.STALE
                && balanceStatus != InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                && balanceStatus != InvestmentDataCalculator.DataStatus.DATA_MISSING) {
            balanceStatus = InvestmentDataCalculator.DataStatus.STALE;
        }
        readiness.put("balanceSheetStatus", balanceStatus.name());
        readiness.put("priceStatus", priceStatus.name());
        readiness.put("trendStatus", technicalStatus.name());
        readiness.put("fundamentalStatus", fundamentalStatus.name());
        readiness.put("consensusStatus", consensusStatus.name());
        readiness.put("revisionStatus", revisionStatus.name());
        readiness.put("valuationStatus", valuationStatus.name());
        readiness.put("overallDataStatus", overall(List.of(priceStatus, technicalStatus, fundamentalStatus,
                consensusStatus, revisionStatus, valuationStatus, balanceStatus)).name());
        return snapshot;
    }

    private InvestmentDataCalculator.DataStatus refreshValuationMetrics(ObjectNode valuation, Instant now) {
        var statuses = object(valuation, "metricStatuses");
        if (statuses.isEmpty()) return dataStatus(text(valuation.get("status")));
        var reasons = object(valuation, "metricReasons");
        var provenance = object(valuation, "metricProvenance");
        var updated = new LinkedHashMap<String, InvestmentDataCalculator.DataStatus>();
        statuses.properties().forEach(entry -> {
            var current = dataStatus(text(entry.getValue()));
            var refreshed = refreshMetricInputs(provenance.get(entry.getKey()), current, now);
            statuses.put(entry.getKey(), refreshed.name());
            updated.put(entry.getKey(), refreshed);
            if (refreshed == InvestmentDataCalculator.DataStatus.STALE
                    && reasons.get(entry.getKey()) == null) reasons.put(entry.getKey(), "INPUTS_STALE");
            else if (refreshed != InvestmentDataCalculator.DataStatus.STALE
                    && refreshed != InvestmentDataCalculator.DataStatus.UNVERIFIED
                    && "INPUTS_STALE".equals(text(reasons.get(entry.getKey())))) reasons.remove(entry.getKey());
        });
        var classification = text(valuation.get("classification"));
        var required = switch (classification == null ? "" : classification) {
            case "GROWTH" -> List.of("evSalesTTM");
            case "CYCLICAL" -> List.of("evEbitdaTTM", "normalizedFcf");
            case "COMPOUNDER" -> List.of("forwardPE", "fcfYieldTTM");
            case "POWER_UTILITY" -> List.of("evEbitdaTTM", "fcfYieldTTM");
            default -> List.of("evSalesTTM", "evEbitdaTTM", "fcfYieldTTM");
        };
        var result = overall(required.stream().map(name -> updated.getOrDefault(name,
                InvestmentDataCalculator.DataStatus.DATA_MISSING)).toList());
        valuation.put("status", result.name());
        return result;
    }

    private InvestmentDataCalculator.DataStatus refreshMetricInputs(
            JsonNode metric, InvestmentDataCalculator.DataStatus current, Instant now) {
        if (current == InvestmentDataCalculator.DataStatus.DATA_MISSING
                || current == InvestmentDataCalculator.DataStatus.NOT_APPLICABLE
                || current == InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT) return current;
        var inputs = metric == null ? null : metric.get("inputs");
        var freshness = new ArrayList<InvestmentDataCalculator.DataStatus>();
        collectMetricInputFreshness(inputs, now, freshness);
        if (freshness.contains(InvestmentDataCalculator.DataStatus.STALE))
            return InvestmentDataCalculator.DataStatus.STALE;
        if (freshness.contains(InvestmentDataCalculator.DataStatus.UNVERIFIED))
            return InvestmentDataCalculator.DataStatus.UNVERIFIED;
        return current;
    }

    private void collectMetricInputFreshness(
            JsonNode node, Instant now, List<InvestmentDataCalculator.DataStatus> result) {
        if (node == null || !node.isObject()) return;
        var source = text(node.get("source"));
        var asOf = instant(node.get("asOf"));
        if (source != null && asOf != null) {
            Duration maxAge = source.contains("ALPHA_VANTAGE") ? consensusStaleAfter
                    : "TOSS".equals(source) || "TOSS".equals(text(node.get("priceSource")))
                    ? regularCloseStaleAfter : fundamentalStaleAfter;
            result.add(metricAsOfStatus(asOf, true, maxAge, now));
        }
        var sharesAsOf = instant(node.get("sharesAsOf"));
        if (sharesAsOf != null) result.add(metricAsOfStatus(sharesAsOf, true, fundamentalStaleAfter, now));
        node.properties().forEach(entry -> collectMetricInputFreshness(entry.getValue(), now, result));
    }

    private static InvestmentDataCalculator.DataStatus refreshStatus(
            ObjectNode section, String statusField, String asOfField, Duration maxAge, Instant now) {
        var status = dataStatus(text(section.get(statusField)));
        var asOf = instant(section.get(asOfField));
        if (asOf == null && status == InvestmentDataCalculator.DataStatus.OK) {
            status = InvestmentDataCalculator.DataStatus.PARTIAL;
            section.put(statusField, status.name());
        } else if (asOf != null && asOf.isBefore(now.minus(maxAge))
                && status != InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                && status != InvestmentDataCalculator.DataStatus.DATA_MISSING
                && status != InvestmentDataCalculator.DataStatus.NOT_APPLICABLE) {
            status = InvestmentDataCalculator.DataStatus.STALE;
            section.put(statusField, status.name());
        }
        return status;
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        var child = parent.get(field);
        if (child instanceof ObjectNode object) return object;
        var created = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        parent.set(field, created);
        return created;
    }

    private Map<String, ThesisView> theses(UUID userId, Set<String> symbols) {
        var values = new LinkedHashMap<String, ThesisView>();
        for (var symbol : symbols) {
            var value = thesisIfPresent(userId, symbol);
            if (value != null) values.put(symbol, value);
        }
        return values;
    }

    private ThesisView thesisIfPresent(UUID userId, String ticker) {
        return jdbc.query("""
                SELECT ticker, core_thesis, upside_driver, expectations_gap, fundamental_invalidation,
                       revision_invalidation, price_risk_trigger, price_risk_trigger_price,
                       invalidation_status, expand_trigger, exit_or_discard_trigger, classification, updated_at
                  FROM investment_thesis_states WHERE user_id = ? AND ticker = ?
                """, thesisRow(), userId, ticker).stream().findFirst().orElse(null);
    }

    private ThesisView thesis(UUID userId, String ticker) {
        var value = thesisIfPresent(userId, ticker);
        if (value == null) throw new InvestmentException(InvestmentException.Code.NOT_FOUND);
        return value;
    }

    private static RowMapper<ThesisView> thesisRow() {
        return (resultSet, rowNum) -> new ThesisView(
                resultSet.getString("ticker"), resultSet.getString("core_thesis"),
                resultSet.getString("upside_driver"), resultSet.getString("expectations_gap"),
                resultSet.getString("fundamental_invalidation"), resultSet.getString("revision_invalidation"),
                resultSet.getString("price_risk_trigger"), resultSet.getBigDecimal("price_risk_trigger_price"),
                resultSet.getString("invalidation_status"), resultSet.getString("expand_trigger"),
                resultSet.getString("exit_or_discard_trigger"), resultSet.getString("classification"),
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)));
    }

    private RowMapper<DecisionView> decisionRow() {
        return (resultSet, rowNum) -> new DecisionView(
                resultSet.getObject("decision_id", UUID.class),
                instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                resultSet.getString("asset"), resultSet.getString("action"),
                resultSet.getBigDecimal("reference_price"), resultSet.getString("price_session"),
                resultSet.getString("horizon"), resultSet.getString("alpha_thesis"),
                resultSet.getString("invalidation"), resultSet.getString("next_review_trigger"),
                resultSet.getBigDecimal("confidence"), decode(resultSet.getString("risk_policy_check")),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)));
    }

    private FundamentalData latestFundamental(UUID userId, String ticker) {
        var rows = jdbc.query("""
                SELECT fiscal_period, fiscal_year, fiscal_period_code, reported_at, as_of, source,
                       market_cap, market_cap_as_of, enterprise_value, enterprise_value_as_of,
                       enterprise_value_source, cash, debt, basic_shares, basic_shares_basis,
                       market_cap_formula, fully_diluted_market_cap, fully_diluted_market_cap_as_of,
                       fully_diluted_market_cap_formula, balance_sheet_as_of, currency,
                       diluted_shares, diluted_shares_basis, revenue_ttm, revenue_growth_yoy,
                       ebitda_ttm, ebitda_ttm_type, ebitda_ttm_formula, ebitda_ttm_source,
                       eps, fcf_ttm, field_provenance::text
                 FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = ? AND source = 'SEC'
                 ORDER BY fiscal_period DESC, reported_at DESC, market_cap_as_of DESC NULLS LAST,
                          created_at DESC, id DESC LIMIT 1
                """, (resultSet, rowNum) -> new FundamentalData(
                resultSet.getString("fiscal_period"),
                resultSet.getString("fiscal_year"), resultSet.getString("fiscal_period_code"),
                instant(resultSet.getObject("reported_at", OffsetDateTime.class)),
                instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                resultSet.getString("source"), resultSet.getBigDecimal("market_cap"),
                instant(resultSet.getObject("market_cap_as_of", OffsetDateTime.class)),
                resultSet.getBigDecimal("enterprise_value"),
                instant(resultSet.getObject("enterprise_value_as_of", OffsetDateTime.class)),
                resultSet.getString("enterprise_value_source"), resultSet.getBigDecimal("cash"),
                resultSet.getBigDecimal("debt"), resultSet.getBigDecimal("basic_shares"),
                resultSet.getString("basic_shares_basis"), resultSet.getString("market_cap_formula"),
                resultSet.getBigDecimal("fully_diluted_market_cap"),
                instant(resultSet.getObject("fully_diluted_market_cap_as_of", OffsetDateTime.class)),
                resultSet.getString("fully_diluted_market_cap_formula"),
                instant(resultSet.getObject("balance_sheet_as_of", OffsetDateTime.class)),
                resultSet.getString("currency"), resultSet.getBigDecimal("diluted_shares"),
                resultSet.getString("diluted_shares_basis"),
                resultSet.getBigDecimal("revenue_ttm"), resultSet.getBigDecimal("revenue_growth_yoy"),
                resultSet.getBigDecimal("ebitda_ttm"), resultSet.getString("ebitda_ttm_type"),
                resultSet.getString("ebitda_ttm_formula"), resultSet.getString("ebitda_ttm_source"),
                resultSet.getBigDecimal("eps"), resultSet.getBigDecimal("fcf_ttm"),
                decode(resultSet.getString("field_provenance")), InvestmentDataCalculator.DataStatus.OK),
                userId, ticker);
        var value = rows.stream().findFirst().orElse(null);
        if (value == null) return FundamentalData.missing();
        var fields = java.util.Arrays.asList(value.marketCap(), value.enterpriseValue(), value.cash(), value.debt(),
                value.basicShares(), value.dilutedShares(), value.fullyDilutedMarketCap(), value.revenueTTM(),
                value.revenueGrowthYoY(), value.ebitdaTTM(), value.eps(), value.fcfTTM());
        var present = fields.stream().filter(Objects::nonNull).count();
        var requiredStatus = requiredFundamentalStatus(value.fieldProvenance(), value.cash() != null,
                value.revenueTTM() != null, clock.instant());
        var status = requiredStatus == InvestmentDataCalculator.DataStatus.STALE
                ? requiredStatus
                : requiredStatus == InvestmentDataCalculator.DataStatus.DATA_MISSING
                ? requiredStatus
                : requiredStatus == InvestmentDataCalculator.DataStatus.UNVERIFIED
                ? requiredStatus
                : present == fields.size() ? InvestmentDataCalculator.DataStatus.OK
                : InvestmentDataCalculator.DataStatus.PARTIAL;
        return value.withStatus(status);
    }

    private InvestmentDataCalculator.DataStatus requiredFundamentalStatus(
            JsonNode provenance, boolean hasCash, boolean hasRevenue, Instant now
    ) {
        var cashStatus = fieldFreshness(provenance, "cash", hasCash, now);
        var revenueStatus = fieldFreshness(provenance, "revenueTTM", hasRevenue, now);
        if (cashStatus == InvestmentDataCalculator.DataStatus.STALE
                || revenueStatus == InvestmentDataCalculator.DataStatus.STALE) {
            return InvestmentDataCalculator.DataStatus.STALE;
        }
        if (cashStatus == InvestmentDataCalculator.DataStatus.UNVERIFIED
                || revenueStatus == InvestmentDataCalculator.DataStatus.UNVERIFIED) {
            return InvestmentDataCalculator.DataStatus.UNVERIFIED;
        }
        if (cashStatus == InvestmentDataCalculator.DataStatus.DATA_MISSING
                && revenueStatus == InvestmentDataCalculator.DataStatus.DATA_MISSING) {
            return InvestmentDataCalculator.DataStatus.DATA_MISSING;
        }
        if (cashStatus != InvestmentDataCalculator.DataStatus.OK
                || revenueStatus != InvestmentDataCalculator.DataStatus.OK) {
            return InvestmentDataCalculator.DataStatus.PARTIAL;
        }
        return InvestmentDataCalculator.DataStatus.OK;
    }

    private InvestmentDataCalculator.DataStatus fieldFreshness(
            JsonNode provenance, String field, boolean present, Instant now
    ) {
        if (!present) return InvestmentDataCalculator.DataStatus.DATA_MISSING;
        var asOf = instant(node(provenance, field).get("asOf"));
        if (asOf == null || asOf.isAfter(now)) return InvestmentDataCalculator.DataStatus.UNVERIFIED;
        return asOf.isBefore(now.minus(fundamentalStaleAfter))
                ? InvestmentDataCalculator.DataStatus.STALE : InvestmentDataCalculator.DataStatus.OK;
    }

    private static boolean hasFieldProvenance(JsonNode provenance, String field) {
        return provenance != null && provenance.isObject() && provenance.get(field) != null
                && provenance.get(field).isObject();
    }

    private ConsensusData latestConsensus(UUID userId, String ticker) {
        var today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        var horizonObservations = jdbc.query("""
                SELECT DISTINCT ON (estimate_type, horizon)
                       as_of, horizon, estimate_type, estimate_label,
                       COALESCE(period_end, CASE WHEN horizon ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                           THEN horizon::date END) AS period_end,
                       revenue_consensus, eps_consensus, eps_analyst_count,
                       revenue_analyst_count, currency, source
                  FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = ? AND source = 'ALPHA_VANTAGE'
                   AND COALESCE(period_end, CASE WHEN horizon ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                       THEN horizon::date END) > ?
                   AND as_of <= ?
                 ORDER BY estimate_type, horizon, as_of DESC, id DESC
                """, (resultSet, rowNum) -> map(
                "asOf", instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                "horizon", resultSet.getString("horizon"),
                "estimateType", resultSet.getString("estimate_type"),
                "estimateLabel", resultSet.getString("estimate_label"),
                "periodEnd", resultSet.getObject("period_end", LocalDate.class),
                "revenueConsensus", resultSet.getBigDecimal("revenue_consensus"),
                "epsConsensus", resultSet.getBigDecimal("eps_consensus"),
                "epsAnalystCount", resultSet.getObject("eps_analyst_count"),
                "revenueAnalystCount", resultSet.getObject("revenue_analyst_count"),
                "currency", resultSet.getString("currency"), "source", resultSet.getString("source")),
                userId, ticker, today, timestamp(clock.instant()));
        var rows = jdbc.query("""
                SELECT as_of, horizon, source, estimate_type, estimate_label, period_end,
                       revenue_consensus, eps_consensus, ebitda_consensus, fcf_consensus,
                       eps_analyst_count, revenue_analyst_count, currency
                 FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = ?
                   AND source = 'ALPHA_VANTAGE'
                   AND COALESCE(period_end, CASE WHEN horizon ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                       THEN horizon::date END) > ?
                   AND as_of <= ?
                 ORDER BY CASE WHEN source = 'ALPHA_VANTAGE' THEN 0 ELSE 1 END,
                          CASE WHEN estimate_type = 'ANNUAL' THEN 0 ELSE 1 END,
                          COALESCE(period_end, CASE WHEN horizon ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                              THEN horizon::date END) ASC,
                          as_of DESC, id DESC LIMIT 1
                """, (resultSet, rowNum) -> new ConsensusData(
                instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                resultSet.getString("horizon"), resultSet.getString("source"),
                resultSet.getString("estimate_type"), resultSet.getString("estimate_label"),
                resultSet.getObject("period_end", LocalDate.class),
                resultSet.getBigDecimal("revenue_consensus"), resultSet.getBigDecimal("eps_consensus"),
                resultSet.getBigDecimal("ebitda_consensus"), resultSet.getBigDecimal("fcf_consensus"),
                (Integer) resultSet.getObject("eps_analyst_count"),
                (Integer) resultSet.getObject("revenue_analyst_count"), resultSet.getString("currency"),
                horizonObservations, null,
                InvestmentDataCalculator.DataStatus.OK), userId, ticker, today, timestamp(clock.instant()));
        var missingReason = latestAlphaConsensusFailure(userId, ticker);
        var value = rows.stream().findFirst().orElseGet(ConsensusData::missing)
                .withMissingReason(missingReason);
        if (value.asOf() == null) return value;
        var present = java.util.Arrays.asList(value.revenueConsensus(), value.epsConsensus(),
                value.ebitdaConsensus(), value.fcfConsensus()).stream().filter(Objects::nonNull).count();
        var status = value.asOf().isBefore(clock.instant().minus(consensusStaleAfter))
                ? InvestmentDataCalculator.DataStatus.STALE
                : present == CONSENSUS_FIELDS.size() ? InvestmentDataCalculator.DataStatus.OK
                : present == 0 ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : InvestmentDataCalculator.DataStatus.PARTIAL;
        if (consensusCurrencyUnverified(value) && status == InvestmentDataCalculator.DataStatus.OK) {
            status = InvestmentDataCalculator.DataStatus.PARTIAL;
        }
        return value.withStatus(status);
    }

    private String latestAlphaConsensusFailure(UUID userId, String ticker) {
        var payload = jdbc.query("""
                SELECT payload::text FROM analysis_input_snapshots
                 WHERE user_id = ? AND symbol = ?
                 ORDER BY collected_at DESC, created_at DESC, id DESC LIMIT 1
                """, (resultSet, rowNum) -> resultSet.getString(1), userId, ticker)
                .stream().findFirst().orElse(null);
        if (payload == null) return null;
        try {
            var observations = objectMapper.readTree(payload).path("observations");
            if (!observations.isArray()) return null;
            for (var observation : observations) {
                if (!"ALPHA_VANTAGE".equals(observation.path("provider").asText())
                        || !observation.path("field").asText().startsWith("consensus.")) continue;
                var reasons = observation.path("missingData");
                if (!reasons.isArray()) continue;
                for (var reason : reasons) {
                    var value = reason.asText();
                    var normalized = normalizedProviderFailureReason(StockDataProviderId.ALPHA_VANTAGE, value);
                    if (normalized == null) continue;
                    var code = normalized.substring("PROVIDER_".length());
                    if (isAlphaProviderFailureCode(code)) return code;
                }
            }
        } catch (JacksonException ignored) {
            return null;
        }
        return null;
    }

    private static String normalizedProviderFailureReason(StockDataProviderId provider, String reason) {
        if (reason.startsWith("PROVIDER_")) return reason;
        if (provider == StockDataProviderId.ALPHA_VANTAGE && isAlphaProviderFailureCode(reason)) {
            return "PROVIDER_" + reason;
        }
        return null;
    }

    private static boolean isAlphaProviderFailureCode(String reason) {
        return ALPHA_PROVIDER_FAILURE_CODES.contains(reason) || reason.matches("HTTP_[1-5][0-9]{2}");
    }

    private static boolean consensusCurrencyUnverified(ConsensusData consensus) {
        return consensus.currency() == null || !"USD".equalsIgnoreCase(consensus.currency());
    }

    private RevisionData revisions(UUID userId, String ticker, ConsensusData current) {
        if (current.asOf() == null || current.source() == null || current.horizon() == null) {
            return RevisionData.missing();
        }
        var history = jdbc.query("""
                SELECT as_of, revenue_consensus, eps_consensus
                  FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = ? AND source = ? AND horizon = ? AND estimate_type = ?
                   AND as_of <= ? AND as_of >= ?
                 ORDER BY as_of
                """, (resultSet, rowNum) -> new ConsensusHistory(
                instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                resultSet.getBigDecimal("revenue_consensus"), resultSet.getBigDecimal("eps_consensus")),
                userId, ticker, current.source(), current.horizon(), current.estimateType(), timestamp(current.asOf()),
                timestamp(current.asOf().minus(Duration.ofDays(110))));
        var revenue = history.stream().map(item -> new InvestmentDataCalculator.ConsensusValue(
                item.asOf(), item.revenue())).toList();
        var eps = history.stream().map(item -> new InvestmentDataCalculator.ConsensusValue(
                item.asOf(), item.eps())).toList();
        var revenue30 = InvestmentDataCalculator.revision(current.revenueConsensus(), current.asOf(), revenue,
                Duration.ofDays(30));
        var revenue90 = InvestmentDataCalculator.revision(current.revenueConsensus(), current.asOf(), revenue,
                Duration.ofDays(90));
        var eps30 = InvestmentDataCalculator.revision(current.epsConsensus(), current.asOf(), eps,
                Duration.ofDays(30));
        var eps90 = InvestmentDataCalculator.revision(current.epsConsensus(), current.asOf(), eps,
                Duration.ofDays(90));
        var present = List.of(revenue30, revenue90, eps30, eps90).stream()
                .filter(item -> item.value() != null).count();
        var anyCurrentValue = current.revenueConsensus() != null || current.epsConsensus() != null;
        var insufficient = List.of(revenue30, revenue90, eps30, eps90).stream()
                .anyMatch(item -> item.status() == InvestmentDataCalculator.DataStatus.INSUFFICIENT_HISTORY);
        return new RevisionData(revenue30, revenue90, eps30, eps90,
                present == 4 ? InvestmentDataCalculator.DataStatus.OK
                        : present == 0 && insufficient && anyCurrentValue
                        ? InvestmentDataCalculator.DataStatus.INSUFFICIENT_HISTORY
                        : present == 0 ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                        : InvestmentDataCalculator.DataStatus.PARTIAL);
    }

    private TechnicalData technical(UUID userId, String ticker, BigDecimal latestPrice) {
        var rows = jdbc.query("""
                SELECT regular_close, regular_close_as_of
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = ? AND regular_close IS NOT NULL
                   AND source = 'TOSS'
                   AND regular_close_as_of <= ?
                 ORDER BY regular_close_as_of DESC, source
                 LIMIT 1000
                """, (resultSet, rowNum) -> new PriceBar(
                resultSet.getBigDecimal("regular_close"),
                instant(resultSet.getObject("regular_close_as_of", OffsetDateTime.class))),
                userId, ticker, timestamp(clock.instant()));
        var unique = new LinkedHashMap<LocalDate, BigDecimal>();
        rows.forEach(row -> unique.putIfAbsent(row.asOf().atZone(ZoneOffset.UTC).toLocalDate(), row.close()));
        var closes = unique.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).toList();
        var sma20 = averageTail(closes, 20);
        var sma50 = averageTail(closes, 50);
        var rsi14 = rsi14(closes);
        var asOf = rows.stream().map(PriceBar::asOf).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        var status = closes.size() >= 50 ? InvestmentDataCalculator.DataStatus.OK
                : closes.size() >= 20 ? InvestmentDataCalculator.DataStatus.PARTIAL
                : closes.isEmpty() ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : InvestmentDataCalculator.DataStatus.PARTIAL;
        return new TechnicalData(latestPrice, sma20, sma50, rsi14, closes.size(), asOf, status);
    }

    private ValuationData valuation(UUID userId, String ticker,
                                    InvestmentDataCalculator.PriceAssessment price,
                                    FundamentalData fundamental, ConsensusData consensus, ThesisView thesis) {
        var classification = thesis == null ? null : normalizeClassification(thesis.classification());
        var marketCap = fundamental.marketCap();
        var enterpriseValue = fundamental.enterpriseValue();
        var currencyUnverified = consensusCurrencyUnverified(consensus);
        var annualForward = "ANNUAL".equals(consensus.estimateType());
        var evSalesTTM = multiple(enterpriseValue, fundamental.revenueTTM());
        var evSalesForward = !annualForward || currencyUnverified ? null
                : multiple(enterpriseValue, consensus.revenueConsensus());
        var evEbitdaTTM = multiple(enterpriseValue, fundamental.ebitdaTTM());
        var evEbitdaForward = !annualForward || currencyUnverified ? null
                : multiple(enterpriseValue, consensus.ebitdaConsensus());
        var forwardPe = annualForward && !currencyUnverified
                ? multiple(price.regularClose(), consensus.epsConsensus()) : null;
        var fcfYieldTTM = ratio(fundamental.fcfTTM(), marketCap);
        var fcfYieldForward = annualForward && !currencyUnverified
                ? ratio(consensus.fcfConsensus(), marketCap) : null;
        var normalizedFcf = normalizedFcf(userId, ticker, fundamental.source());
        var normalizedFcfYield = ratio(normalizedFcf.value(), marketCap);
        var now = clock.instant();
        var marketCapStatus = marketCapFreshness(fundamental, now);
        var enterpriseValueStatus = enterpriseValueFreshness(fundamental, marketCapStatus, now);
        var revenueStatus = financialFieldFreshness(fundamental, "revenueTTM", fundamental.revenueTTM(), now);
        var ebitdaStatus = financialFieldFreshness(fundamental, "ebitdaTTM", fundamental.ebitdaTTM(), now);
        var fcfStatus = financialFieldFreshness(fundamental, "fcfTTM", fundamental.fcfTTM(), now);
        var consensusFreshness = metricAsOfStatus(consensus.asOf(), consensus.asOf() != null,
                consensusStaleAfter, now);
        var closeFreshness = metricAsOfStatus(price.regularCloseAsOf(), price.regularClose() != null,
                regularCloseStaleAfter, now);
        var annualStatus = annualForward ? InvestmentDataCalculator.DataStatus.OK
                : InvestmentDataCalculator.DataStatus.DATA_MISSING;

        var metricStatuses = new LinkedHashMap<String, String>();
        var metricReasons = new LinkedHashMap<String, String>();
        var metricProvenance = new LinkedHashMap<String, Object>();
        addMetric(metricStatuses, metricReasons, "evSalesTTM", evSalesTTM,
                metricStatus(evSalesTTM, enterpriseValueStatus, revenueStatus,
                        unitStatus(verifiedUsd(fundamental.fieldProvenance(), "enterpriseValue", "revenueTTM"),
                                enterpriseValue != null && fundamental.revenueTTM() != null), null),
                metricReason(evSalesTTM, enterpriseValue, fundamental.revenueTTM(),
                        "ENTERPRISE_VALUE_MISSING", "REVENUE_TTM_MISSING", null));
        addMetric(metricStatuses, metricReasons, "evSalesForward", evSalesForward,
                metricStatus(evSalesForward, enterpriseValueStatus, consensusFreshness, annualStatus,
                        consensusCurrencyUnverifiedStatus(consensus, enterpriseValue,
                                consensus.revenueConsensus()),
                        unitStatus(verifiedUsd(fundamental.fieldProvenance(), "enterpriseValue"),
                                enterpriseValue != null && consensus.revenueConsensus() != null)),
                metricReason(evSalesForward, enterpriseValue, consensus.revenueConsensus(),
                        "ENTERPRISE_VALUE_MISSING", "REVENUE_ESTIMATE_MISSING",
                        forwardBlockReason(consensus, consensus.revenueConsensus())));
        addMetric(metricStatuses, metricReasons, "evEbitdaTTM", evEbitdaTTM,
                metricStatus(evEbitdaTTM, enterpriseValueStatus, ebitdaStatus,
                        unitStatus(verifiedUsd(fundamental.fieldProvenance(), "enterpriseValue", "ebitdaTTM"),
                                enterpriseValue != null && fundamental.ebitdaTTM() != null), null),
                metricReason(evEbitdaTTM, enterpriseValue, fundamental.ebitdaTTM(),
                        "ENTERPRISE_VALUE_MISSING", "EBITDA_TTM_MISSING", null));
        addMetric(metricStatuses, metricReasons, "evEbitdaForward", evEbitdaForward,
                metricStatus(evEbitdaForward, enterpriseValueStatus, consensusFreshness, annualStatus,
                        consensusCurrencyUnverifiedStatus(consensus, enterpriseValue,
                                consensus.ebitdaConsensus()),
                        unitStatus(verifiedUsd(fundamental.fieldProvenance(), "enterpriseValue"),
                                enterpriseValue != null && consensus.ebitdaConsensus() != null)),
                metricReason(evEbitdaForward, enterpriseValue, consensus.ebitdaConsensus(),
                        "ENTERPRISE_VALUE_MISSING", "EBITDA_ESTIMATE_MISSING",
                        forwardBlockReason(consensus, consensus.ebitdaConsensus())));
        addMetric(metricStatuses, metricReasons, "forwardPE", forwardPe,
                metricStatus(forwardPe, closeFreshness, consensusFreshness, annualStatus,
                        consensusCurrencyUnverifiedStatus(consensus, price.regularClose(),
                                consensus.epsConsensus()),
                        price.regularClose() == null || consensus.epsConsensus() == null
                                ? null : InvestmentDataCalculator.DataStatus.OK),
                metricReason(forwardPe, price.regularClose(), consensus.epsConsensus(),
                        "REGULAR_CLOSE_MISSING", "EPS_ESTIMATE_MISSING",
                        forwardBlockReason(consensus, consensus.epsConsensus())));
        addMetric(metricStatuses, metricReasons, "fcfYieldTTM", fcfYieldTTM,
                metricStatus(fcfYieldTTM, fcfStatus, marketCapStatus,
                        unitStatus(verifiedUsd(fundamental.fieldProvenance(), "fcfTTM", "marketCap"),
                                fundamental.fcfTTM() != null && marketCap != null), null),
                metricReason(fcfYieldTTM, fundamental.fcfTTM(), marketCap,
                        "FCF_TTM_MISSING", "MARKET_CAP_MISSING", null));
        addMetric(metricStatuses, metricReasons, "fcfYieldForward", fcfYieldForward,
                metricStatus(fcfYieldForward, consensusFreshness, annualStatus, marketCapStatus,
                        consensusCurrencyUnverifiedStatus(consensus, consensus.fcfConsensus(), marketCap),
                        unitStatus(verifiedUsd(fundamental.fieldProvenance(), "marketCap"),
                                consensus.fcfConsensus() != null && marketCap != null)),
                metricReason(fcfYieldForward, consensus.fcfConsensus(), marketCap,
                        "FCF_ESTIMATE_MISSING", "MARKET_CAP_MISSING",
                        forwardBlockReason(consensus, consensus.fcfConsensus())));
        addMetric(metricStatuses, metricReasons, "normalizedFcf", normalizedFcf.value(),
                normalizedFcf.value() == null ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                        : metricAsOfStatus(normalizedFcf.asOf(), true, fundamentalStaleAfter, now),
                normalizedFcf.value() == null ? "INSUFFICIENT_FCF_HISTORY" : null);
        addMetric(metricStatuses, metricReasons, "normalizedFcfYield", normalizedFcfYield,
                metricStatus(normalizedFcfYield,
                        normalizedFcf.value() == null ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                                : metricAsOfStatus(normalizedFcf.asOf(), true, fundamentalStaleAfter, now),
                        marketCapStatus, unitStatus(verifiedUsd(fundamental.fieldProvenance(), "marketCap"),
                                normalizedFcf.value() != null && marketCap != null), null),
                metricReason(normalizedFcfYield, normalizedFcf.value(), marketCap,
                        "NORMALIZED_FCF_MISSING", "MARKET_CAP_MISSING", null));

        var inputs = fundamental.fieldProvenance();
        metricProvenance.put("evSalesTTM", metricProvenance("enterpriseValue / revenueTTM", "SEC+TOSS", "USD",
                latestInstant(fundamental.enterpriseValueAsOf(), fieldAsOf(inputs, "revenueTTM")),
                Map.of("marketCap", node(inputs, "marketCap"), "basicShares", node(inputs, "basicShares"),
                        "cash", node(inputs, "cash"), "debt", node(inputs, "debt"),
                        "enterpriseValue", node(inputs, "enterpriseValue"),
                        "revenueTTM", node(inputs, "revenueTTM"))));
        metricProvenance.put("evSalesForward", forwardMetricProvenance("enterpriseValue / annualRevenueConsensus",
                latestInstant(fundamental.enterpriseValueAsOf(), consensus.asOf()), inputs, consensus));
        metricProvenance.put("evEbitdaTTM", metricProvenance("enterpriseValue / ebitdaTTM", "SEC+TOSS", "USD",
                latestInstant(fundamental.enterpriseValueAsOf(), fieldAsOf(inputs, "ebitdaTTM")),
                Map.of("marketCap", node(inputs, "marketCap"), "basicShares", node(inputs, "basicShares"),
                        "cash", node(inputs, "cash"), "debt", node(inputs, "debt"),
                        "ebitdaTTM", node(inputs, "ebitdaTTM"))));
        metricProvenance.put("evEbitdaForward", forwardMetricProvenance("enterpriseValue / annualEbitdaConsensus",
                latestInstant(fundamental.enterpriseValueAsOf(), consensus.asOf()), inputs, consensus));
        metricProvenance.put("forwardPE", map("formula", "TOSS regularClose / annual EPS consensus",
                "asOf", latestInstant(price.regularCloseAsOf(), consensus.asOf()), "source", "TOSS+ALPHA_VANTAGE",
                "currency", consensus.currency(), "estimateType", consensus.estimateType(),
                "periodEnd", consensus.periodEnd(), "horizon", consensus.horizon(),
                "inputs", Map.of("regularClose", map("source", "TOSS", "asOf", price.regularCloseAsOf(),
                                "unit", "USD", "session", "REGULAR_CLOSE"),
                        "epsConsensus", consensusInput(consensus))));
        metricProvenance.put("fcfYieldTTM", metricProvenance("fcfTTM / marketCap", "SEC+TOSS", "USD",
                latestInstant(fieldAsOf(inputs, "fcfTTM"), fundamental.marketCapAsOf()),
                Map.of("fcfTTM", node(inputs, "fcfTTM"), "marketCap", node(inputs, "marketCap"),
                        "basicShares", node(inputs, "basicShares"))));
        metricProvenance.put("fcfYieldForward", map("formula", "annual FCF consensus / marketCap",
                "asOf", latestInstant(consensus.asOf(), fundamental.marketCapAsOf()),
                "source", "ALPHA_VANTAGE+TOSS", "currency", consensus.currency(),
                "estimateType", consensus.estimateType(), "periodEnd", consensus.periodEnd(),
                "horizon", consensus.horizon(), "inputs", Map.of("fcfConsensus", consensusInput(consensus),
                        "marketCap", node(inputs, "marketCap"), "basicShares", node(inputs, "basicShares"))));
        metricProvenance.put("normalizedFcf", map("formula", "mean(latest 3 fiscal period FCF TTM)",
                "asOf", normalizedFcf.asOf(), "source", fundamental.source(),
                "currency", "USD", "periods", normalizedFcf.normalizedFcfPeriods()));
        metricProvenance.put("normalizedFcfYield", metricProvenance("normalizedFcf / marketCap", "SEC+TOSS", "USD",
                latestInstant(normalizedFcf.asOf(), fundamental.marketCapAsOf()),
                Map.of("normalizedFcf", map("source", fundamental.source(), "asOf", normalizedFcf.asOf(),
                                "periods", normalizedFcf.normalizedFcfPeriods()),
                        "marketCap", node(inputs, "marketCap"), "basicShares", node(inputs, "basicShares"))));

        var displayCurrency = verifiedIssuerCurrency(fundamental);
        var currencyProvenance = node(fundamental.fieldProvenance(), "currency");
        var displayCurrencySourceAsOf = instant(currencyProvenance.get("asOf"));
        var displayCurrencySource = text(currencyProvenance.get("source"));
        var displayCurrencyPeriod = text(currencyProvenance.get("period"));
        var displayCurrencyStatus = fieldFreshness(fundamental.fieldProvenance(), "currency",
                displayCurrency != null, now);

        List<InvestmentDataCalculator.DataStatus> neededStatuses = classification == null
                ? List.of(metricStatus(metricStatuses, "evSalesTTM"),
                        metricStatus(metricStatuses, "evEbitdaTTM"), metricStatus(metricStatuses, "fcfYieldTTM"))
                : switch (classification) {
                    case "GROWTH" -> List.of(metricStatus(metricStatuses, "evSalesTTM"));
                    case "CYCLICAL" -> List.of(metricStatus(metricStatuses, "evEbitdaTTM"),
                            metricStatus(metricStatuses, "normalizedFcf"));
                    case "COMPOUNDER" -> List.of(metricStatus(metricStatuses, "forwardPE"),
                            metricStatus(metricStatuses, "fcfYieldTTM"));
                    case "POWER_UTILITY" -> List.of(metricStatus(metricStatuses, "evEbitdaTTM"),
                            metricStatus(metricStatuses, "fcfYieldTTM"));
                    default -> List.<InvestmentDataCalculator.DataStatus>of();
                };
        var status = neededStatuses.isEmpty() ? InvestmentDataCalculator.DataStatus.NOT_APPLICABLE
                : overall(neededStatuses);
        var currencyUnverifiedAsStatus = displayCurrencyStatus;
        return new ValuationData(classification, evSalesTTM, evSalesForward, evEbitdaTTM,
                evEbitdaForward, forwardPe, fcfYieldTTM, fcfYieldForward,
                normalizedFcf.value(), normalizedFcfYield, normalizedFcf.asOf(),
                normalizedFcf.normalizedFcfPeriods(),
                fundamental.asOf(), fundamental.source(), consensus.asOf(), consensus.horizon(),
                consensus.source(), consensus.estimateType(), consensus.periodEnd(), consensus.currency(),
                displayCurrency, displayCurrencySource, displayCurrencySourceAsOf, displayCurrencyPeriod,
                currencyUnverifiedAsStatus.name(), Map.copyOf(metricStatuses), Map.copyOf(metricReasons),
                Map.copyOf(metricProvenance), status);
    }

    private DataStatus marketCapFreshness(FundamentalData fundamental, Instant now) {
        var closeStatus = metricAsOfStatus(fundamental.marketCapAsOf(), fundamental.marketCap() != null,
                regularCloseStaleAfter, now);
        var sharesStatus = fieldFreshness(fundamental.fieldProvenance(), "basicShares",
                fundamental.basicShares() != null, now);
        if (fundamental.marketCap() == null) {
            return combineMetricStatuses(closeStatus, sharesStatus);
        }
        if (!verifiedUsd(fundamental.fieldProvenance(), "marketCap")
                || !unitEquals(fundamental.fieldProvenance(), "basicShares", "shares")) {
            return InvestmentDataCalculator.DataStatus.UNVERIFIED;
        }
        return combineMetricStatuses(closeStatus, sharesStatus);
    }

    private DataStatus enterpriseValueFreshness(
            FundamentalData fundamental, DataStatus marketCapStatus, Instant now) {
        if (fundamental.enterpriseValue() == null) {
            var missingInputs = List.of(marketCapStatus,
                    fieldFreshness(fundamental.fieldProvenance(), "cash", fundamental.cash() != null, now),
                    fieldFreshness(fundamental.fieldProvenance(), "debt", fundamental.debt() != null, now));
            if (missingInputs.contains(InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT))
                return InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT;
            if (missingInputs.contains(InvestmentDataCalculator.DataStatus.STALE))
                return InvestmentDataCalculator.DataStatus.STALE;
            if (missingInputs.contains(InvestmentDataCalculator.DataStatus.UNVERIFIED))
                return InvestmentDataCalculator.DataStatus.UNVERIFIED;
            return InvestmentDataCalculator.DataStatus.DATA_MISSING;
        }
        if (!verifiedUsd(fundamental.fieldProvenance(), "enterpriseValue", "cash", "debt"))
            return InvestmentDataCalculator.DataStatus.UNVERIFIED;
        return combineMetricStatuses(marketCapStatus,
                fieldFreshness(fundamental.fieldProvenance(), "cash", fundamental.cash() != null, now),
                fieldFreshness(fundamental.fieldProvenance(), "debt", fundamental.debt() != null, now));
    }

    private DataStatus financialFieldFreshness(
            FundamentalData fundamental, String field, BigDecimal value, Instant now) {
        var status = fieldFreshness(fundamental.fieldProvenance(), field, value != null, now);
        if (value == null) return status;
        return unitEquals(fundamental.fieldProvenance(), field, "USD")
                ? status : InvestmentDataCalculator.DataStatus.UNVERIFIED;
    }

    private DataStatus metricAsOfStatus(Instant asOf, boolean present, Duration maxAge, Instant now) {
        if (!present) return InvestmentDataCalculator.DataStatus.DATA_MISSING;
        if (asOf == null || asOf.isAfter(now)) return InvestmentDataCalculator.DataStatus.UNVERIFIED;
        return asOf.isBefore(now.minus(maxAge))
                ? InvestmentDataCalculator.DataStatus.STALE : InvestmentDataCalculator.DataStatus.OK;
    }

    private static DataStatus combineMetricStatuses(DataStatus... statuses) {
        var values = java.util.Arrays.stream(statuses).filter(Objects::nonNull).toList();
        if (values.contains(DataStatus.SOURCE_CONFLICT)) return DataStatus.SOURCE_CONFLICT;
        if (values.contains(DataStatus.STALE)) return DataStatus.STALE;
        if (values.contains(DataStatus.UNVERIFIED)) return DataStatus.UNVERIFIED;
        if (values.contains(DataStatus.PARTIAL)) return DataStatus.PARTIAL;
        if (values.contains(DataStatus.DATA_MISSING)) return DataStatus.DATA_MISSING;
        return DataStatus.OK;
    }

    private static DataStatus metricStatus(
            BigDecimal value, DataStatus first, DataStatus second, DataStatus... remaining) {
        var statuses = new ArrayList<DataStatus>();
        statuses.add(first);
        statuses.add(second);
        java.util.Arrays.stream(remaining).filter(Objects::nonNull).forEach(statuses::add);
        var combined = combineMetricStatuses(statuses.toArray(DataStatus[]::new));
        if (value == null && combined == DataStatus.OK) return DataStatus.DATA_MISSING;
        return value != null && combined == DataStatus.DATA_MISSING ? DataStatus.UNVERIFIED : combined;
    }

    private static DataStatus unitStatus(boolean verified, boolean operandsPresent) {
        if (!operandsPresent) return null;
        return verified ? DataStatus.OK : DataStatus.UNVERIFIED;
    }

    private static DataStatus consensusCurrencyUnverifiedStatus(
            ConsensusData consensus, BigDecimal numerator, BigDecimal denominator) {
        return numerator != null && denominator != null && consensusCurrencyUnverified(consensus)
                ? DataStatus.UNVERIFIED : DataStatus.OK;
    }

    private static String forwardBlockReason(ConsensusData consensus, BigDecimal estimate) {
        if (estimate == null) return "FORWARD_ESTIMATE_MISSING";
        if (!"ANNUAL".equals(consensus.estimateType())) return "ANNUAL_ESTIMATE_REQUIRED";
        if (consensusCurrencyUnverified(consensus)) return "CONSENSUS_CURRENCY_UNVERIFIED";
        return null;
    }

    private static String metricReason(
            BigDecimal result, BigDecimal numerator, BigDecimal denominator,
            String numeratorMissing, String denominatorMissing, String blockedReason) {
        if (result != null) return null;
        if (blockedReason != null) return blockedReason;
        if (denominator != null && denominator.signum() <= 0) return "NON_POSITIVE_DENOMINATOR";
        if (numerator == null) return numeratorMissing;
        if (denominator == null) return denominatorMissing;
        return "INPUT_UNAVAILABLE";
    }

    private static void addMetric(
            Map<String, String> statuses, Map<String, String> reasons, String name,
            BigDecimal value, DataStatus status, String reason) {
        if (value == null && "NON_POSITIVE_DENOMINATOR".equals(reason)) {
            status = DataStatus.NOT_APPLICABLE;
        } else if (value == null && status == DataStatus.OK) {
            status = DataStatus.DATA_MISSING;
        }
        statuses.put(name, status.name());
        if (reason != null) reasons.put(name, reason);
        else if (status == DataStatus.STALE) reasons.put(name, "INPUTS_STALE");
        else if (status == DataStatus.UNVERIFIED) reasons.put(name, "INPUT_PROVENANCE_UNVERIFIED");
        else if (status == DataStatus.SOURCE_CONFLICT) reasons.put(name, "INPUT_SOURCE_CONFLICT");
    }

    private static DataStatus metricStatus(Map<String, String> statuses, String name) {
        var value = statuses.get(name);
        return value == null ? DataStatus.DATA_MISSING : DataStatus.valueOf(value);
    }

    private static boolean verifiedUsd(JsonNode provenance, String... fields) {
        for (var field : fields) {
            if (!unitEquals(provenance, field, "USD")) return false;
        }
        return true;
    }

    private static boolean unitEquals(JsonNode provenance, String field, String expected) {
        var unit = text(node(provenance, field).get("unit"));
        return expected.equalsIgnoreCase(unit);
    }

    private static Instant fieldAsOf(JsonNode provenance, String field) {
        return instant(node(provenance, field).get("asOf"));
    }

    private static Map<String, Object> metricProvenance(
            String formula, String source, String currency, Instant asOf, Map<String, ?> inputs) {
        return map("formula", formula, "asOf", asOf, "source", source, "currency", currency,
                "inputs", inputs);
    }

    private static Map<String, Object> forwardMetricProvenance(
            String formula, Instant asOf, JsonNode financialProvenance, ConsensusData consensus) {
        return map("formula", formula, "asOf", asOf, "source", "SEC+TOSS+ALPHA_VANTAGE", "currency",
                consensus.currency(), "estimateType", consensus.estimateType(), "periodEnd", consensus.periodEnd(),
                "horizon", consensus.horizon(), "inputs", Map.of("marketCap", node(financialProvenance, "marketCap"),
                        "cash", node(financialProvenance, "cash"), "debt", node(financialProvenance, "debt"),
                        "annualConsensus", consensusInput(consensus)));
    }

    private static Map<String, Object> consensusInput(ConsensusData consensus) {
        return map("source", consensus.source(), "asOf", consensus.asOf(),
                "currency", consensus.currency(), "estimateType", consensus.estimateType(),
                "periodEnd", consensus.periodEnd(), "horizon", consensus.horizon());
    }

    private String verifiedIssuerCurrency(FundamentalData fundamental) {
        var currency = fundamental.currency();
        var provenance = node(fundamental.fieldProvenance(), "currency");
        var source = text(provenance.get("source"));
        var asOf = instant(provenance.get("asOf"));
        var identifier = text(provenance.get("identifier"));
        var period = text(provenance.get("period"));
        return currency != null && currency.matches("[A-Za-z]{3}")
                && "SEC".equals(source) && asOf != null && !asOf.isAfter(clock.instant())
                && identifier != null && !identifier.isBlank()
                && (period == null || !period.isBlank())
                ? currency.toUpperCase(Locale.ROOT) : null;
    }

    private NormalizedFcf normalizedFcf(UUID userId, String ticker, String source) {
        if (source == null) return NormalizedFcf.missing();
        var rows = jdbc.query("""
                SELECT DISTINCT ON (fiscal_period) fiscal_period, fcf_ttm, as_of
                  FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = ? AND source = ? AND fcf_ttm IS NOT NULL
                 ORDER BY fiscal_period, as_of DESC
                """, (resultSet, rowNum) -> new NormalizedFcfPeriod(
                resultSet.getString("fiscal_period"), resultSet.getBigDecimal("fcf_ttm"),
                instant(resultSet.getObject("as_of", OffsetDateTime.class))), userId, ticker, source)
                .stream().sorted(Comparator.comparing(NormalizedFcfPeriod::asOf).reversed()).limit(3).toList();
        if (rows.size() < 3) return NormalizedFcf.missing();
        var average = rows.stream().map(NormalizedFcfPeriod::fcf).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rows.size()), MathContext.DECIMAL128);
        var asOf = rows.stream().map(NormalizedFcfPeriod::asOf).min(Comparator.naturalOrder()).orElse(null);
        return new NormalizedFcf(average, asOf,
                rows.stream().map(NormalizedFcfPeriod::period).toList());
    }

    private ReadinessData readiness(InvestmentDataCalculator.PriceAssessment price, TechnicalData technical,
                                    FundamentalData fundamental, ConsensusData consensus,
                                    RevisionData revision, ValuationData valuation, StockAnalysisInput input) {
        var balanceFields = java.util.Arrays.asList(fundamental.cash(), fundamental.debt());
        var cashFreshness = fieldFreshness(fundamental.fieldProvenance(), "cash",
                fundamental.cash() != null, clock.instant());
        var debtFreshness = fieldFreshness(fundamental.fieldProvenance(), "debt",
                fundamental.debt() != null, clock.instant());
        var balanceStatus = fundamental.status() == InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                ? InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                : cashFreshness == InvestmentDataCalculator.DataStatus.STALE
                || debtFreshness == InvestmentDataCalculator.DataStatus.STALE
                ? InvestmentDataCalculator.DataStatus.STALE
                : cashFreshness == InvestmentDataCalculator.DataStatus.DATA_MISSING
                && debtFreshness == InvestmentDataCalculator.DataStatus.DATA_MISSING
                ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : cashFreshness == InvestmentDataCalculator.DataStatus.UNVERIFIED
                || debtFreshness == InvestmentDataCalculator.DataStatus.UNVERIFIED
                ? InvestmentDataCalculator.DataStatus.UNVERIFIED
                : cashFreshness != InvestmentDataCalculator.DataStatus.OK
                || debtFreshness != InvestmentDataCalculator.DataStatus.OK
                ? InvestmentDataCalculator.DataStatus.PARTIAL
                : InvestmentDataCalculator.DataStatus.OK;
        var revisionStatus = revision.status();
        var statuses = List.of(price.status(), technical.status(), fundamental.status(), consensus.status(), revisionStatus,
                valuation.status(), balanceStatus);
        var overall = overall(statuses);
        var missing = new ArrayList<String>();
        if (price.latestPrice() == null) missing.add("price.latestPrice");
        if (price.regularClose() == null) {
            missing.add("price.regularClose");
            input.observations().stream()
                    .filter(observation -> "price.regularCloseHistory".equals(observation.field())
                            && observation.provider() == StockDataProviderId.TOSS && observation.value() == null)
                    .flatMap(observation -> observation.missingData().stream())
                    .filter(reason -> reason.startsWith("TOSS_CANDLE"))
                    .map(reason -> "price.regularClose." + reason)
                    .forEach(missing::add);
        }
        if (technical.sma20() == null) missing.add("technical.sma20");
        if (technical.sma50() == null) missing.add("technical.sma50");
        if (technical.rsi14() == null) missing.add("technical.rsi14");
        for (var field : FUNDAMENTAL_FIELDS) {
            if (fundamental.value(field) == null) {
                var readinessField = "fundamentals." + field;
                missing.add(readinessField);
                input.observations().stream()
                        .filter(observation -> observation.provider() == StockDataProviderId.SEC
                                && ("fundamental." + field).equals(observation.field())
                                && observation.value() == null)
                        .flatMap(observation -> observation.missingData().stream())
                        .filter(InvestmentContextService::safeFundamentalMissingReason)
                        .map(reason -> readinessField + "." + reason)
                        .forEach(missing::add);
            }
        }
        if (fundamental.status() == InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT) {
            missing.add("fundamentals.statementPeriodConflict");
        }
        if (consensus.revenueConsensus() == null) missing.add("consensus.revenueConsensus");
        if (consensus.epsConsensus() == null) missing.add("consensus.epsConsensus");
        if (consensusCurrencyUnverified(consensus)) missing.add("consensus.currency");
        if (consensus.missingReason() != null) missing.add("consensus.provider." + consensus.missingReason());
        if (revision.revenue30().value() == null) missing.add("revision.revenueRevision30D");
        if (revision.revenue90().value() == null) missing.add("revision.revenueRevision90D");
        if (revision.eps30().value() == null) missing.add("revision.epsRevision30D");
        if (revision.eps90().value() == null) missing.add("revision.epsRevision90D");
        if (balanceFields.get(0) == null) missing.add("fundamentals.cash");
        if (balanceFields.get(1) == null) missing.add("fundamentals.debt");
        if (fundamental.basicShares() == null) missing.add("fundamentals.basicShares");
        if (fundamental.dilutedShares() == null) missing.add("fundamentals.dilutedShares");
        if ("TOSS".equals(price.source()) && price.session() == null) {
            input.observations().stream()
                    .filter(observation -> "price.session".equals(observation.field())
                            && observation.provider() == StockDataProviderId.TOSS && observation.value() == null)
                    .flatMap(observation -> observation.missingData().stream())
                    .filter(reason -> reason.startsWith("TOSS_"))
                    .findFirst()
                    .ifPresent(reason -> {
                        missing.add("price.session");
                        missing.add("price.session." + reason);
                    });
        }
        return new ReadinessData(price.status(), technical.status(), fundamental.status(), consensus.status(),
                revisionStatus, valuation.status(), balanceStatus, overall,
                List.copyOf(new LinkedHashSet<>(missing)));
    }

    private static InvestmentDataCalculator.DataStatus overall(
            List<InvestmentDataCalculator.DataStatus> statuses) {
        if (statuses.contains(InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT))
            return InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT;
        if (statuses.contains(InvestmentDataCalculator.DataStatus.STALE))
            return InvestmentDataCalculator.DataStatus.STALE;
        if (statuses.contains(InvestmentDataCalculator.DataStatus.NOT_CONFIGURED))
            return InvestmentDataCalculator.DataStatus.PARTIAL;
        if (statuses.stream().allMatch(status -> status == InvestmentDataCalculator.DataStatus.DATA_MISSING
                || status == InvestmentDataCalculator.DataStatus.NOT_APPLICABLE))
            return InvestmentDataCalculator.DataStatus.DATA_MISSING;
        if (statuses.contains(InvestmentDataCalculator.DataStatus.UNVERIFIED))
            return InvestmentDataCalculator.DataStatus.UNVERIFIED;
        if (statuses.contains(InvestmentDataCalculator.DataStatus.INSUFFICIENT_HISTORY))
            return InvestmentDataCalculator.DataStatus.PARTIAL;
        if (statuses.contains(InvestmentDataCalculator.DataStatus.PARTIAL)
                || statuses.contains(InvestmentDataCalculator.DataStatus.DATA_MISSING))
            return InvestmentDataCalculator.DataStatus.PARTIAL;
        return InvestmentDataCalculator.DataStatus.OK;
    }

    private static boolean safeFundamentalMissingReason(String reason) {
        return "DATA_NOT_PRESENT".equals(reason) || reason != null && (reason.matches("INLINE_XBRL_HTTP_[1-5][0-9]{2}")
                || Set.of("INLINE_XBRL_PARSE_FAILED", "INLINE_XBRL_REFERENCE_REJECTED",
                "INLINE_XBRL_RESPONSE_TOO_LARGE", "INLINE_XBRL_NO_VERIFIED_FACTS",
                "INLINE_XBRL_NETWORK_ERROR", "INLINE_XBRL_EMPTY_RESPONSE", "INLINE_XBRL_INTERRUPTED",
                "INLINE_XBRL_FETCH_ERROR", "INLINE_XBRL_FETCH_FAILED",
                "PARSE_FAILED", "REFERENCE_REJECTED").contains(reason));
    }

    private static InvestmentDataCalculator.DataStatus dataStatus(String value) {
        if (value == null) return InvestmentDataCalculator.DataStatus.DATA_MISSING;
        try {
            return InvestmentDataCalculator.DataStatus.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return InvestmentDataCalculator.DataStatus.UNVERIFIED;
        }
    }

    private Map<String, BigDecimal> portfolioWeights(PortfolioView portfolio) {
        var result = new LinkedHashMap<String, BigDecimal>();
        portfolio.positions().forEach(position -> result.put(position.ticker(), position.weight()));
        return result;
    }

    private Map<String, RiskContributionView> riskContributions(
            UUID userId,
            Set<String> symbols,
            Map<String, PositionView> positions,
            Map<String, BigDecimal> weights,
            PortfolioView portfolio,
            Map<String, ThesisView> theses,
            Map<String, JsonNode> analysis
    ) {
        var portfolioDataStatus = dataStatus(portfolio.status());
        var portfolioNumbersAvailable = portfolio.riskNumbersAvailable();
        var portfolioCurrent = portfolioDataStatus == InvestmentDataCalculator.DataStatus.OK;
        var exposures = new ArrayList<RiskExposure>();
        var risks = new LinkedHashMap<String, RiskContributionView>();
        for (var symbol : symbols) {
            var thesis = theses.get(symbol);
            var position = positions.get(symbol);
            var priceNode = node(analysis.get(symbol), "price");
            var price = decimal(priceNode.get("latestPrice"));
            if (price == null && position != null) price = position.lastPrice();
            var priceAsOf = instant(priceNode.get("latestPriceAsOf"));
            var weight = portfolioNumbersAvailable ? weights.get(symbol) : null;
            var triggerPrice = thesis == null ? null : thesis.priceRiskTriggerPrice();
            var priceStatus = dataStatus(text(priceNode.get("status")));
            var trustedPriceStatus = priceStatus == InvestmentDataCalculator.DataStatus.OK
                    && (priceAsOf == null || price == null || price.signum() <= 0)
                    ? InvestmentDataCalculator.DataStatus.DATA_MISSING : priceStatus;
            var trustedInputs = portfolioNumbersAvailable
                    && trustedPriceStatus == InvestmentDataCalculator.DataStatus.OK
                    && priceAsOf != null && price != null && price.signum() > 0;
            var downside = trustedInputs
                    ? InvestmentDataCalculator.invalidationDownside(price, triggerPrice) : null;
            var loss = trustedInputs ? InvestmentDataCalculator.plannedLossContribution(weight, downside) : null;
            var riskStatus = triggerPrice == null
                    ? InvestmentDataCalculator.DataStatus.NOT_CONFIGURED
                    : trustedInputs
                    ? loss == null ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                    : portfolioCurrent ? InvestmentDataCalculator.DataStatus.OK : portfolioDataStatus
                    : overall(List.of(portfolioDataStatus, trustedPriceStatus));
            var eligible = thesis != null && "CONFIRMED".equals(thesis.invalidationStatus())
                    && portfolioCurrent && trustedInputs && weight != null && downside != null && loss != null;
            if (position != null) exposures.add(new RiskExposure(symbol, loss, riskStatus));
            risks.put(symbol, new RiskContributionView(weight, downside, loss,
                    riskStatus,
                    null, InvestmentDataCalculator.DataStatus.DATA_MISSING, null, null, null,
                    InvestmentDataCalculator.DataStatus.DATA_MISSING, eligible,
                    riskBudgetStatus(null, null, null)));
        }
        var held = exposures.stream().filter(item -> positions.containsKey(item.ticker())).toList();
        var thesisFailureStatus = held.isEmpty() ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : overall(held.stream().map(RiskExposure::status).toList());
        var completeExposureLosses = !held.isEmpty() && held.stream().allMatch(item -> item.loss() != null);
        var thesisFailureStress = completeExposureLosses
                && (thesisFailureStatus == InvestmentDataCalculator.DataStatus.OK
                || thesisFailureStatus == InvestmentDataCalculator.DataStatus.STALE)
                ? held.stream().map(RiskExposure::loss).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        var top2 = top2Correlated(userId, held);
        var budget = riskPolicies.current(userId).softRiskBudget();
        var budgetStatus = riskBudgetStatus(budget, thesisFailureStress, thesisFailureStatus);
        risks.replaceAll((symbol, risk) -> new RiskContributionView(
                risk.portfolioWeight(), risk.invalidationDownside(), risk.plannedLossContribution(),
                risk.status(), thesisFailureStress, thesisFailureStatus, top2.stress(), top2.assets(),
                top2.correlation(), top2.status(), risk.sizingEligible(), budgetStatus));
        return Map.copyOf(risks);
    }

    private Top2Stress top2Correlated(UUID userId, List<RiskExposure> exposures) {
        var inputStatus = exposures.isEmpty() ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : overall(exposures.stream().map(RiskExposure::status).toList());
        if (inputStatus != InvestmentDataCalculator.DataStatus.OK
                && inputStatus != InvestmentDataCalculator.DataStatus.STALE) return Top2Stress.missing(inputStatus);
        if (exposures.stream().anyMatch(item -> item.loss() == null)) return Top2Stress.missing(inputStatus);
        var known = exposures;
        Top2Stress best = Top2Stress.missing();
        for (int leftIndex = 0; leftIndex < known.size(); leftIndex++) {
            var left = known.get(leftIndex);
            var leftCloses = dailyCloses(userId, left.ticker());
            for (int rightIndex = leftIndex + 1; rightIndex < known.size(); rightIndex++) {
                var right = known.get(rightIndex);
                var rightCloses = dailyCloses(userId, right.ticker());
                var returns = pairedReturns(leftCloses, rightCloses);
                if (returns.left().size() < 30) continue;
                var correlation = InvestmentDataCalculator.correlation(returns.left(), returns.right());
                if (correlation == null || correlation.signum() <= 0) continue;
                if (best.correlation() == null || correlation.compareTo(best.correlation()) > 0) {
                    best = new Top2Stress(left.ticker() + "," + right.ticker(), correlation,
                            left.loss().add(right.loss()), inputStatus);
                }
            }
        }
        return best;
    }

    private Map<LocalDate, BigDecimal> dailyCloses(UUID userId, String ticker) {
        var rows = jdbc.query("""
                SELECT regular_close, regular_close_as_of
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = ? AND regular_close IS NOT NULL
                 ORDER BY regular_close_as_of DESC, source
                 LIMIT 1000
                """, (resultSet, rowNum) -> new PriceBar(
                resultSet.getBigDecimal("regular_close"),
                instant(resultSet.getObject("regular_close_as_of", OffsetDateTime.class))), userId, ticker);
        var closes = new LinkedHashMap<LocalDate, BigDecimal>();
        rows.forEach(row -> closes.putIfAbsent(row.asOf().atZone(ZoneOffset.UTC).toLocalDate(), row.close()));
        return Map.copyOf(closes);
    }

    private static PairedReturns pairedReturns(Map<LocalDate, BigDecimal> left, Map<LocalDate, BigDecimal> right) {
        var dates = left.keySet().stream().filter(right::containsKey).sorted().toList();
        var leftReturns = new ArrayList<BigDecimal>();
        var rightReturns = new ArrayList<BigDecimal>();
        for (var index = 1; index < dates.size(); index++) {
            var previous = dates.get(index - 1);
            var current = dates.get(index);
            var leftPrevious = left.get(previous);
            var rightPrevious = right.get(previous);
            if (leftPrevious == null || rightPrevious == null || leftPrevious.signum() <= 0 || rightPrevious.signum() <= 0)
                continue;
            leftReturns.add(left.get(current).subtract(leftPrevious).divide(leftPrevious, MathContext.DECIMAL128));
            rightReturns.add(right.get(current).subtract(rightPrevious).divide(rightPrevious, MathContext.DECIMAL128));
        }
        return new PairedReturns(leftReturns, rightReturns);
    }

    private static String riskBudgetStatus(BigDecimal budget, BigDecimal stress,
                                           InvestmentDataCalculator.DataStatus stressStatus) {
        if (budget == null) return "NOT_CONFIGURED";
        if (stress == null || stressStatus != InvestmentDataCalculator.DataStatus.OK) return "DATA_MISSING";
        return stress.compareTo(budget) <= 0 ? "WITHIN_SOFT_BUDGET" : "OVER_SOFT_BUDGET";
    }

    private JsonNode riskCheck(RiskPolicyService.RiskPolicySnapshot policy, RiskContributionView risk) {
        var check = map(
                "policyVersion", policy.version(),
                "softRiskBudget", policy.softRiskBudget(),
                "plannedLossContribution", risk == null ? null : risk.plannedLossContribution(),
                "thesisFailureStress", risk == null ? null : risk.thesisFailureStress(),
                "top2CorrelatedStress", risk == null ? null : risk.top2CorrelatedStress(),
                "status", risk == null ? "DATA_MISSING" : risk.softBudgetStatus());
        return objectMapper.valueToTree(check);
    }

    private static boolean sameDecision(DecisionView existing, DecisionInput input) {
        return existing.asOf().equals(input.asOf()) && existing.asset().equals(input.asset())
                && existing.action().equals(input.action())
                && existing.referencePrice() != null
                && existing.referencePrice().compareTo(input.referencePrice()) == 0
                && Objects.equals(existing.priceSession(), input.priceSession())
                && existing.horizon().equals(input.horizon())
                && existing.alphaThesis().equals(input.alphaThesis())
                && existing.invalidation().equals(input.invalidation())
                && existing.nextReviewTrigger().equals(input.nextReviewTrigger())
                && existing.confidence().compareTo(input.confidence()) == 0;
    }

    private static void validateThesis(ThesisInput input) {
        if (input == null || blank(input.coreThesis()) || input.coreThesis().length() > 5000
                || input.invalidationStatus() == null
                || !Set.of("NOT_REVIEWED", "SUSPECTED", "CONFIRMED", "CLEARED")
                .contains(input.invalidationStatus().trim().toUpperCase(Locale.ROOT))
                || input.priceRiskTriggerPrice() != null && input.priceRiskTriggerPrice().signum() < 0
                || tooLong(input.upsideDriver(), 5000) || tooLong(input.expectationsGap(), 5000)
                || tooLong(input.fundamentalInvalidation(), 5000) || tooLong(input.revisionInvalidation(), 5000)
                || tooLong(input.priceRiskTrigger(), 5000) || tooLong(input.expandTrigger(), 5000)
                || tooLong(input.exitOrDiscardTrigger(), 5000) || tooLong(input.classification(), 80)) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
    }

    private DecisionInput normalizeDecision(DecisionInput input) {
        if (input == null || input.decisionId() == null || input.asOf() == null
                || input.asOf().isAfter(clock.instant()) || input.referencePrice() == null
                || input.referencePrice().signum() <= 0 || input.confidence() == null
                || input.confidence().signum() < 0 || input.confidence().compareTo(BigDecimal.ONE) > 0
                || blank(input.horizon()) || input.horizon().length() > 80
                || blank(input.alphaThesis()) || blank(input.invalidation()) || blank(input.nextReviewTrigger())) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        var action = input.action() == null ? "" : input.action().trim().toUpperCase(Locale.ROOT);
        var session = normalizeSession(input.priceSession());
        if (!Set.of("ADD", "HOLD", "REDUCE", "EXIT", "REPLACE").contains(action) || session == null) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        var referencePrice = input.referencePrice().setScale(8, RoundingMode.HALF_UP);
        if (referencePrice.signum() <= 0 || referencePrice.precision() - referencePrice.scale() > 16) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        return new DecisionInput(input.decisionId(), input.asOf().truncatedTo(ChronoUnit.MICROS),
                ticker(input.asset()), action, referencePrice, session, input.horizon().trim(),
                input.alphaThesis().trim(), input.invalidation().trim(), input.nextReviewTrigger().trim(),
                input.confidence().setScale(6, RoundingMode.HALF_UP));
    }

    private void validateDecision(DecisionInput input) {
        normalizeDecision(input);
    }

    private static BigDecimal averageTail(List<BigDecimal> values, int count) {
        if (values.size() < count) return null;
        return values.subList(values.size() - count, values.size()).stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(count), MathContext.DECIMAL128)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal rsi14(List<BigDecimal> closes) {
        if (closes.size() < 15) return null;
        var gains = BigDecimal.ZERO;
        var losses = BigDecimal.ZERO;
        for (int index = closes.size() - 14; index < closes.size(); index++) {
            var change = closes.get(index).subtract(closes.get(index - 1));
            if (change.signum() > 0) gains = gains.add(change);
            else losses = losses.add(change.abs());
        }
        if (losses.signum() == 0) return gains.signum() == 0 ? new BigDecimal("50.0000") : new BigDecimal("100.0000");
        var relativeStrength = gains.divide(losses, MathContext.DECIMAL128);
        return BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100)
                .divide(BigDecimal.ONE.add(relativeStrength), MathContext.DECIMAL128))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal multiple(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0)
            return null;
        return numerator.divide(denominator, MathContext.DECIMAL128).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) return null;
        return numerator.divide(denominator, MathContext.DECIMAL128).setScale(8, RoundingMode.HALF_UP);
    }

    private static String normalizeClassification(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace('/', '_').replace(' ', '_')) {
            case "GROWTH" -> "GROWTH";
            case "CYCLICAL", "CYCLIC" -> "CYCLICAL";
            case "COMPOUNDER" -> "COMPOUNDER";
            case "POWER", "UTILITY", "POWER_UTILITY", "POWER_AND_UTILITY" -> "POWER_UTILITY";
            default -> null;
        };
    }

    private static Map<String, Object> map(Object... fields) {
        if (fields.length % 2 != 0) throw new IllegalArgumentException("map fields must be key/value pairs");
        var result = new LinkedHashMap<String, Object>();
        for (var index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }

    private JsonNode decode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored investment snapshot is invalid", exception);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("investment data serialization unavailable", exception);
        }
    }

    private static JsonNode node(JsonNode parent, String field) {
        if (parent == null || parent.get(field) == null || parent.get(field).isNull()) {
            return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        return parent.get(field);
    }

    private static StockAnalysisInput.Observation observation(
            Map<String, StockAnalysisInput.Observation> values, String field) {
        return values == null ? null : values.get(field);
    }

    private static JsonNode value(Map<String, StockAnalysisInput.Observation> values, String field) {
        var observation = observation(values, field);
        return observation == null ? null : observation.value();
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer nonNegativeInteger(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            var value = node.isNumber() ? node.intValue() : Integer.parseInt(node.asText().trim());
            return value < 0 ? null : value;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        var value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(JsonNode node) {
        var value = text(node);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (RuntimeException ignoredDate) {
                return null;
            }
        }
    }

    private static LocalDate localDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Instant latestAsOf(Map<String, StockAnalysisInput.Observation> values, String prefix) {
        return values.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).map(StockAnalysisInput.Observation::asOf)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private static Instant latestAsOf(InvestmentDataCalculator.PriceAssessment price, TechnicalData technical,
                                      FundamentalData fundamental, ConsensusData consensus, Instant fallback) {
        return java.util.stream.Stream.of(price.latestPriceAsOf(), price.regularCloseAsOf(), technical.asOf(),
                        fundamental.asOf(), consensus.asOf(), fallback)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(fallback);
    }

    private static Instant latestInstant(Instant first, Instant second) {
        return java.util.stream.Stream.of(first, second).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        if (value == null) return null;
        return value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime timestampOrNull(Instant value) {
        return value == null ? null : timestamp(value);
    }

    private static String normalizeSession(String value) {
        if (value == null) return null;
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        return PRICE_SESSIONS.contains(normalized) ? normalized : null;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static BigDecimal add(BigDecimal left, BigDecimal right) {
        return left == null ? right : right == null ? left : left.add(right);
    }

    private static String ticker(String value) {
        if (value == null || !TICKER.matcher(value.trim().toUpperCase(Locale.ROOT)).matches()) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void requireUser(UUID userId) {
        if (userId == null || jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)",
                Boolean.class, userId) != Boolean.TRUE) {
            throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        }
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || !value.isPositive()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String safeError(RuntimeException exception) {
        return exception.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private static WatchlistView watchlistView(MonitoringWatchlistService.WatchlistEntry entry) {
        return new WatchlistView(entry.id(), entry.symbol(), entry.status(),
                entry.levels(), entry.evidence(), entry.observedAt(), entry.createdAt(), entry.updatedAt());
    }

    public record ContextView(
            PortfolioView portfolio,
            List<SecurityView> securities,
            List<WatchlistView> watchlist,
            RiskPolicyService.RiskPolicySnapshot riskPolicy,
            List<DecisionView> decisionLedger,
            PipelineView pipeline,
            TacticalOverlayPortfolioView tacticalOverlay,
            Map<UUID, DecisionTacticalOverlayView> decisionOverlays
    ) {
        public ContextView(PortfolioView portfolio, List<SecurityView> securities,
                           List<WatchlistView> watchlist, RiskPolicyService.RiskPolicySnapshot riskPolicy,
                           List<DecisionView> decisionLedger, PipelineView pipeline) {
            this(portfolio, securities, watchlist, riskPolicy, decisionLedger, pipeline,
                    TacticalOverlayPortfolioView.notConfigured(), Map.of());
        }

        public ContextView {
            tacticalOverlay = tacticalOverlay == null ? TacticalOverlayPortfolioView.notConfigured() : tacticalOverlay;
            decisionOverlays = decisionOverlays == null ? Map.of() : Map.copyOf(decisionOverlays);
        }
    }

    public record PortfolioView(
            Instant asOf, List<PositionView> positions, Map<String, BigDecimal> totalMarketValueByCurrency,
            boolean stale, List<String> missingFields, String status,
            String source, Instant account1AsOf, LocalDate manualAsOf, Instant manualReadAt,
            String manualStatus, String snapshotStatus, boolean manualStale, boolean riskNumbersAvailable
    ) {
        public PortfolioView(Instant asOf, List<PositionView> positions,
                             Map<String, BigDecimal> totalMarketValueByCurrency, boolean stale,
                             List<String> missingFields, String status) {
            this(asOf, positions, totalMarketValueByCurrency, stale, missingFields, status,
                    null, asOf, null, null, null, null, false,
                    "OK".equals(status));
        }
    }

    public record PositionView(
            String ticker, String name, BigDecimal quantity, String currency, BigDecimal marketValue,
            BigDecimal weight, BigDecimal lastPrice, Instant asOf, String accountsIncluded,
            String sourceCoverage, Instant quantityAsOf, Instant priceAsOf, LocalDate manualAsOf
    ) {
        public PositionView(String ticker, String name, BigDecimal quantity, String currency,
                            BigDecimal marketValue, BigDecimal weight, BigDecimal lastPrice, Instant asOf) {
            this(ticker, name, quantity, currency, marketValue, weight, lastPrice, asOf,
                    null, null, asOf, asOf, null);
        }
    }

    public record SecurityView(
            String ticker,
            PositionView position,
            Instant asOf,
            JsonNode price,
            JsonNode technical,
            JsonNode fundamentals,
            JsonNode consensus,
            JsonNode revision,
            JsonNode valuation,
            JsonNode readiness,
            ThesisView thesis,
            RiskContributionView risk,
            SecurityTacticalOverlayView tacticalOverlay
    ) {
        public SecurityView(String ticker, PositionView position, Instant asOf, JsonNode price, JsonNode technical,
                            JsonNode fundamentals, JsonNode consensus, JsonNode revision, JsonNode valuation,
                            JsonNode readiness, ThesisView thesis, RiskContributionView risk) {
            this(ticker, position, asOf, price, technical, fundamentals, consensus, revision, valuation,
                    readiness, thesis, risk, SecurityTacticalOverlayView.notConfigured());
        }

        public SecurityView {
            tacticalOverlay = tacticalOverlay == null ? SecurityTacticalOverlayView.notConfigured() : tacticalOverlay;
        }
    }

    public record TacticalOverlayPortfolioView(
            String status, LocalDate asOf, String source, Instant sourceAsOf, JsonNode market, JsonNode themes
    ) {
        public static TacticalOverlayPortfolioView notConfigured() {
            return new TacticalOverlayPortfolioView("NOT_CONFIGURED", null, null, null, null, null);
        }
    }

    public record SecurityTacticalOverlayView(
            String status, String reason, String themeId, String trendStage, String entrySetup,
            BigDecimal initialRiskPrice, String overlayEffect, String source, Instant sourceAsOf,
            LocalDate asOf, String overlayVersion, JsonNode indicators, JsonNode events,
            JsonNode cohorts, JsonNode anchoredVwaps, JsonNode performance
    ) {
        public JsonNode getDailyAvwaps() {
            return anchoredVwaps;
        }

        public static SecurityTacticalOverlayView notConfigured() {
            return new SecurityTacticalOverlayView("NOT_CONFIGURED", "TACTICAL_INPUTS_NOT_CONFIGURED",
                    null, null, null, null, null, null, null, null, "TACTICAL_V1",
                    null, null, null, null, null);
        }
    }

    public record DecisionTacticalOverlayView(
            UUID decisionId, String status, String entrySetup, BigDecimal initialRiskPrice,
            String overlayEffect, String source, Instant sourceAsOf, String overlayVersion, JsonNode performance
    ) {
    }

    public record WatchlistView(
            UUID id, String symbol, String status, MonitoringWatchlistService.WatchlistLevels levels,
            Map<String, Object> evidence, Instant observedAt, Instant createdAt, Instant updatedAt
    ) {
    }

    public record ThesisInput(
            String coreThesis, String upsideDriver, String expectationsGap,
            String fundamentalInvalidation, String revisionInvalidation, String priceRiskTrigger,
            BigDecimal priceRiskTriggerPrice, String invalidationStatus, String expandTrigger,
            String exitOrDiscardTrigger, String classification
    ) {
    }

    public record ThesisView(
            String ticker, String coreThesis, String upsideDriver, String expectationsGap,
            String fundamentalInvalidation, String revisionInvalidation, String priceRiskTrigger,
            BigDecimal priceRiskTriggerPrice, String invalidationStatus, String expandTrigger,
            String exitOrDiscardTrigger, String classification, Instant updatedAt
    ) {
    }

    public record DecisionInput(
            UUID decisionId, Instant asOf, String asset, String action, BigDecimal referencePrice,
            String priceSession, String horizon, String alphaThesis, String invalidation,
            String nextReviewTrigger, BigDecimal confidence
    ) {
    }

    public record DecisionView(
            UUID decisionId, Instant asOf, String asset, String action, BigDecimal referencePrice,
            String priceSession, String horizon, String alphaThesis, String invalidation,
            String nextReviewTrigger, BigDecimal confidence, JsonNode riskPolicyCheck, Instant createdAt
    ) {
    }

    public record RiskContributionView(
            BigDecimal portfolioWeight, BigDecimal invalidationDownside, BigDecimal plannedLossContribution,
            InvestmentDataCalculator.DataStatus status, BigDecimal thesisFailureStress,
            InvestmentDataCalculator.DataStatus thesisFailureStressStatus, BigDecimal top2CorrelatedStress,
            String top2CorrelatedAssets, BigDecimal top2Correlation,
            InvestmentDataCalculator.DataStatus top2CorrelatedStatus, boolean sizingEligible,
            String softBudgetStatus
    ) {
    }

    public record PipelineView(String status, Instant lastAttemptAt, Instant lastSuccessAt, String lastError) {
    }

    private record FundamentalData(
            String fiscalPeriod, String fiscalYear, String fiscalPeriodCode,
            Instant reportedAt, Instant asOf, String source,
            BigDecimal marketCap, Instant marketCapAsOf,
            BigDecimal enterpriseValue, Instant enterpriseValueAsOf, String enterpriseValueSource,
            BigDecimal cash, BigDecimal debt, BigDecimal basicShares, String basicSharesBasis,
            String marketCapFormula, BigDecimal fullyDilutedMarketCap, Instant fullyDilutedMarketCapAsOf,
            String fullyDilutedMarketCapFormula, Instant balanceSheetAsOf, String currency,
            BigDecimal dilutedShares, String dilutedSharesBasis,
            BigDecimal revenueTTM, BigDecimal revenueGrowthYoY,
            BigDecimal ebitdaTTM, String ebitdaTTMType, String ebitdaTTMFormula, String ebitdaTTMSource,
            BigDecimal eps, BigDecimal fcfTTM, JsonNode fieldProvenance,
            InvestmentDataCalculator.DataStatus status
    ) {
        private static FundamentalData missing() {
            return new FundamentalData(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    InvestmentDataCalculator.DataStatus.DATA_MISSING);
        }

        private FundamentalData withStatus(InvestmentDataCalculator.DataStatus status) {
            return new FundamentalData(fiscalPeriod, fiscalYear, fiscalPeriodCode, reportedAt, asOf, source,
                    marketCap, marketCapAsOf, enterpriseValue, enterpriseValueAsOf, enterpriseValueSource,
                    cash, debt, basicShares, basicSharesBasis, marketCapFormula,
                    fullyDilutedMarketCap, fullyDilutedMarketCapAsOf, fullyDilutedMarketCapFormula,
                    balanceSheetAsOf, currency, dilutedShares, dilutedSharesBasis,
                    revenueTTM, revenueGrowthYoY, ebitdaTTM, ebitdaTTMType, ebitdaTTMFormula,
                    ebitdaTTMSource, eps, fcfTTM, fieldProvenance, status);
        }

        private BigDecimal value(String field) {
            return switch (field) {
                case "marketCap" -> marketCap;
                case "enterpriseValue" -> enterpriseValue;
                case "cash" -> cash;
                case "debt" -> debt;
                case "basicShares" -> basicShares;
                case "dilutedShares" -> dilutedShares;
                case "fullyDilutedMarketCap" -> fullyDilutedMarketCap;
                case "revenueTTM" -> revenueTTM;
                case "revenueGrowthYoY" -> revenueGrowthYoY;
                case "ebitdaTTM" -> ebitdaTTM;
                case "eps" -> eps;
                case "fcfTTM" -> fcfTTM;
                default -> null;
            };
        }

        private Map<String, Object> view() {
            var data = map("fiscalPeriod", fiscalPeriod, "fiscalYear", fiscalYear,
                    "fiscalPeriodCode", fiscalPeriodCode, "reportedAt", reportedAt,
                    "asOf", asOf, "source", source, "marketCap", marketCap,
                    "marketCapAsOf", marketCapAsOf, "marketCapFormula", marketCapFormula,
                    "enterpriseValue", enterpriseValue,
                    "enterpriseValueAsOf", enterpriseValueAsOf, "enterpriseValueSource", enterpriseValueSource,
                    "cash", cash, "debt", debt, "balanceSheetAsOf", balanceSheetAsOf,
                    "currency", currency, "basicShares", basicShares, "basicSharesBasis", basicSharesBasis,
                    "dilutedShares", dilutedShares, "dilutedSharesBasis", dilutedSharesBasis,
                    "fullyDilutedMarketCap", fullyDilutedMarketCap,
                    "fullyDilutedMarketCapAsOf", fullyDilutedMarketCapAsOf,
                    "fullyDilutedMarketCapFormula", fullyDilutedMarketCapFormula,
                    "revenueTTM", revenueTTM,
                    "revenueGrowthYoY", revenueGrowthYoY, "ebitdaTTM", ebitdaTTM,
                    "ebitdaTTMType", ebitdaTTMType, "ebitdaTTMFormula", ebitdaTTMFormula,
                    "ebitdaTTMSource", ebitdaTTMSource, "eps", eps, "fcfTTM", fcfTTM,
                    "fieldProvenance", fieldProvenance, "status", status.name());
            return data;
        }
    }

    private record ConsensusData(
            Instant asOf, String horizon, String source, String estimateType, String estimateLabel,
            LocalDate periodEnd, BigDecimal revenueConsensus, BigDecimal epsConsensus,
            BigDecimal ebitdaConsensus, BigDecimal fcfConsensus, Integer epsAnalystCount,
            Integer revenueAnalystCount, String currency, List<Map<String, Object>> observations,
            String missingReason,
            InvestmentDataCalculator.DataStatus status
    ) {
        private static ConsensusData missing() {
            return new ConsensusData(null, null, null, null, null, null, null, null, null,
                    null, null, null, null, List.of(), null,
                    InvestmentDataCalculator.DataStatus.DATA_MISSING);
        }

        private ConsensusData withStatus(InvestmentDataCalculator.DataStatus status) {
            return new ConsensusData(asOf, horizon, source, estimateType, estimateLabel, periodEnd,
                    revenueConsensus, epsConsensus, ebitdaConsensus, fcfConsensus,
                    epsAnalystCount, revenueAnalystCount, currency, observations, missingReason, status);
        }

        private ConsensusData withMissingReason(String reason) {
            return new ConsensusData(asOf, horizon, source, estimateType, estimateLabel, periodEnd,
                    revenueConsensus, epsConsensus, ebitdaConsensus, fcfConsensus,
                    epsAnalystCount, revenueAnalystCount, currency, observations, reason, status);
        }

        private Map<String, Object> view() {
            return map("asOf", asOf, "horizon", horizon, "source", source,
                    "estimateType", estimateType, "estimateLabel", estimateLabel,
                    "periodEnd", periodEnd, "currency", currency,
                    "missingReason", missingReason,
                    "epsAnalystCount", epsAnalystCount, "revenueAnalystCount", revenueAnalystCount,
                    "revenueConsensus", revenueConsensus, "epsConsensus", epsConsensus,
                    "ebitdaConsensus", ebitdaConsensus, "fcfConsensus", fcfConsensus,
                    "observations", observations, "status", status.name());
        }
    }

    private record RevisionData(
            InvestmentDataCalculator.RevisionResult revenue30,
            InvestmentDataCalculator.RevisionResult revenue90,
            InvestmentDataCalculator.RevisionResult eps30,
            InvestmentDataCalculator.RevisionResult eps90,
            InvestmentDataCalculator.DataStatus status
    ) {
        private static RevisionData missing() {
            var missing = new InvestmentDataCalculator.RevisionResult(null, null,
                    InvestmentDataCalculator.DataStatus.DATA_MISSING);
            return new RevisionData(missing, missing, missing, missing,
                    InvestmentDataCalculator.DataStatus.DATA_MISSING);
        }

        private Map<String, Object> view() {
            return map("revenueRevision30D", revisionView(revenue30),
                    "revenueRevision90D", revisionView(revenue90), "epsRevision30D", revisionView(eps30),
                    "epsRevision90D", revisionView(eps90), "status", status.name());
        }

        private static Map<String, Object> revisionView(InvestmentDataCalculator.RevisionResult result) {
            return map("value", result.value(), "baselineAsOf", result.baselineAsOf(),
                    "status", result.status().name(), "reason", result.reason());
        }
    }

    private record TechnicalData(
            BigDecimal currentPrice, BigDecimal sma20, BigDecimal sma50, BigDecimal rsi14,
            int dailyObservations, Instant asOf, InvestmentDataCalculator.DataStatus status
    ) {
        private Map<String, Object> view() {
            return map("currentPrice", currentPrice, "sma20", sma20, "sma50", sma50,
                    "rsi14", rsi14, "dailyObservations", dailyObservations, "asOf", asOf,
                    "trendStatus", status.name());
        }
    }

    private record ValuationData(
            String classification, BigDecimal evSalesTTM, BigDecimal evSalesForward,
            BigDecimal evEbitdaTTM, BigDecimal evEbitdaForward, BigDecimal forwardPE,
            BigDecimal fcfYieldTTM, BigDecimal fcfYieldForward, BigDecimal normalizedFcf,
            BigDecimal normalizedFcfYield, Instant normalizedFcfAsOf, List<String> normalizedFcfPeriods,
            Instant ttmAsOf,
            String ttmSource, Instant forwardAsOf, String forwardHorizon, String forwardSource,
            String forwardEstimateType, LocalDate forwardPeriodEnd, String forwardCurrency,
            String displayCurrency, String displayCurrencySource, Instant displayCurrencySourceAsOf,
            String displayCurrencyPeriod, String displayCurrencyStatus,
            Map<String, String> metricStatuses, Map<String, String> metricReasons,
            Map<String, Object> metricProvenance,
            InvestmentDataCalculator.DataStatus status
    ) {
        private Map<String, Object> view() {
            return map("classification", classification, "evSalesTTM", evSalesTTM,
                    "evSalesForward", evSalesForward, "evEbitdaTTM", evEbitdaTTM,
                    "evEbitdaForward", evEbitdaForward, "forwardPE", forwardPE,
                    "fcfYieldTTM", fcfYieldTTM, "fcfYieldForward", fcfYieldForward,
                    "normalizedFcf", normalizedFcf, "normalizedFcfYield", normalizedFcfYield,
                    "normalizedFcfBasis", "MEAN_OF_LATEST_3_FISCAL_PERIODS_TTM",
                    "normalizedFcfPeriods", normalizedFcfPeriods,
                    "normalizedFcfAsOf", normalizedFcfAsOf, "ttmAsOf", ttmAsOf,
                    "ttmSource", ttmSource, "forwardAsOf", forwardAsOf,
                    "forwardHorizon", forwardHorizon, "forwardSource", forwardSource,
                    "forwardEstimateType", forwardEstimateType, "forwardPeriodEnd", forwardPeriodEnd,
                    "forwardCurrency", forwardCurrency, "displayCurrency", displayCurrency,
                    "displayCurrencySource", displayCurrencySource,
                    "displayCurrencySourceAsOf", displayCurrencySourceAsOf,
                    "displayCurrencyPeriod", displayCurrencyPeriod,
                    "displayCurrencyStatus", displayCurrencyStatus,
                    "metricStatuses", metricStatuses, "metricReasons", metricReasons,
                    "metricProvenance", metricProvenance,
                    "status", status.name());
        }
    }

    private record ReadinessData(
            InvestmentDataCalculator.DataStatus priceStatus,
            InvestmentDataCalculator.DataStatus trendStatus,
            InvestmentDataCalculator.DataStatus fundamentalStatus,
            InvestmentDataCalculator.DataStatus consensusStatus,
            InvestmentDataCalculator.DataStatus revisionStatus,
            InvestmentDataCalculator.DataStatus valuationStatus,
            InvestmentDataCalculator.DataStatus balanceSheetStatus,
            InvestmentDataCalculator.DataStatus overallDataStatus,
            List<String> missingFields
    ) {
        private Map<String, Object> view() {
            return map("priceStatus", priceStatus.name(), "trendStatus", trendStatus.name(),
                    "fundamentalStatus", fundamentalStatus.name(), "consensusStatus", consensusStatus.name(),
                    "revisionStatus", revisionStatus.name(),
                    "valuationStatus", valuationStatus.name(), "balanceSheetStatus", balanceSheetStatus.name(),
                    "overallDataStatus", overallDataStatus.name(), "missingFields", missingFields);
        }
    }

    private record NormalizedFcf(BigDecimal value, Instant asOf, List<String> normalizedFcfPeriods) {
        private static NormalizedFcf missing() { return new NormalizedFcf(null, null, List.of()); }
    }

    private record NormalizedFcfPeriod(String period, BigDecimal fcf, Instant asOf) {
    }

    private record ConsensusHistory(Instant asOf, BigDecimal revenue, BigDecimal eps) {
    }

    private record PriceBar(BigDecimal close, Instant asOf) {
    }

    private record TossQuoteSource(
            UUID connectionId, Map<String, BrokerSurfaceResponse.PriceView> quotes
    ) {
    }

    private record RiskExposure(String ticker, BigDecimal loss, InvestmentDataCalculator.DataStatus status) {
    }

    private record PairedReturns(List<BigDecimal> left, List<BigDecimal> right) {
    }

    private record Top2Stress(String assets, BigDecimal correlation, BigDecimal stress,
                              InvestmentDataCalculator.DataStatus status) {
        private static Top2Stress missing() {
            return missing(InvestmentDataCalculator.DataStatus.DATA_MISSING);
        }

        private static Top2Stress missing(InvestmentDataCalculator.DataStatus status) {
            return new Top2Stress(null, null, null, status);
        }
    }
}
