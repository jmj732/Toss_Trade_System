package com.jmj.trade.investment;

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

    public ContextView context(UUID userId) {
        requireUser(userId);
        var portfolio = readPortfolio(userId);
        var watchEntries = watchlist.list(userId);
        var symbols = new LinkedHashSet<String>();
        portfolio.positions().forEach(position -> symbols.add(position.ticker()));
        watchEntries.stream().filter(entry -> !"INVALIDATED".equals(entry.status()))
                .map(MonitoringWatchlistService.WatchlistEntry::symbol).forEach(symbols::add);
        additionalSymbols().forEach(symbols::add);

        var positions = new LinkedHashMap<String, PositionView>();
        portfolio.positions().forEach(position -> positions.put(position.ticker(), position));
        var analysis = new LinkedHashMap<String, JsonNode>();
        var theses = theses(userId, symbols);
        for (var symbol : symbols) {
            analysis.put(symbol, latestSecuritySnapshot(userId, symbol));
        }
        var weights = portfolioWeights(portfolio);
        var risks = riskContributions(userId, symbols, positions, weights, portfolio.status(), theses, analysis);
        var securities = symbols.stream().sorted().map(symbol -> new SecurityView(
                symbol,
                positions.get(symbol),
                instant(analysis.get(symbol).get("asOf")),
                node(analysis.get(symbol), "price"),
                node(analysis.get(symbol), "technical"),
                node(analysis.get(symbol), "fundamentals"),
                node(analysis.get(symbol), "consensus"),
                node(analysis.get(symbol), "revision"),
                node(analysis.get(symbol), "valuation"),
                node(analysis.get(symbol), "readiness"),
                theses.get(symbol),
                risks.get(symbol))).toList();
        return new ContextView(
                portfolio,
                securities,
                watchEntries.stream().map(InvestmentContextService::watchlistView).toList(),
                riskPolicies.current(userId),
                decisionLedger(userId, 50),
                pipelineState(userId, "SECURITY_DATA"));
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
                if (selectedFields == null && !canonicalDataReady(userId, symbol, input.collectedAt())) {
                    canonicalMissing = true;
                }
                captured++;
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
        return symbols;
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
                var previous = candlesByDate.putIfAbsent(date, candle);
                if (previous != null && !sameCandle(previous, candle)) conflictingDates.add(date);
            }
            conflictingDates.forEach(candlesByDate::remove);
            if (!conflictingDates.isEmpty()) rejected.add("TOSS_CANDLE_DUPLICATE_CONFLICT");
            if (candlesByDate.isEmpty()) {
                if (rejected.isEmpty()) rejected.add("TOSS_CANDLES_EMPTY");
                rejected.forEach(reason -> observations.add(
                        tossMissing("price.regularCloseHistory", reason, collectedAt)));
                return withObservations(input, observations);
            }

            var rows = objectMapper.createArrayNode();
            candlesByDate.forEach((date, candle) -> {
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
            });
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
                .flatMap(observation -> observation.missingData().stream())
                .filter(reason -> reason.startsWith("PROVIDER_"))
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
        var currencySet = new LinkedHashSet<>(totals.keySet());
        var weighted = positions.stream().map(position -> {
            var total = currencySet.size() == 1 ? totals.get(position.currency()) : null;
            var weight = total == null || total.signum() <= 0 || position.marketValue() == null
                    ? null : position.marketValue().divide(total, MathContext.DECIMAL128);
            return new PositionView(position.ticker(), position.name(), position.quantity(),
                    position.currency(), position.marketValue(), weight, position.lastPrice(), position.asOf());
        }).toList();
        var status = missing.size() > 0 ? "PARTIAL" : stale ? "STALE" : asOf == null ? "DATA_MISSING" : "OK";
        return new PortfolioView(asOf, weighted, Map.copyOf(totals), stale, List.copyOf(missing), status);
    }

    private List<String> latestHeldSymbols(UUID userId) {
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
                    && observation.value().isArray() && onlyOuterAsOfMissing;
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
                "fundamentalStatus", "DATA_MISSING", "revisionStatus", "DATA_MISSING",
                "valuationStatus", "DATA_MISSING", "balanceSheetStatus", "DATA_MISSING",
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
        var valuationStatus = dataStatus(text(valuation.get("status")));
        var priceDependent = "COMPOUNDER".equals(text(valuation.get("classification")));
        var valuationInputsStale = fundamentalStatus == InvestmentDataCalculator.DataStatus.STALE
                || priceDependent && (priceStatus == InvestmentDataCalculator.DataStatus.STALE
                || consensusStatus == InvestmentDataCalculator.DataStatus.STALE);
        if (valuationInputsStale && valuationStatus != InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                && valuationStatus != InvestmentDataCalculator.DataStatus.DATA_MISSING
                && valuationStatus != InvestmentDataCalculator.DataStatus.NOT_APPLICABLE) {
            valuationStatus = InvestmentDataCalculator.DataStatus.STALE;
            valuation.put("status", valuationStatus.name());
        }
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
        readiness.put("revisionStatus", revisionStatus.name());
        readiness.put("valuationStatus", valuationStatus.name());
        readiness.put("overallDataStatus", overall(List.of(priceStatus, technicalStatus, fundamentalStatus,
                consensusStatus, revisionStatus, valuationStatus, balanceStatus)).name());
        return snapshot;
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
                    if (!value.startsWith("PROVIDER_")) continue;
                    var code = value.substring("PROVIDER_".length());
                    if (Set.of("DAILY_QUOTA_EXHAUSTED", "REQUEST_IN_PROGRESS", "CACHE_UNAVAILABLE",
                            "CACHE_CORRUPT", "API_ERROR", "INVALID_RESPONSE", "SOURCE_CONFLICT",
                            "SYMBOL_MISMATCH", "NETWORK", "EMPTY_RESPONSE", "INTERRUPTED").contains(code)
                            || code.matches("HTTP_[1-5][0-9]{2}")) return code;
                }
            }
        } catch (JacksonException ignored) {
            return null;
        }
        return null;
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
        if (classification == null) return ValuationData.notApplicable();
        var marketCap = fundamental.marketCap();
        var enterpriseValue = fundamental.enterpriseValue();
        var currencyUnverified = consensusCurrencyUnverified(consensus);
        var evSalesTTM = multiple(enterpriseValue, fundamental.revenueTTM());
        var evSalesForward = currencyUnverified ? null
                : multiple(enterpriseValue, consensus.revenueConsensus());
        var evEbitdaTTM = multiple(enterpriseValue, fundamental.ebitdaTTM());
        var evEbitdaForward = currencyUnverified ? null
                : multiple(enterpriseValue, consensus.ebitdaConsensus());
        var priceTrusted = price.status() == InvestmentDataCalculator.DataStatus.OK;
        var forwardPe = !currencyUnverified && priceTrusted
                ? multiple(price.latestPrice(), consensus.epsConsensus()) : null;
        var fcfYieldTTM = ratio(fundamental.fcfTTM(), marketCap);
        var fcfYieldForward = currencyUnverified ? null : ratio(consensus.fcfConsensus(), marketCap);
        var normalizedFcf = normalizedFcf(userId, ticker, fundamental.source());
        var normalizedFcfYield = ratio(normalizedFcf.value(), marketCap);
        var needed = switch (classification) {
            case "GROWTH" -> java.util.Arrays.asList(evSalesTTM);
            case "CYCLICAL" -> java.util.Arrays.asList(evEbitdaTTM, normalizedFcf.value());
            case "COMPOUNDER" -> java.util.Arrays.asList(forwardPe, fcfYieldTTM);
            case "POWER_UTILITY" -> java.util.Arrays.asList(evEbitdaTTM, fcfYieldTTM);
            default -> List.<BigDecimal>of();
        };
        var present = needed.stream().filter(Objects::nonNull).count();
        var status = needed.isEmpty() ? InvestmentDataCalculator.DataStatus.NOT_APPLICABLE
                : present == needed.size() ? InvestmentDataCalculator.DataStatus.OK
                : present == 0 ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : InvestmentDataCalculator.DataStatus.PARTIAL;
        var valuationStatuses = new ArrayList<InvestmentDataCalculator.DataStatus>();
        valuationStatuses.add(status);
        valuationStatuses.add(fundamental.status());
        if ("COMPOUNDER".equals(classification) || currencyUnverified) {
            valuationStatuses.add(consensus.status());
        }
        if ("COMPOUNDER".equals(classification)) {
            valuationStatuses.add(price.status());
        }
        status = overall(valuationStatuses);
        return new ValuationData(classification, evSalesTTM, evSalesForward, evEbitdaTTM,
                evEbitdaForward, forwardPe, fcfYieldTTM, fcfYieldForward,
                normalizedFcf.value(), normalizedFcfYield, normalizedFcf.asOf(),
                normalizedFcf.normalizedFcfPeriods(),
                fundamental.asOf(), fundamental.source(), consensus.asOf(), consensus.horizon(),
                consensus.source(), status);
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
                        .filter(InvestmentContextService::safeInlineXbrlMissingReason)
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
        return new ReadinessData(price.status(), technical.status(), fundamental.status(), revisionStatus,
                valuation.status(), balanceStatus, overall, List.copyOf(new LinkedHashSet<>(missing)));
    }

    private static InvestmentDataCalculator.DataStatus overall(
            List<InvestmentDataCalculator.DataStatus> statuses) {
        if (statuses.contains(InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT))
            return InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT;
        if (statuses.contains(InvestmentDataCalculator.DataStatus.STALE))
            return InvestmentDataCalculator.DataStatus.STALE;
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

    private static boolean safeInlineXbrlMissingReason(String reason) {
        return reason != null && (reason.matches("INLINE_XBRL_HTTP_[1-5][0-9]{2}")
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
        if ("OK".equals(portfolio.status())) {
            portfolio.positions().forEach(position -> result.put(position.ticker(), position.weight()));
        }
        return result;
    }

    private Map<String, RiskContributionView> riskContributions(
            UUID userId,
            Set<String> symbols,
            Map<String, PositionView> positions,
            Map<String, BigDecimal> weights,
            String portfolioStatus,
            Map<String, ThesisView> theses,
            Map<String, JsonNode> analysis
    ) {
        var portfolioDataStatus = dataStatus(portfolioStatus);
        var exposures = new ArrayList<RiskExposure>();
        var risks = new LinkedHashMap<String, RiskContributionView>();
        for (var symbol : symbols) {
            var thesis = theses.get(symbol);
            var position = positions.get(symbol);
            var priceNode = node(analysis.get(symbol), "price");
            var price = decimal(priceNode.get("latestPrice"));
            if (price == null && position != null) price = position.lastPrice();
            var priceAsOf = instant(priceNode.get("latestPriceAsOf"));
            var weight = weights.get(symbol);
            var triggerPrice = thesis == null ? null : thesis.priceRiskTriggerPrice();
            var priceStatus = dataStatus(text(priceNode.get("status")));
            var trustedPriceStatus = priceStatus == InvestmentDataCalculator.DataStatus.OK
                    && (priceAsOf == null || price == null || price.signum() <= 0)
                    ? InvestmentDataCalculator.DataStatus.DATA_MISSING : priceStatus;
            var trustedInputs = portfolioDataStatus == InvestmentDataCalculator.DataStatus.OK
                    && trustedPriceStatus == InvestmentDataCalculator.DataStatus.OK
                    && priceAsOf != null && price != null && price.signum() > 0;
            var downside = trustedInputs
                    ? InvestmentDataCalculator.invalidationDownside(price, triggerPrice) : null;
            var loss = trustedInputs ? InvestmentDataCalculator.plannedLossContribution(weight, downside) : null;
            var riskStatus = trustedInputs
                    ? loss == null ? InvestmentDataCalculator.DataStatus.DATA_MISSING : InvestmentDataCalculator.DataStatus.OK
                    : overall(List.of(portfolioDataStatus, trustedPriceStatus));
            var eligible = thesis != null && "CONFIRMED".equals(thesis.invalidationStatus())
                    && trustedInputs && weight != null && downside != null && loss != null;
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
        var thesisFailureStress = thesisFailureStatus == InvestmentDataCalculator.DataStatus.OK
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
        if (inputStatus != InvestmentDataCalculator.DataStatus.OK) return Top2Stress.missing(inputStatus);
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
                            left.loss().add(right.loss()), InvestmentDataCalculator.DataStatus.OK);
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
        if (numerator == null || denominator == null || numerator.signum() <= 0 || denominator.signum() <= 0)
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
            PipelineView pipeline
    ) {
    }

    public record PortfolioView(
            Instant asOf, List<PositionView> positions, Map<String, BigDecimal> totalMarketValueByCurrency,
            boolean stale, List<String> missingFields, String status
    ) {
    }

    public record PositionView(
            String ticker, String name, BigDecimal quantity, String currency, BigDecimal marketValue,
            BigDecimal weight, BigDecimal lastPrice, Instant asOf
    ) {
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
            RiskContributionView risk
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
            InvestmentDataCalculator.DataStatus status
    ) {
        private static ValuationData notApplicable() {
            return new ValuationData(null, null, null, null, null, null, null, null,
                    null, null, null, List.of(), null, null, null, null, null,
                    InvestmentDataCalculator.DataStatus.NOT_APPLICABLE);
        }

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
                    "status", status.name());
        }
    }

    private record ReadinessData(
            InvestmentDataCalculator.DataStatus priceStatus,
            InvestmentDataCalculator.DataStatus trendStatus,
            InvestmentDataCalculator.DataStatus fundamentalStatus,
            InvestmentDataCalculator.DataStatus revisionStatus,
            InvestmentDataCalculator.DataStatus valuationStatus,
            InvestmentDataCalculator.DataStatus balanceSheetStatus,
            InvestmentDataCalculator.DataStatus overallDataStatus,
            List<String> missingFields
    ) {
        private Map<String, Object> view() {
            return map("priceStatus", priceStatus.name(), "trendStatus", trendStatus.name(),
                    "fundamentalStatus", fundamentalStatus.name(), "revisionStatus", revisionStatus.name(),
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
