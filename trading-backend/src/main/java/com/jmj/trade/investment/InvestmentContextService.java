package com.jmj.trade.investment;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.analysis.StockAnalysisSnapshotHasher;
import com.jmj.trade.marketdata.StockAnalysisInput;
import com.jmj.trade.marketdata.StockAnalysisInputAssembler;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final List<String> FUNDAMENTAL_FIELDS = List.of(
            "marketCap", "enterpriseValue", "cash", "debt", "dilutedShares",
            "revenueTTM", "revenueGrowthYoY", "ebitdaTTM", "eps", "fcfTTM");
    private static final List<String> CONSENSUS_FIELDS = List.of(
            "revenueConsensus", "epsConsensus", "ebitdaConsensus", "fcfConsensus");
    private static final List<String> PRICE_SESSIONS = List.of(
            "REGULAR_CLOSE", "LIVE_REGULAR", "AFTER_HOURS", "PREMARKET");
    private static final Set<String> QUOTE_UPDATE_FIELDS = Set.of(
            "quote.price", "quote.volume", "quote.change-percent");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final StockAnalysisInputAssembler assembler;
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

    public InvestmentContextService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            StockDataProviderRegistry providers,
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
            String providerFailure = null;
            for (var symbol : symbols) {
                var input = assembler.assemble(symbol, Map.of(), selectedFields);
                transaction.execute(status -> persistCapture(userId, input, selectedFields == null));
                sourceResponded |= !input.observations().isEmpty();
                if (providerFailure == null) providerFailure = providerFailure(input);
                captured++;
            }
            var error = providerFailure != null ? providerFailure
                    : sourceResponded ? null : "NO_DATA_COLLECTED";
            if (error != null) {
                markPipeline(userId, pipeline, "FAILED", null, error);
                return captured;
            }
            markPipeline(userId, pipeline, "SUCCEEDED", clock.instant(), null);
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
                             WHERE user_id = ? AND ticker = ? AND session = 'REGULAR_CLOSE'
                               AND regular_close > 0 AND regular_close_as_of IS NOT NULL
                        )
                        """, Boolean.class, userId, ticker);
                var hasFundamental = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM fundamental_snapshots WHERE user_id = ? AND ticker = ?)
                        """, Boolean.class, userId, ticker);
                var hasConsensus = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM consensus_snapshots WHERE user_id = ? AND ticker = ?)
                        """, Boolean.class, userId, ticker);
                if (!Boolean.TRUE.equals(hasPrice) || !Boolean.TRUE.equals(hasFundamental)
                        || !Boolean.TRUE.equals(hasConsensus)) return true;
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
        return symbols;
    }

    static String providerFailure(StockAnalysisInput input) {
        var reasons = input.observations().stream()
                .flatMap(observation -> observation.missingData().stream())
                .filter(reason -> reason.startsWith("PROVIDER_"))
                .toList();
        return reasons.stream()
                .filter(reason -> reason.matches("PROVIDER_HTTP_[1-5][0-9]{2}"))
                .findFirst()
                .orElseGet(() -> reasons.stream().findFirst().orElse(null));
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
            for (var values : groups.values()) {
                fundamentalConflict |= persistFundamentals(userId, input, values, now);
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
        var readiness = readiness(price, technical, fundamental, consensus, revision, valuation, input.collectedAt());
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
        return true;
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
        var source = values.entrySet().stream().filter(entry -> entry.getKey().startsWith("fundamental."))
                .map(Map.Entry::getValue).filter(Objects::nonNull)
                .map(StockAnalysisInput.Observation::provider).findFirst().orElse(null);
        if (source == null) return false;

        var fmp = source == StockDataProviderId.FMP;
        var fiscalPeriod = text(value(values, "fundamental.fiscalPeriod"));
        var reportedAt = instant(value(values, "fundamental.reportedAt"));
        var fiscalYear = text(value(values, "fundamental.fiscalYear"));
        var fiscalPeriodCode = text(value(values, "fundamental.fiscalPeriodCode"));
        var incomePeriod = text(value(values, "fundamental.incomeFiscalPeriod"));
        var cashFlowPeriod = text(value(values, "fundamental.cashFlowFiscalPeriod"));
        var incomeReportedAt = instant(value(values, "fundamental.incomeReportedAt"));
        var cashFlowReportedAt = instant(value(values, "fundamental.cashFlowReportedAt"));

        LocalDate financialDate = null;
        if (fmp) {
            var balanceDate = localDate(fiscalPeriod);
            var incomeDate = localDate(incomePeriod);
            var cashFlowDate = localDate(cashFlowPeriod);
            if (balanceDate != null && incomeDate != null && cashFlowDate != null
                    && (!balanceDate.equals(incomeDate) || !balanceDate.equals(cashFlowDate))) {
                return true;
            }
            if (balanceDate == null || incomeDate == null || cashFlowDate == null) return false;
            financialDate = balanceDate;
            fiscalPeriod = balanceDate.toString();
            var balanceReportedAt = reportedAt;
            if (balanceReportedAt == null || incomeReportedAt == null || cashFlowReportedAt == null) return false;
            reportedAt = List.of(balanceReportedAt, incomeReportedAt, cashFlowReportedAt)
                    .stream().max(Comparator.naturalOrder()).orElse(null);
        }

        var marketCap = decimal(value(values, "fundamental.marketCap"));
        var cash = decimal(value(values, "fundamental.cash"));
        var debt = decimal(value(values, "fundamental.debt"));
        var marketCapObservation = observation(values, "fundamental.marketCap");
        var marketCapAsOf = marketCapObservation == null ? null : marketCapObservation.asOf();
        var providedEnterpriseValue = decimal(value(values, "fundamental.enterpriseValue"));
        var enterpriseValue = fmp && marketCap != null && cash != null && debt != null
                ? marketCap.add(debt).subtract(cash) : providedEnterpriseValue;
        var enterpriseValueObservation = observation(values, "fundamental.enterpriseValue");
        var enterpriseValueAsOf = fmp && enterpriseValue != null ? marketCapAsOf
                : enterpriseValueObservation == null ? null : enterpriseValueObservation.asOf();
        var enterpriseValueSource = fmp && enterpriseValue != null
                ? "FMP_MARKET_CAP_PLUS_BALANCE_SHEET"
                : enterpriseValueObservation == null ? null : enterpriseValueObservation.provider().name();
        var revenue = decimal(value(values, "fundamental.revenueTTM"));
        var revenueGrowth = fmp ? revenueGrowth(userId, input.symbol(), source.name(), fiscalPeriod,
                fiscalYear, fiscalPeriodCode, value(values, "fundamental.incomeHistory"), revenue)
                : decimal(value(values, "fundamental.revenueGrowthYoY"));
        var dilutedSharesObservation = observation(values, "fundamental.dilutedShares");
        var dilutedSharesBasis = fmp && dilutedSharesObservation != null ? "WEIGHTED_AVERAGE_TTM" : null;
        var financialAsOf = fmp && financialDate != null
                ? financialDate.atStartOfDay(ZoneOffset.UTC).toInstant() : latestAsOf(values, "fundamental.");
        var count = marketCap != null ? 1 : 0;
        count += enterpriseValue != null ? 1 : 0;
        count += cash != null ? 1 : 0;
        count += debt != null ? 1 : 0;
        count += dilutedSharesObservation != null
                && decimal(value(values, "fundamental.dilutedShares")) != null ? 1 : 0;
        count += revenue != null ? 1 : 0;
        count += revenueGrowth != null ? 1 : 0;
        count += decimal(value(values, "fundamental.ebitdaTTM")) != null ? 1 : 0;
        count += decimal(value(values, "fundamental.eps")) != null ? 1 : 0;
        count += decimal(value(values, "fundamental.fcfTTM")) != null ? 1 : 0;
        if (count == 0 || fiscalPeriod == null || reportedAt == null || financialAsOf == null) return false;

        jdbc.update("""
                INSERT INTO fundamental_snapshots (
                    id, user_id, input_snapshot_id, ticker, fiscal_period, fiscal_year, fiscal_period_code,
                    reported_at, as_of, source, market_cap, market_cap_as_of, enterprise_value,
                    enterprise_value_as_of, enterprise_value_source, cash, debt, diluted_shares,
                    diluted_shares_basis, revenue_ttm, revenue_growth_yoy, ebitda_ttm, eps, fcf_ttm, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, ticker, input_snapshot_id, source) DO NOTHING
                """, UUID.randomUUID(), userId, input.snapshotId(), input.symbol(), fiscalPeriod,
                fiscalYear, fiscalPeriodCode, timestamp(reportedAt), timestamp(financialAsOf), source.name(),
                marketCap, timestampOrNull(marketCapAsOf), enterpriseValue, timestampOrNull(enterpriseValueAsOf),
                enterpriseValueSource, cash, debt, decimal(value(values, "fundamental.dilutedShares")),
                dilutedSharesBasis, revenue, revenueGrowth,
                decimal(value(values, "fundamental.ebitdaTTM")), decimal(value(values, "fundamental.eps")),
                decimal(value(values, "fundamental.fcfTTM")), now);
        return false;
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
                    eps_consensus, ebitda_consensus, fcf_consensus, source, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, ticker, as_of, horizon, source) DO NOTHING
                """, UUID.randomUUID(), userId, input.snapshotId(), input.symbol(), timestamp(asOf), horizon,
                decimal(value(values, "consensus.revenueConsensus")),
                decimal(value(values, "consensus.epsConsensus")),
                decimal(value(values, "consensus.ebitdaConsensus")),
                decimal(value(values, "consensus.fcfConsensus")), source.name(), now);
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
            var close = observation(values, "price.regularClose");
            var session = text(value(values, "price.session"));
            var latestHistory = regularCloseBars(value(values, "price.regularCloseHistory"), session, collectedAt)
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
                   AND regular_close > 0 AND regular_close_as_of IS NOT NULL AND regular_close_as_of <= ?
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
        if (provider == null) return;
        var session = normalizeSession(text(value(values, "price.session")));
        var history = observation(values, "price.regularCloseHistory");
        for (var bar : regularCloseBars(history == null ? null : history.value(), session, input.collectedAt())) {
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

    private static List<PriceBar> regularCloseBars(JsonNode value, String session, Instant collectedAt) {
        if (!"REGULAR_CLOSE".equals(normalizeSession(session)) || value == null || !value.isArray()
                || collectedAt == null) {
            return List.of();
        }
        var closesByDate = new java.util.TreeMap<LocalDate, BigDecimal>();
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
            if (date.isAfter(latestAllowedDate)) continue;
            var previous = closesByDate.putIfAbsent(date, close);
            if (previous != null && previous.compareTo(close) != 0) conflictingDates.add(date);
        }
        conflictingDates.forEach(closesByDate::remove);
        return closesByDate.entrySet().stream()
                .map(entry -> new PriceBar(entry.getValue(), entry.getKey().atStartOfDay(ZoneOffset.UTC).toInstant()))
                .toList();
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
        var fundamentalStatus = refreshStatus(object(snapshot, "fundamentals"), "status", "asOf",
                fundamentalStaleAfter, now);
        var consensusStatus = refreshStatus(object(snapshot, "consensus"), "status", "asOf",
                consensusStaleAfter, now);
        var revision = object(snapshot, "revision");
        var revisionStatus = dataStatus(text(revision.get("status")));
        if (consensusStatus == InvestmentDataCalculator.DataStatus.STALE
                && revisionStatus != InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                && revisionStatus != InvestmentDataCalculator.DataStatus.DATA_MISSING) {
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
        if (fundamentalStatus == InvestmentDataCalculator.DataStatus.STALE
                && balanceStatus != InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                && balanceStatus != InvestmentDataCalculator.DataStatus.DATA_MISSING) {
            balanceStatus = InvestmentDataCalculator.DataStatus.STALE;
            readiness.put("balanceSheetStatus", balanceStatus.name());
        }
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
                       enterprise_value_source, cash, debt, diluted_shares, diluted_shares_basis,
                       revenue_ttm, revenue_growth_yoy, ebitda_ttm, eps, fcf_ttm
                  FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = ?
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
                resultSet.getBigDecimal("debt"), resultSet.getBigDecimal("diluted_shares"),
                resultSet.getString("diluted_shares_basis"),
                resultSet.getBigDecimal("revenue_ttm"), resultSet.getBigDecimal("revenue_growth_yoy"),
                resultSet.getBigDecimal("ebitda_ttm"), resultSet.getBigDecimal("eps"),
                resultSet.getBigDecimal("fcf_ttm"), InvestmentDataCalculator.DataStatus.OK), userId, ticker);
        var value = rows.stream().findFirst().orElse(null);
        if (value == null) return FundamentalData.missing();
        var fields = java.util.Arrays.asList(value.marketCap(), value.enterpriseValue(), value.cash(), value.debt(),
                value.dilutedShares(), value.revenueTTM(), value.revenueGrowthYoY(), value.ebitdaTTM(),
                value.eps(), value.fcfTTM());
        var present = fields.stream().filter(Objects::nonNull).count();
        var status = value.asOf().isBefore(clock.instant().minus(fundamentalStaleAfter))
                ? InvestmentDataCalculator.DataStatus.STALE
                : present == fields.size() ? InvestmentDataCalculator.DataStatus.OK
                : present == 0 ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : InvestmentDataCalculator.DataStatus.PARTIAL;
        return value.withStatus(status);
    }

    private ConsensusData latestConsensus(UUID userId, String ticker) {
        var rows = jdbc.query("""
                SELECT as_of, horizon, source, revenue_consensus, eps_consensus,
                       ebitda_consensus, fcf_consensus
                  FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = ?
                 ORDER BY as_of DESC, id DESC LIMIT 1
                """, (resultSet, rowNum) -> new ConsensusData(
                instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                resultSet.getString("horizon"), resultSet.getString("source"),
                resultSet.getBigDecimal("revenue_consensus"), resultSet.getBigDecimal("eps_consensus"),
                resultSet.getBigDecimal("ebitda_consensus"), resultSet.getBigDecimal("fcf_consensus"),
                InvestmentDataCalculator.DataStatus.OK), userId, ticker);
        var value = rows.stream().findFirst().orElseGet(ConsensusData::missing);
        if (value.asOf() == null) return value;
        var present = java.util.Arrays.asList(value.revenueConsensus(), value.epsConsensus(),
                value.ebitdaConsensus(), value.fcfConsensus()).stream().filter(Objects::nonNull).count();
        var status = value.asOf().isBefore(clock.instant().minus(consensusStaleAfter))
                ? InvestmentDataCalculator.DataStatus.STALE
                : present == CONSENSUS_FIELDS.size() ? InvestmentDataCalculator.DataStatus.OK
                : present == 0 ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : InvestmentDataCalculator.DataStatus.PARTIAL;
        return value.withStatus(status);
    }

    private RevisionData revisions(UUID userId, String ticker, ConsensusData current) {
        if (current.asOf() == null || current.source() == null || current.horizon() == null) {
            return RevisionData.missing();
        }
        var history = jdbc.query("""
                SELECT as_of, revenue_consensus, eps_consensus
                  FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = ? AND source = ? AND horizon = ?
                   AND as_of <= ? AND as_of >= ?
                 ORDER BY as_of
                """, (resultSet, rowNum) -> new ConsensusHistory(
                instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                resultSet.getBigDecimal("revenue_consensus"), resultSet.getBigDecimal("eps_consensus")),
                userId, ticker, current.source(), current.horizon(), timestamp(current.asOf()),
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
        return new RevisionData(revenue30, revenue90, eps30, eps90,
                present == 4 ? InvestmentDataCalculator.DataStatus.OK
                        : present == 0 ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                        : InvestmentDataCalculator.DataStatus.PARTIAL);
    }

    private TechnicalData technical(UUID userId, String ticker, BigDecimal latestPrice) {
        var rows = jdbc.query("""
                SELECT regular_close, regular_close_as_of
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = ? AND regular_close IS NOT NULL
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
        var evSalesTTM = multiple(enterpriseValue, fundamental.revenueTTM());
        var evSalesForward = multiple(enterpriseValue, consensus.revenueConsensus());
        var evEbitdaTTM = multiple(enterpriseValue, fundamental.ebitdaTTM());
        var evEbitdaForward = multiple(enterpriseValue, consensus.ebitdaConsensus());
        var priceTrusted = price.status() == InvestmentDataCalculator.DataStatus.OK;
        var forwardPe = priceTrusted ? multiple(price.latestPrice(), consensus.epsConsensus()) : null;
        var fcfYieldTTM = ratio(fundamental.fcfTTM(), marketCap);
        var fcfYieldForward = ratio(consensus.fcfConsensus(), marketCap);
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
        if ("COMPOUNDER".equals(classification)) {
            valuationStatuses.add(consensus.status());
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
                                    RevisionData revision, ValuationData valuation, Instant now) {
        var balanceFields = java.util.Arrays.asList(fundamental.cash(), fundamental.debt(), fundamental.dilutedShares());
        var balanceCount = balanceFields.stream().filter(Objects::nonNull).count();
        var balanceStatus = fundamental.status() == InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                ? InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT
                : fundamental.status() == InvestmentDataCalculator.DataStatus.STALE
                ? InvestmentDataCalculator.DataStatus.STALE
                : balanceCount == 3 ? InvestmentDataCalculator.DataStatus.OK
                : balanceCount == 0 ? InvestmentDataCalculator.DataStatus.DATA_MISSING
                : InvestmentDataCalculator.DataStatus.PARTIAL;
        var revisionStatus = revision.status();
        var statuses = List.of(price.status(), technical.status(), fundamental.status(), consensus.status(), revisionStatus,
                valuation.status(), balanceStatus);
        var overall = overall(statuses);
        var missing = new ArrayList<String>();
        if (price.latestPrice() == null) missing.add("price.latestPrice");
        if (technical.sma20() == null) missing.add("technical.sma20");
        if (technical.sma50() == null) missing.add("technical.sma50");
        if (technical.rsi14() == null) missing.add("technical.rsi14");
        for (var field : FUNDAMENTAL_FIELDS) {
            if (fundamental.value(field) == null) missing.add("fundamentals." + field);
        }
        if (fundamental.status() == InvestmentDataCalculator.DataStatus.SOURCE_CONFLICT) {
            missing.add("fundamentals.statementPeriodConflict");
        }
        if (consensus.revenueConsensus() == null) missing.add("consensus.revenueConsensus");
        if (consensus.epsConsensus() == null) missing.add("consensus.epsConsensus");
        if (revision.revenue30().value() == null) missing.add("revision.revenueRevision30D");
        if (revision.revenue90().value() == null) missing.add("revision.revenueRevision90D");
        if (revision.eps30().value() == null) missing.add("revision.epsRevision30D");
        if (revision.eps90().value() == null) missing.add("revision.epsRevision90D");
        if (balanceFields.get(0) == null) missing.add("fundamentals.cash");
        if (balanceFields.get(1) == null) missing.add("fundamentals.debt");
        if (balanceFields.get(2) == null) missing.add("fundamentals.dilutedShares");
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
        if (statuses.contains(InvestmentDataCalculator.DataStatus.PARTIAL)
                || statuses.contains(InvestmentDataCalculator.DataStatus.DATA_MISSING))
            return InvestmentDataCalculator.DataStatus.PARTIAL;
        return InvestmentDataCalculator.DataStatus.OK;
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
            BigDecimal cash, BigDecimal debt, BigDecimal dilutedShares, String dilutedSharesBasis,
            BigDecimal revenueTTM, BigDecimal revenueGrowthYoY,
            BigDecimal ebitdaTTM, BigDecimal eps, BigDecimal fcfTTM,
            InvestmentDataCalculator.DataStatus status
    ) {
        private static FundamentalData missing() {
            return new FundamentalData(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, InvestmentDataCalculator.DataStatus.DATA_MISSING);
        }

        private FundamentalData withStatus(InvestmentDataCalculator.DataStatus status) {
            return new FundamentalData(fiscalPeriod, fiscalYear, fiscalPeriodCode, reportedAt, asOf, source,
                    marketCap, marketCapAsOf, enterpriseValue, enterpriseValueAsOf, enterpriseValueSource,
                    cash, debt, dilutedShares, dilutedSharesBasis, revenueTTM, revenueGrowthYoY,
                    ebitdaTTM, eps, fcfTTM, status);
        }

        private BigDecimal value(String field) {
            return switch (field) {
                case "marketCap" -> marketCap;
                case "enterpriseValue" -> enterpriseValue;
                case "cash" -> cash;
                case "debt" -> debt;
                case "dilutedShares" -> dilutedShares;
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
                    "marketCapAsOf", marketCapAsOf, "enterpriseValue", enterpriseValue,
                    "enterpriseValueAsOf", enterpriseValueAsOf, "enterpriseValueSource", enterpriseValueSource,
                    "cash", cash, "debt", debt, "dilutedShares", dilutedShares,
                    "dilutedSharesBasis", dilutedSharesBasis, "revenueTTM", revenueTTM,
                    "revenueGrowthYoY", revenueGrowthYoY, "ebitdaTTM", ebitdaTTM,
                    "eps", eps, "fcfTTM", fcfTTM, "status", status.name());
            return data;
        }
    }

    private record ConsensusData(
            Instant asOf, String horizon, String source, BigDecimal revenueConsensus,
            BigDecimal epsConsensus, BigDecimal ebitdaConsensus, BigDecimal fcfConsensus,
            InvestmentDataCalculator.DataStatus status
    ) {
        private static ConsensusData missing() {
            return new ConsensusData(null, null, null, null, null, null, null,
                    InvestmentDataCalculator.DataStatus.DATA_MISSING);
        }

        private ConsensusData withStatus(InvestmentDataCalculator.DataStatus status) {
            return new ConsensusData(asOf, horizon, source, revenueConsensus, epsConsensus,
                    ebitdaConsensus, fcfConsensus, status);
        }

        private Map<String, Object> view() {
            return map("asOf", asOf, "horizon", horizon, "source", source,
                    "revenueConsensus", revenueConsensus, "epsConsensus", epsConsensus,
                    "ebitdaConsensus", ebitdaConsensus, "fcfConsensus", fcfConsensus,
                    "status", status.name());
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
                    "status", result.status().name());
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
