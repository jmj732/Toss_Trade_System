package com.jmj.trade.sheets;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.connector.ConnectorResponse;
import com.jmj.trade.connector.ConnectorService;
import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CombinedPortfolioMirrorIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("62a6f45b-5f71-4fd7-a2ac-8d20f29c60c4");
    private static final UUID CONNECTION_ID = UUID.fromString("3d176e40-0f99-45f9-91f1-68a621c7f54b");
    private static final String SPREADSHEET_ID = "synthetic-sheet";
    private static final BrokerAccountRef BROKER_ACCOUNT =
            new BrokerAccountRef(CONNECTION_ID, "01", "GENERAL", "****0001");

    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private DataSourceTransactionManager transactions;
    private ObjectMapper mapper;

    @BeforeEach
    void migrateAndSeed() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        mapper = new ObjectMapper();
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @AfterEach
    void closeTestDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsCombinedPortfolioReadsItForContextAndMirrorsItThenKeepsAcceptedValuesOnReadFailure()
            throws Exception {
        var syncAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var quoteAt = syncAt.minusSeconds(20);
        var manualAsOf = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        var changingClock = new AtomicReference<>(syncAt);
        var failAccountStateRead = new AtomicBoolean(false);
        var reconciliationValues = new AtomicReference<>(
                new GoogleSheetsClient.SheetValues("reconciliation", List.of()));
        var sheets = mock(GoogleSheetsClient.class);
        var thesisHeadersWithManualFields = List.of("Ticker", "Core Thesis", "Upside Driver", "Expectations Gap",
                "Fundamental Invalidation", "Revision Invalidation", "Price Risk Trigger",
                "Price Risk Trigger Price", "Invalidation Status", "Expand Trigger",
                "Exit Or Discard Trigger", "Classification", "Updated At",
                "Sizing Eligible", "Next Catalyst", "Next Review");
        var decisionHeadersWithManualFields = List.of("Decision ID", "As Of", "Asset", "Action", "Reference Price",
                "Price Session", "Horizon", "Alpha Thesis", "Invalidation", "Next Review Trigger",
                "Confidence", "Risk Policy Check", "Created At", "Scope", "Account");
        when(sheets.readValues(eq(SPREADSHEET_ID), anyString())).thenAnswer(invocation -> {
            var range = (String) invocation.getArgument(1);
            if ("'Account State'!A:Z".equals(range)) {
                if (failAccountStateRead.get()) throw new RuntimeException("synthetic-token-must-not-escape");
                return manualAccountState(manualAsOf, syncAt.minus(Duration.ofHours(2)));
            }
            if ("'Account Registry'!A:Z".equals(range)) return accountRegistry();
            if ("'Reconciliation Log'!A:Z".equals(range)) return reconciliationValues.get();
            if ("'Thesis State'!A:ZZ".equals(range)) return new GoogleSheetsClient.SheetValues(range, List.of(
                    new ArrayList<>(thesisHeadersWithManualFields),
                    new ArrayList<>(List.of("AAPL", "manual thesis", "", "", "", "", "", "", "", "", "",
                            "", "", true, "product review", "2026-Q4"))));
            if ("'Thesis State Legacy before DB'!A:ZZ".equals(range)) return new GoogleSheetsClient.SheetValues(range,
                    List.of(List.of("Ticker", "Older thesis archive"), List.of("AAPL", "preserve existing archive")));
            if ("'Decision Ledger'!A:ZZ".equals(range)) return new GoogleSheetsClient.SheetValues(range, List.of(
                    new ArrayList<>(decisionHeadersWithManualFields),
                    new ArrayList<>(List.of("decision-1", "2026-10-04", "AAPL", "HOLD", "100", "REGULAR_CLOSE",
                            "LONG", "manual alpha", "manual invalidation", "review", "HIGH", "PASS", "2026-10-04",
                            "CORE", "ACCOUNT_1"))));
            if ("'Decision Ledger Legacy before DB'!A:ZZ".equals(range)) return new GoogleSheetsClient.SheetValues(range,
                    List.of(List.of("decision", "older archive"), List.of("decision-1", "preserve existing archive")));
            return new GoogleSheetsClient.SheetValues(range, List.of());
        });
        when(sheets.sheetIdsByTitle(SPREADSHEET_ID)).thenReturn(Map.of(
                "Security Snapshot", 1, "Thesis State", 2, "Consensus History", 3, "Watchlist", 4,
                "Decision Ledger", 5, "Alpha State", 6, "Risk Policy", 7,
                "Thesis State Legacy before DB", 100, "Decision Ledger Legacy before DB", 101));
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var updates = (List<GoogleSheetsClient.SheetValueRange>) invocation.getArgument(1);
            updates.stream().filter(update -> update.range().startsWith("'Reconciliation Log'!"))
                    .findFirst().ifPresent(update -> reconciliationValues.set(new GoogleSheetsClient.SheetValues(
                            update.range(), update.values())));
            return null;
        }).when(sheets).batchUpdateValues(eq(SPREADSHEET_ID), any());

        var connector = mock(ConnectorService.class);
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(account1Portfolio(syncAt.minusSeconds(30)));
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenReturn(List.of());
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenReturn(List.of());
        var brokerSurface = mock(BrokerSurfaceService.class);
        when(brokerSurface.prices(eq(USER_ID), eq(CONNECTION_ID), anyString()))
                .thenReturn(BrokerSurfaceResponse.available(List.of(
                        price("AAPL", "100", quoteAt),
                        price("GOOGL", "50", quoteAt),
                        price("VST", "25", quoteAt))));

        var properties = new InvestmentOsSheetProperties(true, SPREADSHEET_ID, USER_ID, CONNECTION_ID,
                InvestmentOsSheetModel.ACCOUNT_1, Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(2));
        var riskPolicies = mock(RiskPolicyService.class);
        when(riskPolicies.current(USER_ID)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                0, bd("10000000"), bd("10000"), bd("100"), bd("0.25"), false));
        when(riskPolicies.history(USER_ID, 100)).thenReturn(List.of());
        var context = contextService(properties, riskPolicies);
        var researchMirror = new InvestmentOsResearchSheetSync(
                properties, sheets, context, riskPolicies, jdbc, mapper);
        var sync = new InvestmentOsSheetSyncService(properties,
                new InvestmentOsSheetLease(jdbc, Duration.ofMinutes(2)), connector, brokerSurface, sheets,
                changingClock::get, researchMirror, jdbc, mapper);

        var result = sync.sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.SUCCEEDED);
        assertThat(result.error()).isNull();
        verify(sheets, org.mockito.Mockito.never()).duplicateSheets(anyString(), any());
        var snapshotId = jdbc.queryForObject("""
                SELECT id FROM investment_os_portfolio_snapshots
                 WHERE user_id = ? AND payload IS NOT NULL ORDER BY attempted_at DESC LIMIT 1
                """, UUID.class, USER_ID);
        assertThat(jdbc.queryForObject("SELECT attempt_status FROM investment_os_portfolio_snapshots WHERE id = ?",
                String.class, snapshotId)).isEqualTo("SUCCEEDED");
        var payload = mapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM investment_os_portfolio_snapshots WHERE id = ?", String.class, snapshotId));
        var accountState = payload.path("accountState");
        var accountHeaders = strings(accountState.path("headers"));
        var accountRows = accountState.path("rows");
        var manualRows = rowsFor(accountRows, accountHeaders, "Account", InvestmentOsSheetModel.ACCOUNT_2);
        assertThat(manualRows).isNotEmpty();
        assertThat(manualRows).allSatisfy(row -> {
            assertThat(cell(row, accountHeaders, "Source")).isEqualTo("USER_SCREENSHOT");
            assertThat(cell(row, accountHeaders, "asOf")).isEqualTo(manualAsOf.toString());
        });
        assertThat(payload.path("manualReadAt").asText()).isEqualTo(syncAt.toString());
        assertThat(payload.path("account1AsOf").asText()).isNotEqualTo(manualAsOf.toString());

        var aggregate = payload.path("aggregate");
        var aggregateHeaders = strings(aggregate.path("headers"));
        var aggregateRows = aggregate.path("rows");
        var aapl = rowFor(aggregateRows, aggregateHeaders, "Ticker", "AAPL");
        var googl = rowFor(aggregateRows, aggregateHeaders, "Ticker", "GOOGL");
        var vst = rowFor(aggregateRows, aggregateHeaders, "Ticker", "VST");
        assertThat(decimalCell(aapl, aggregateHeaders, "Quantity")).isEqualByComparingTo("13");
        assertThat(decimalCell(googl, aggregateHeaders, "Quantity")).isEqualByComparingTo("2");
        assertThat(decimalCell(vst, aggregateHeaders, "Quantity")).isEqualByComparingTo("4");
        assertThat(cell(aapl, aggregateHeaders, "Accounts Included")).contains("ACCOUNT_1", "ACCOUNT_2");
        assertThat(cell(googl, aggregateHeaders, "Accounts Included")).isEqualTo("ACCOUNT_2");
        assertThat(cell(vst, aggregateHeaders, "Accounts Included")).isEqualTo("ACCOUNT_2");

        var metrics = payload.path("metrics");
        var metricHeaders = strings(metrics.path("headers"));
        var combined = rowFor(metrics.path("rows"), metricHeaders, "Scope", "COMBINED");
        assertThat(decimalCell(combined, metricHeaders, "Total Value")).isEqualByComparingTo("2100");
        assertThat(decimalCell(combined, metricHeaders, "Cash USD")).isEqualByComparingTo("600");

        var view = context.context(USER_ID).portfolio();
        assertThat(view.source()).isEqualTo("TOSS_API+MANUAL_SHEET");
        assertThat(view.manualAsOf()).isEqualTo(manualAsOf);
        assertThat(view.manualReadAt()).isEqualTo(syncAt);
        assertThat(view.manualStatus()).isEqualTo("OK");
        assertThat(view.account1AsOf()).isEqualTo(syncAt.minusSeconds(30));
        var contextAapl = position(view.positions(), "AAPL");
        var contextGoogl = position(view.positions(), "GOOGL");
        var contextVst = position(view.positions(), "VST");
        assertThat(contextAapl.quantity()).isEqualByComparingTo("13");
        assertThat(contextAapl.marketValue()).isEqualByComparingTo("1300");
        assertThat(contextAapl.weight()).isEqualByComparingTo(new BigDecimal("1300")
                .divide(new BigDecimal("2100"), java.math.MathContext.DECIMAL128));
        assertThat(contextGoogl.quantity()).isEqualByComparingTo("2");
        assertThat(contextGoogl.accountsIncluded()).isEqualTo("ACCOUNT_2");
        assertThat(contextVst.quantity()).isEqualByComparingTo("4");
        assertThat(contextVst.accountsIncluded()).isEqualTo("ACCOUNT_2");
        assertThat(contextAapl.manualAsOf()).isEqualTo(manualAsOf);
        assertThat(contextAapl.priceAsOf()).isEqualTo(quoteAt);
        assertThat(contextAapl.manualAsOf()).isNotEqualTo(
                contextAapl.priceAsOf().atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
        verifyNoInteractions(mockPortfolioReadService);

        var updates = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(sheets, org.mockito.Mockito.times(2)).batchUpdateValues(eq(SPREADSHEET_ID), updates.capture());
        @SuppressWarnings("unchecked")
        var allUpdates = (List<List<GoogleSheetsClient.SheetValueRange>>) (List<?>) updates.getAllValues();
        assertThat(allUpdates).hasSize(2);
        assertThat(allUpdates.getFirst()).anyMatch(update -> "'Security Snapshot'!A1".equals(update.range()));
        assertThat(allUpdates.getLast()).anyMatch(update -> update.range().startsWith("'Account State'!A1"));
        var researchUpdates = allUpdates.stream().flatMap(List::stream)
                .filter(update -> update.range().startsWith("'Security Snapshot'!A1")
                        || update.range().startsWith("'Thesis State'!A1")
                        || update.range().startsWith("'Decision Ledger'!A1")
                        || update.range().startsWith("'Consensus History'!A1")
                        || update.range().startsWith("'Watchlist'!A1"))
                .toList();
        assertThat(researchUpdates).anyMatch(update -> "'Security Snapshot'!A1".equals(update.range()))
                .anyMatch(update -> "'Consensus History'!A1".equals(update.range()))
                .anyMatch(update -> "'Watchlist'!A1".equals(update.range()))
                .noneMatch(update -> update.range().startsWith("'Thesis State'!A1"));
        var decisionLedgerUpdates = allUpdates.stream().flatMap(List::stream)
                .filter(update -> update.range().startsWith("'Decision Ledger'!"))
                .toList();
        assertThat(decisionLedgerUpdates).hasSize(1);
        assertThat(decisionLedgerUpdates.getFirst().range()).isEqualTo("'Decision Ledger'!P1:R1");
        assertThat(decisionLedgerUpdates.getFirst().values()).isEqualTo(List.of(List.of(
                "EntrySetup", "InitialRiskPrice", "OverlayEffect")));
        var reconciliationWrites = allUpdates.stream().flatMap(List::stream)
                .filter(update -> update.range().startsWith("'Reconciliation Log'!A1")).toList();
        assertThat(reconciliationWrites).hasSize(1);
        var reconciliation = reconciliationWrites.getLast();
        assertThat(reconciliation.values()).hasSize(2);
        var reconciliationHeaders = reconciliation.values().getFirst().stream().map(String::valueOf).toList();
        var reconciliationRow = reconciliation.values().get(1);
        assertThat(reconciliationRow.get(reconciliationHeaders.indexOf("Error")))
                .isEqualTo("");
        var securityUpdate = allUpdates.stream().flatMap(List::stream)
                .filter(update -> "'Security Snapshot'!A1".equals(update.range())).findFirst().orElseThrow();
        var securityHeaders = securityUpdate.values().getFirst().stream().map(String::valueOf).toList();
        assertThat(securityHeaders).hasSize(121);
        assertThat(securityHeaders.subList(119, 121)).containsExactly("ThemeId", "TrendStage");
        var securityAapl = rowForValues(securityUpdate.values().subList(1, securityUpdate.values().size()),
                securityHeaders, "Ticker", "AAPL");
        assertThat(value(securityAapl, securityHeaders, "Quantity")).isEqualTo(contextAapl.quantity());
        assertThat(value(securityAapl, securityHeaders, "Weight")).isEqualTo(contextAapl.weight());
        assertThat(value(securityAapl, securityHeaders, "Combined Portfolio Source")).isEqualTo(view.source());
        assertThat(value(securityAapl, securityHeaders, "Combined Portfolio Status")).isEqualTo(view.status());
        assertThat(value(securityAapl, securityHeaders, "Account 1 As Of")).isEqualTo(syncAt.minusSeconds(30).toString());
        assertThat(value(securityAapl, securityHeaders, "Manual Account As Of")).isEqualTo(manualAsOf.toString());
        assertThat(value(securityAapl, securityHeaders, "Manual Read At")).isEqualTo(syncAt.toString());
        assertThat(value(securityAapl, securityHeaders, "Manual Account Status")).isEqualTo("OK");
        assertThat(value(securityAapl, securityHeaders, "Manual Account Stale")).isEqualTo("false");
        assertThat(value(securityAapl, securityHeaders, "Position Manual As Of")).isEqualTo(manualAsOf.toString());
        assertThat(value(securityAapl, securityHeaders, "Position Price As Of")).isEqualTo(quoteAt.toString());
        assertThat((String) value(securityAapl, securityHeaders, "Position Accounts Included"))
                .contains("ACCOUNT_1", "ACCOUNT_2");

        var repeated = sync.sync();
        assertThat(repeated.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.SUCCEEDED);
        assertThat(reconciliationValues.get().values()).hasSize(2);
        assertThat(reconciliationValues.get().values().get(1).get(reconciliationHeaders.indexOf("Error")))
                .isEqualTo("");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE investment_os_portfolio_snapshots SET error_code = 'TAMPER' WHERE id = ?", snapshotId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM investment_os_portfolio_snapshots WHERE id = ?", snapshotId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_os_portfolio_snapshots WHERE id = ?",
                Integer.class, snapshotId)).isEqualTo(1);

        changingClock.set(syncAt.plusSeconds(30));
        failAccountStateRead.set(true);
        var failed = sync.sync();
        assertThat(failed.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.FAILED);
        assertThat(failed.error()).isEqualTo("RuntimeException").doesNotContain("synthetic-token");
        assertThat(jdbc.queryForObject("""
                SELECT error_code FROM investment_os_portfolio_snapshots
                 WHERE user_id = ? ORDER BY attempted_at DESC LIMIT 1
                """, String.class, USER_ID)).isEqualTo("SHEET_READ_FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT payload IS NULL FROM investment_os_portfolio_snapshots
                 WHERE user_id = ? ORDER BY attempted_at DESC LIMIT 1
                """, Boolean.class, USER_ID)).isTrue();
        var afterFailure = context.context(USER_ID).portfolio();
        assertThat(afterFailure.snapshotStatus()).isEqualTo("FAILED");
        assertThat(afterFailure.manualStatus()).isEqualTo("LATEST_REFRESH_FAILED");
        assertThat(afterFailure.stale()).isTrue();
        assertThat(afterFailure.missingFields()).contains("SHEET_REFRESH_FAILED");
        assertThat(position(afterFailure.positions(), "AAPL").quantity()).isEqualByComparingTo("13");
        assertThat(position(afterFailure.positions(), "GOOGL").marketValue()).isEqualByComparingTo("100");
        verifyNoInteractions(mockPortfolioReadService);
    }

    private PortfolioReadService mockPortfolioReadService;

    private InvestmentContextService contextService(InvestmentOsSheetProperties properties,
                                                    RiskPolicyService riskPolicies) {
        mockPortfolioReadService = mock(PortfolioReadService.class);
        var watchlist = mock(MonitoringWatchlistService.class);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var context = new InvestmentContextService(jdbc, mapper, transactions,
                new StockDataProviderRegistry(List.of()), provider, mockPortfolioReadService, watchlist,
                riskPolicies, Duration.ofMinutes(15), Duration.ofDays(7),
                Duration.ofDays(210), Duration.ofDays(10));
        context.setInvestmentOsSheetProperties(properties);
        return context;
    }

    private static ConnectorResponse.Portfolio account1Portfolio(Instant completedAt) {
        var position = new ConnectorResponse.Position("AAPL", "Apple", "US", bd("10"), "USD", bd("80"),
                null, null, null, null, null, null, null, null, null, null, null, null, bd("10"), completedAt);
        return new ConnectorResponse.Portfolio(completedAt, false, null, false, List.of(), List.of(), null,
                List.of(position), Map.of(
                "USD", new ConnectorResponse.BuyingPower(bd("500"), completedAt),
                "KRW", new ConnectorResponse.BuyingPower(BigDecimal.ZERO, completedAt)));
    }

    private static BrokerSurfaceResponse.PriceView price(String ticker, String value, Instant asOf) {
        return new BrokerSurfaceResponse.PriceView(ticker, bd(value), null, null, "USD", asOf, asOf);
    }

    private static GoogleSheetsClient.SheetValues manualAccountState(LocalDate asOf, Instant syncedAt) {
        var headers = new ArrayList<>(InvestmentOsSheetModel.accountHeaders());
        headers.add("asOf");
        var synced = syncedAt.toString();
        var rows = new ArrayList<List<Object>>();
        rows.add(new ArrayList<>(headers));
        rows.add(accountRow("AAPL", "HOLDING", "USD", "3", "80", "", "", "", "USER_SCREENSHOT",
                "HIGH", synced, "", "", "HELD", asOf.toString()));
        rows.add(accountRow("GOOGL", "HOLDING", "USD", "2", "40", "", "", "", "USER_SCREENSHOT",
                "HIGH", synced, "", "", "HELD", asOf.toString()));
        rows.add(accountRow("VST", "HOLDING", "USD", "4", "20", "", "", "", "USER_SCREENSHOT",
                "HIGH", synced, "", "", "HELD", asOf.toString()));
        rows.add(accountRow("CASH_USD", "CASH", "USD", "", "", "", "", "100", "USER_SCREENSHOT",
                "HIGH", synced, "", "", "CASH", asOf.toString()));
        return new GoogleSheetsClient.SheetValues("'Account State'!A:Z", rows);
    }

    private static List<Object> accountRow(String ticker, String type, String currency, String quantity,
                                           String averageCost, String price, String marketValue, String cash,
                                           String source, String confidence, String syncedAt, String priceSource,
                                           String priceSyncedAt, String state, String asOf) {
        return List.of(InvestmentOsSheetModel.ACCOUNT_2, ticker, type, currency, quantity, averageCost, price,
                marketValue, cash, source, confidence, syncedAt, priceSource, priceSyncedAt, state, asOf);
    }

    private static GoogleSheetsClient.SheetValues accountRegistry() {
        return new GoogleSheetsClient.SheetValues("'Account Registry'!A:Z", List.of(
                List.of("Account", "Label", "Sync Mode", "Source", "Default Confidence", "Enabled", "Last Sync", "Notes"),
                List.of("ACCOUNT_1", "Broker", "AUTO", "TOSS_API", "HIGH", "TRUE", "", ""),
                List.of("ACCOUNT_2", "Manual", "MANUAL", "MANUAL", "HIGH", "TRUE", "", "")));
    }

    private static List<String> strings(JsonNode values) {
        var result = new ArrayList<String>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static JsonNode rowFor(JsonNode rows, List<String> headers, String key, String value) {
        var index = headers.indexOf(key);
        for (var row : rows) if (row.path(index).asText().equals(value)) return row;
        throw new AssertionError("Missing row " + key + "=" + value);
    }

    private static List<JsonNode> rowsFor(JsonNode rows, List<String> headers, String key, String value) {
        var index = headers.indexOf(key);
        var result = new ArrayList<JsonNode>();
        rows.forEach(row -> { if (row.path(index).asText().equals(value)) result.add(row); });
        return result;
    }

    private static String cell(JsonNode row, List<String> headers, String name) {
        return row.path(headers.indexOf(name)).asText();
    }

    private static BigDecimal decimalCell(JsonNode row, List<String> headers, String name) {
        return new BigDecimal(cell(row, headers, name));
    }

    private static InvestmentContextService.PositionView position(
            List<InvestmentContextService.PositionView> positions, String ticker) {
        return positions.stream().filter(candidate -> ticker.equals(candidate.ticker())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing context position " + ticker));
    }

    private static JsonNode rowForValues(List<List<Object>> rows, List<String> headers, String key, String value) {
        var index = headers.indexOf(key);
        return new ObjectMapper().valueToTree(rows.stream().filter(row -> value.equals(String.valueOf(row.get(index))))
                .findFirst().orElseThrow());
    }

    private static Object value(JsonNode row, List<String> headers, String name) {
        var node = row.path(headers.indexOf(name));
        if (node.isNumber()) return node.decimalValue();
        return node.asText();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
