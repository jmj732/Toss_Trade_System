package com.jmj.trade.sheets;

import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Mirrors canonical Investment OS read models and append-only history into operational tabs. */
final class InvestmentOsResearchSheetSync {

    private static final List<String> TABS = List.of(
            "Security Snapshot", "Thesis State", "Consensus History", "Watchlist",
            "Decision Ledger", "Alpha State", "Risk Policy");
    private final InvestmentOsSheetProperties properties;
    private final GoogleSheetsClient sheets;
    private final InvestmentContextService investment;
    private final RiskPolicyService riskPolicies;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    InvestmentOsResearchSheetSync(
            InvestmentOsSheetProperties properties,
            GoogleSheetsClient sheets,
            InvestmentContextService investment,
            RiskPolicyService riskPolicies,
            JdbcTemplate jdbc,
            ObjectMapper mapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.sheets = Objects.requireNonNull(sheets, "sheets");
        this.investment = Objects.requireNonNull(investment, "investment");
        this.riskPolicies = Objects.requireNonNull(riskPolicies, "riskPolicies");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    void sync(UUID userId) {
        var context = investment.context(userId);
        var policy = context.riskPolicy();
        var tables = new LinkedHashMap<String, Table>();
        tables.put("Security Snapshot", security(context.securities()));
        tables.put("Thesis State", thesis(context.securities()));
        tables.put("Consensus History", consensusHistory(userId));
        tables.put("Watchlist", watchlist(context.watchlist()));
        tables.put("Decision Ledger", decisionLedger(userId));
        tables.put("Alpha State", alphaState(userId));
        tables.put("Risk Policy", riskPolicy(userId, policy));

        var spreadsheetId = properties.spreadsheetId();
        var sheetIds = sheets.sheetIdsByTitle(spreadsheetId);
        var currentValues = new LinkedHashMap<String, List<List<Object>>>();
        var archives = new LinkedHashMap<String, Integer>();
        tables.forEach((tab, table) -> {
            var sourceSheetId = sheetIds.get(tab);
            if (sourceSheetId == null) return;
            var range = quote(tab) + "!A:ZZ";
            var current = sheets.readValues(spreadsheetId, range).values();
            currentValues.put(tab, current);
            if (current.isEmpty() || current.getFirst().equals(table.headers())) return;

            var archiveTitle = tab + " Legacy before DB";
            if (sheetIds.containsKey(archiveTitle)) {
                var archived = sheets.readValues(spreadsheetId, quote(archiveTitle) + "!A:ZZ").values();
                if (!current.equals(archived)) {
                    throw GoogleSheetsException.contract("Legacy archive does not match current tab: " + tab);
                }
            } else {
                archives.put(archiveTitle, sourceSheetId);
            }
        });
        if (!archives.isEmpty()) {
            sheets.duplicateSheets(spreadsheetId, archives);
            var verifiedSheetIds = sheets.sheetIdsByTitle(spreadsheetId);
            if (!verifiedSheetIds.keySet().containsAll(archives.keySet())) {
                throw GoogleSheetsException.contract("Google Sheets archive verification failed");
            }
        }
        sheets.ensureSheets(spreadsheetId, TABS);

        var updates = new ArrayList<GoogleSheetsClient.SheetValueRange>();
        tables.forEach((tab, table) -> {
            var current = currentValues.getOrDefault(tab, List.of());
            var rowCount = Math.max(table.rows().size(), current.size());
            var width = Math.max(table.headers().size(), current.stream().mapToInt(List::size).max().orElse(0));
            var values = new ArrayList<List<Object>>(rowCount);
            values.add(pad(table.headers(), width));
            table.rows().forEach(row -> values.add(pad(row, width)));
            while (values.size() < rowCount) values.add(java.util.Collections.nCopies(width, ""));
            updates.add(new GoogleSheetsClient.SheetValueRange(quote(tab) + "!A1", values));
        });
        sheets.batchUpdateValues(spreadsheetId, updates);
    }

    private Table security(List<InvestmentContextService.SecurityView> securities) {
        var headers = List.of("Ticker", "As Of", "Quantity", "Weight", "Currency", "Regular Close",
                "Regular Close As Of", "Latest Price", "Latest Price As Of", "Session", "Source",
                "Secondary Source", "Price Status", "Trend Status", "SMA20", "SMA50", "RSI14",
                "Fundamental Fiscal Period", "Fundamental Reported At", "Fundamental As Of", "Fundamental Source",
                "Market Cap", "Enterprise Value", "Cash", "Debt", "Diluted Shares", "Revenue TTM",
                "Revenue Growth YoY", "EBITDA TTM", "EPS", "FCF TTM", "Fundamental Status",
                "Balance Sheet Status", "Consensus As Of", "Consensus Horizon", "Consensus Source",
                "Revenue Consensus", "EPS Consensus", "EBITDA Consensus", "FCF Consensus",
                "Revenue Revision 30D", "Revenue Revision 90D",
                "EPS Revision 30D", "EPS Revision 90D", "Valuation Status", "EV/Sales TTM",
                "EV/Sales Forward", "EV/EBITDA TTM", "EV/EBITDA Forward", "Forward P/E", "FCF Yield TTM",
                "FCF Yield Forward", "Normalized FCF", "Overall Data Status", "Missing Fields", "Thesis", "Risk");
        var rows = securities.stream().map(security -> {
            var price = security.price();
            var technical = security.technical();
            var fundamental = security.fundamentals();
            var consensus = security.consensus();
            var revision = security.revision();
            var valuation = security.valuation();
            var readiness = security.readiness();
            var position = security.position();
            return row(security.ticker(), instant(security.asOf()), position == null ? null : position.quantity(),
                    position == null ? null : position.weight(), position == null ? null : position.currency(),
                    value(price, "regularClose"), value(price, "regularCloseAsOf"), value(price, "latestPrice"),
                    value(price, "latestPriceAsOf"), value(price, "session"), value(price, "source"),
                    value(price, "secondarySource"), value(price, "status"), value(technical, "trendStatus"),
                    value(technical, "sma20"), value(technical, "sma50"), value(technical, "rsi14"),
                    value(fundamental, "fiscalPeriod"), value(fundamental, "reportedAt"),
                    value(fundamental, "asOf"), value(fundamental, "source"),
                    value(fundamental, "marketCap"), value(fundamental, "enterpriseValue"), value(fundamental, "cash"),
                    value(fundamental, "debt"), value(fundamental, "dilutedShares"), value(fundamental, "revenueTTM"),
                    value(fundamental, "revenueGrowthYoY"), value(fundamental, "ebitdaTTM"), value(fundamental, "eps"),
                    value(fundamental, "fcfTTM"), value(readiness, "fundamentalStatus"),
                    value(readiness, "balanceSheetStatus"), value(consensus, "asOf"), value(consensus, "horizon"),
                    value(consensus, "source"), value(consensus, "revenueConsensus"), value(consensus, "epsConsensus"),
                    value(consensus, "ebitdaConsensus"), value(consensus, "fcfConsensus"),
                    nested(revision, "revenueRevision30D", "value"), nested(revision, "revenueRevision90D", "value"),
                    nested(revision, "epsRevision30D", "value"), nested(revision, "epsRevision90D", "value"),
                    value(valuation, "status"), value(valuation, "evSalesTTM"), value(valuation, "evSalesForward"),
                    value(valuation, "evEbitdaTTM"), value(valuation, "evEbitdaForward"), value(valuation, "forwardPE"),
                    value(valuation, "fcfYieldTTM"), value(valuation, "fcfYieldForward"), value(valuation, "normalizedFcf"),
                    value(readiness, "overallDataStatus"), value(readiness, "missingFields"), json(security.thesis()),
                    json(security.risk()));
        }).toList();
        return new Table(headers, rows);
    }

    private static Table thesis(List<InvestmentContextService.SecurityView> securities) {
        var headers = List.of("Ticker", "Core Thesis", "Upside Driver", "Expectations Gap",
                "Fundamental Invalidation", "Revision Invalidation", "Price Risk Trigger",
                "Price Risk Trigger Price", "Invalidation Status", "Expand Trigger",
                "Exit Or Discard Trigger", "Classification", "Updated At");
        var rows = securities.stream().filter(value -> value.thesis() != null).map(value -> {
            var thesis = value.thesis();
            return row(thesis.ticker(), thesis.coreThesis(), thesis.upsideDriver(), thesis.expectationsGap(),
                    thesis.fundamentalInvalidation(), thesis.revisionInvalidation(), thesis.priceRiskTrigger(),
                    thesis.priceRiskTriggerPrice(), thesis.invalidationStatus(), thesis.expandTrigger(),
                    thesis.exitOrDiscardTrigger(), thesis.classification(), instant(thesis.updatedAt()));
        }).toList();
        return new Table(headers, rows);
    }

    private Table consensusHistory(UUID userId) {
        var headers = List.of("Ticker", "As Of", "Horizon", "Revenue Consensus", "EPS Consensus",
                "EBITDA Consensus", "FCF Consensus", "Source");
        var rows = jdbc.query("""
                SELECT ticker, as_of, horizon, revenue_consensus, eps_consensus,
                       ebitda_consensus, fcf_consensus, source
                  FROM consensus_snapshots WHERE user_id = ?
                 ORDER BY ticker, horizon, as_of, source
                """, (resultSet, rowNum) -> row(resultSet.getString("ticker"), instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                resultSet.getString("horizon"), resultSet.getBigDecimal("revenue_consensus"),
                resultSet.getBigDecimal("eps_consensus"), resultSet.getBigDecimal("ebitda_consensus"),
                resultSet.getBigDecimal("fcf_consensus"), resultSet.getString("source")), userId);
        return new Table(headers, rows);
    }

    private Table watchlist(List<InvestmentContextService.WatchlistView> entries) {
        var headers = List.of("Ticker", "Status", "Levels", "Evidence", "Observed At", "Created At", "Updated At");
        var rows = entries.stream().map(entry -> row(entry.symbol(), entry.status(), json(entry.levels()),
                json(entry.evidence()), instant(entry.observedAt()), instant(entry.createdAt()), instant(entry.updatedAt())))
                .toList();
        return new Table(headers, rows);
    }

    private Table decisionLedger(UUID userId) {
        var headers = List.of("Decision ID", "As Of", "Asset", "Action", "Reference Price", "Price Session",
                "Horizon", "Alpha Thesis", "Invalidation", "Next Review Trigger", "Confidence",
                "Risk Policy Check", "Created At");
        var rows = jdbc.query("""
                SELECT decision_id, as_of, asset, action, reference_price, price_session, horizon,
                       alpha_thesis, invalidation, next_review_trigger, confidence,
                       risk_policy_check::text, created_at
                  FROM investment_decision_ledger WHERE user_id = ?
                 ORDER BY as_of, decision_id
                """, (resultSet, rowNum) -> row(resultSet.getObject("decision_id", UUID.class),
                instant(resultSet.getObject("as_of", OffsetDateTime.class)), resultSet.getString("asset"),
                resultSet.getString("action"), resultSet.getBigDecimal("reference_price"),
                resultSet.getString("price_session"), resultSet.getString("horizon"),
                resultSet.getString("alpha_thesis"), resultSet.getString("invalidation"),
                resultSet.getString("next_review_trigger"), resultSet.getBigDecimal("confidence"),
                resultSet.getString("risk_policy_check"), instant(resultSet.getObject("created_at", OffsetDateTime.class))),
                userId);
        return new Table(headers, rows);
    }

    private Table alphaState(UUID userId) {
        var headers = List.of("Ticker", "Sector", "Factor", "Beta", "Correlation", "Thesis",
                "Primary Alpha", "Conditions", "Updated At");
        var rows = jdbc.query("""
                SELECT symbol, sector, factor, beta, correlation, thesis, primary_alpha,
                       conditions::text, updated_at
                  FROM monitoring_position_contexts WHERE user_id = ? ORDER BY symbol
                """, (resultSet, rowNum) -> row(resultSet.getString("symbol"), resultSet.getString("sector"),
                resultSet.getString("factor"), resultSet.getBigDecimal("beta"), resultSet.getBigDecimal("correlation"),
                resultSet.getString("thesis"), resultSet.getString("primary_alpha"),
                resultSet.getString("conditions"), instant(resultSet.getObject("updated_at", OffsetDateTime.class))), userId);
        return new Table(headers, rows);
    }

    private Table riskPolicy(UUID userId, RiskPolicyService.RiskPolicySnapshot current) {
        var headers = List.of("Current", "Version", "Max Order KRW", "Max Order USD", "Max Quantity",
                "Max Concentration", "Soft Risk Budget", "Changed By", "Changed At");
        var rows = new ArrayList<List<Object>>();
        rows.add(row(true, current.version(), current.maxOrderAmountKrw(), current.maxOrderAmountUsd(),
                current.maxQuantity(), current.maxConcentration(), current.softRiskBudget(), "", ""));
        riskPolicies.history(userId, 100).forEach(entry -> rows.add(row(false, entry.version(),
                entry.maxOrderAmountKrw(), entry.maxOrderAmountUsd(), entry.maxQuantity(),
                entry.maxConcentration(), entry.softRiskBudget(), entry.changedBy(), instant(entry.changedAt()))));
        return new Table(headers, rows);
    }

    private String json(Object value) {
        if (value == null) return "";
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Investment OS Sheet data could not be serialized", exception);
        }
    }

    private static Object value(JsonNode node, String name) {
        if (node == null || node.get(name) == null || node.get(name).isNull()) return "";
        var value = node.get(name);
        if (value.isArray() || value.isObject()) return value.toString();
        if (value.isNumber()) return value.decimalValue();
        return value.asText();
    }

    private static Object nested(JsonNode node, String name, String field) {
        return node == null ? "" : value(node.get(name), field);
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String instant(OffsetDateTime value) {
        return value == null ? "" : value.toInstant().toString();
    }

    private static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static List<Object> pad(List<?> row, int width) {
        var cells = new ArrayList<Object>(row.size());
        row.forEach(value -> cells.add(value == null ? "" : value));
        while (cells.size() < width) cells.add("");
        return List.copyOf(cells);
    }

    private static String quote(String title) {
        return "'" + title.replace("'", "''") + "'";
    }

    private record Table(List<String> headers, List<List<Object>> rows) {
    }
}
