package com.jmj.trade.sheets;

import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InvestmentOsResearchSheetSyncTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void mirrorsInvestmentContextAndCanonicalHistoriesToManagedTabs() throws Exception {
        var sheets = mock(GoogleSheetsClient.class);
        var investment = mock(InvestmentContextService.class);
        var riskPolicies = mock(RiskPolicyService.class);
        var jdbc = mock(JdbcTemplate.class);
        when(sheets.readValues(eq("sheet-1"), anyString())).thenReturn(
                new GoogleSheetsClient.SheetValues("range", List.of()));
        when(sheets.sheetIdsByTitle("sheet-1")).thenReturn(sheetIds());
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), eq(USER_ID));
        doAnswer(invocation -> {
            var sql = (String) invocation.getArgument(0);
            if (!sql.contains("FROM consensus_snapshots")) return List.of();
            @SuppressWarnings("unchecked")
            var mapper = (RowMapper<Object>) invocation.getArgument(1);
            return List.of(mapConsensusRow(mapper));
        }).when(jdbc).query(anyString(), any(RowMapper.class), eq(USER_ID));
        when(riskPolicies.history(USER_ID, 100)).thenReturn(List.of());
        when(investment.context(USER_ID)).thenReturn(context());

        var sync = new InvestmentOsResearchSheetSync(
                new InvestmentOsSheetProperties(true, "sheet-1", USER_ID, UUID.randomUUID(),
                        Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(2)),
                sheets, investment, riskPolicies, jdbc, new ObjectMapper());

        sync.sync(USER_ID);

        verify(sheets).ensureSheets(eq("sheet-1"), eq(List.of("Security Snapshot", "Thesis State",
                "Consensus History", "Watchlist", "Decision Ledger", "Alpha State", "Risk Policy")));
        var updates = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(sheets).batchUpdateValues(eq("sheet-1"), updates.capture());
        @SuppressWarnings("unchecked")
        var tabs = (List<GoogleSheetsClient.SheetValueRange>) updates.getValue();
        verify(sheets).ensureSheetColumnCounts(eq("sheet-1"), argThat(columns ->
                columns.get("Security Snapshot") == tabs.getFirst().values().getFirst().size()));
        assertThat(tabs).extracting(GoogleSheetsClient.SheetValueRange::range).containsExactly(
                "'Security Snapshot'!A1", "'Thesis State'!A1", "'Consensus History'!A1", "'Watchlist'!A1",
                "'Decision Ledger'!A1", "'Alpha State'!A1", "'Risk Policy'!A1");
        var security = tabs.getFirst().values();
        assertThat(security.getFirst()).contains("Ticker", "Latest Price", "Price Status", "Fundamental Status",
                "Fundamental Fiscal Period", "Fundamental Reported At", "Fundamental As Of", "Fundamental Source",
                "Basic Shares", "Basic Shares Basis", "Market Cap As Of", "Market Cap Formula",
                "Fully Diluted Market Cap", "Fully Diluted Market Cap As Of", "Fully Diluted Market Cap Formula",
                "Enterprise Value As Of", "Enterprise Value Source", "Enterprise Value Formula",
                "Balance Sheet As Of", "Fundamental Currency",
                "Field Provenance", "EBITDA TTM Type", "EBITDA TTM Formula", "EBITDA TTM Source",
                "EBITDA Consensus", "FCF Consensus", "Consensus Missing Reason",
                "Revenue Revision 30D Status", "Revenue Revision 90D Status",
                "EPS Revision 30D Status", "EPS Revision 90D Status", "Consensus Status",
                "Consensus Estimate Type", "Consensus Estimate Label", "Consensus Period End",
                "Consensus Currency", "Consensus EPS Analyst Count", "Consensus Revenue Analyst Count",
                "Valuation TTM As Of", "Valuation TTM Source", "Valuation Forward As Of",
                "Valuation Forward Horizon", "Valuation Forward Source", "Valuation Forward Estimate Type",
                "Valuation Forward Period End", "Valuation Forward Currency", "Valuation Metric Statuses",
                "Valuation Metric Reasons", "Valuation Metric Provenance", "Display Currency",
                "Display Currency Source", "Display Currency Source As Of", "Display Currency Period",
                "Display Currency Status", "Risk Status", "Thesis Failure Stress Status",
                "Top Two Correlated Status", "Risk Soft Budget Status", "Risk Sizing Eligible",
                "Combined Portfolio Source", "Combined Portfolio Status", "Account 1 As Of",
                "Manual Account As Of", "Manual Read At", "Manual Account Status", "Manual Account Stale",
                "Risk Inputs Available", "Position Source Coverage", "Position Accounts Included",
                "Position Quantity As Of", "Position Price As Of", "Position Manual As Of");
        assertThat(security.getFirst()).startsWith("Ticker", "As Of", "Quantity", "Weight", "Currency");
        assertThat(security.getFirst().stream().filter("Currency"::equals).count()).isEqualTo(1L);
        assertThat(security.get(1)).contains("AAPL", new BigDecimal("101"), "REGULAR_CLOSE", "SOURCE_CONFLICT",
                "PARTIAL", "FY2025", "2026-02-01T00:00:00Z", "2026-02-02T00:00:00Z", "FMP",
                new BigDecimal("50"), "SEC_INSTANT", "2026-02-03T00:00:00Z",
                "TOSS_REGULAR_CLOSE * SEC_BASIC_SHARES", new BigDecimal("250"),
                "2026-02-04T00:00:00Z", "TOSS_REGULAR_CLOSE * SEC_DILUTED_SHARES",
                "2026-02-05T00:00:00Z", "MARKET_CAP_PLUS_LATEST_DEBT_MINUS_LATEST_CASH",
                "marketCap + latestDebt - latestCash", "2026-02-05T00:00:00Z", "USD",
                "{\"marketCap\":{\"source\":\"TOSS+SEC\"},\"enterpriseValue\":{\"source\":\"MARKET_CAP_PLUS_LATEST_DEBT_MINUS_LATEST_CASH\",\"formula\":\"marketCap + latestDebt - latestCash\"},\"cash\":{\"source\":\"SEC\",\"asOf\":\"2026-02-05T00:00:00Z\"},\"debt\":{\"source\":\"SEC\",\"asOf\":\"2026-02-06T00:00:00Z\"}}",
                "TTM_REPORTED", "REPORTED", "SEC",
                "2026-09-15T00:00:00Z", "FY2026", "CONSENSUS", new BigDecimal("10"), new BigDecimal("8"),
                "DAILY_QUOTA_EXHAUSTED");
        assertThat(security.get(1).get(security.getFirst().indexOf("Revenue Revision 30D Status")))
                .isEqualTo("OK");
        assertThat(security.get(1).get(security.getFirst().indexOf("Revenue Revision 90D Status")))
                .isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(security.get(1).get(security.getFirst().indexOf("EPS Revision 30D Status")))
                .isEqualTo("DATA_MISSING");
        assertThat(security.get(1).get(security.getFirst().indexOf("EPS Revision 90D Status")))
                .isEqualTo("OK");
        assertThat(security.get(1).get(security.getFirst().indexOf("Consensus Status"))).isEqualTo("PARTIAL");
        assertThat(security.get(1).get(security.getFirst().indexOf("Consensus Estimate Type"))).isEqualTo("ANNUAL");
        assertThat(security.get(1).get(security.getFirst().indexOf("Consensus Estimate Label"))).isEqualTo("FY2026");
        assertThat(security.get(1).get(security.getFirst().indexOf("Consensus Period End"))).isEqualTo("2026-12-31");
        assertThat(security.get(1).get(security.getFirst().indexOf("Consensus Currency"))).isEqualTo("USD");
        assertThat(security.get(1).get(security.getFirst().indexOf("Consensus EPS Analyst Count")))
                .isEqualTo(new BigDecimal("21"));
        assertThat(security.get(1).get(security.getFirst().indexOf("Consensus Revenue Analyst Count")))
                .isEqualTo(new BigDecimal("18"));
        assertThat(security.get(1).get(security.getFirst().indexOf("Valuation Forward Estimate Type")))
                .isEqualTo("ANNUAL");
        assertThat(security.get(1).get(security.getFirst().indexOf("Valuation Forward Period End")))
                .isEqualTo("2027-12-31");
        assertThat(security.get(1).get(security.getFirst().indexOf("Valuation Forward Currency"))).isEqualTo("");
        assertThat(security.get(1).get(security.getFirst().indexOf("Valuation Metric Statuses")))
                .isEqualTo("{\"evSalesTTM\":\"OK\",\"evSalesForward\":\"DATA_MISSING\"}");
        assertThat(security.get(1).get(security.getFirst().indexOf("Valuation Metric Reasons")))
                .isEqualTo("{\"evSalesForward\":\"CONSENSUS_MISSING\"}");
        assertThat(security.get(1).get(security.getFirst().indexOf("Valuation Metric Provenance")))
                .isEqualTo("{\"evSalesTTM\":{\"formula\":\"EV_DIVIDED_BY_TTM_REVENUE\","
                        + "\"source\":\"SEC\",\"asOf\":\"2026-09-15T00:00:00Z\",\"currency\":\"USD\"}}");
        assertThat(security.get(1).get(security.getFirst().indexOf("Display Currency"))).isEqualTo("USD");
        assertThat(security.get(1).get(security.getFirst().indexOf("Display Currency Source"))).isEqualTo("SEC");
        assertThat(security.get(1).get(security.getFirst().indexOf("Display Currency Source As Of")))
                .isEqualTo("2026-06-30T00:00:00Z");
        assertThat(security.get(1).get(security.getFirst().indexOf("Display Currency Period"))).isEqualTo("FY2026-Q2");
        assertThat(security.get(1).get(security.getFirst().indexOf("Display Currency Status"))).isEqualTo("OK");
        assertThat(security.get(1).get(security.getFirst().indexOf("Risk Status"))).isEqualTo("DATA_MISSING");
        assertThat(security.get(1).get(security.getFirst().indexOf("Thesis Failure Stress Status")))
                .isEqualTo("DATA_MISSING");
        assertThat(security.get(1).get(security.getFirst().indexOf("Top Two Correlated Status")))
                .isEqualTo("DATA_MISSING");
        assertThat(security.get(1).get(security.getFirst().indexOf("Risk Soft Budget Status")))
                .isEqualTo("DATA_MISSING");
        assertThat(security.get(1).get(security.getFirst().indexOf("Risk Sizing Eligible"))).isEqualTo(false);
        assertThat(security.get(1).get(security.getFirst().indexOf("Combined Portfolio Source")))
                .isEqualTo("TOSS_API+MANUAL_SHEET");
        assertThat(security.get(1).get(security.getFirst().indexOf("Combined Portfolio Status"))).isEqualTo("OK");
        assertThat(security.get(1).get(security.getFirst().indexOf("Account 1 As Of")))
                .isEqualTo("2026-09-16T20:00:00Z");
        assertThat(security.get(1).get(security.getFirst().indexOf("Manual Account As Of")))
                .isEqualTo("2026-09-16");
        assertThat(security.get(1).get(security.getFirst().indexOf("Manual Read At")))
                .isEqualTo("2026-09-16T20:02:00Z");
        assertThat(security.get(1).get(security.getFirst().indexOf("Manual Account Status"))).isEqualTo("OK");
        assertThat(security.get(1).get(security.getFirst().indexOf("Manual Account Stale"))).isEqualTo(false);
        assertThat(security.get(1).get(security.getFirst().indexOf("Risk Inputs Available"))).isEqualTo(true);
        assertThat(security.get(1).get(security.getFirst().indexOf("Position Source Coverage")))
                .isEqualTo("TOSS_API+MANUAL_SHEET");
        assertThat(security.get(1).get(security.getFirst().indexOf("Position Accounts Included")))
                .isEqualTo("ACCOUNT_1+ACCOUNT_2");
        assertThat(security.get(1).get(security.getFirst().indexOf("Position Quantity As Of")))
                .isEqualTo("2026-09-16T20:00:00Z");
        assertThat(security.get(1).get(security.getFirst().indexOf("Position Price As Of")))
                .isEqualTo("2026-09-16T20:01:00Z");
        assertThat(security.get(1).get(security.getFirst().indexOf("Position Manual As Of")))
                .isEqualTo("2026-09-16");
        assertThat(security.get(1).get(security.getFirst().indexOf("Revenue Revision 30D")))
                .isEqualTo(new BigDecimal("12.5"));
        assertThat(security.get(1).get(security.getFirst().indexOf("Revenue Revision 90D")))
                .isEqualTo("");
        assertThat(security.get(1).get(security.getFirst().indexOf("EPS Revision 30D")))
                .isEqualTo("");
        var consensusHistory = tabs.get(2).values();
        assertThat(consensusHistory.getFirst())
                .startsWith("Ticker", "As Of", "Horizon", "Revenue Consensus", "EPS Consensus",
                        "EBITDA Consensus", "FCF Consensus", "Source")
                .contains("Estimate Type", "Estimate Label", "Period End", "Revenue Analyst Count",
                        "EPS Analyst Count", "Currency");
        assertThat(consensusHistory.get(1)).containsExactly("AAPL", "2026-10-01T00:00:00Z", "2027-12-31",
                new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("10"), new BigDecimal("8"),
                "ALPHA_VANTAGE", "ANNUAL", "FY2027", "2027-12-31", 18, 21, "USD");
        assertThat(tabs.get(1).values().get(1)).contains("AAPL", "Keep growing subscriptions");

        var queries = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(queries.capture(), any(RowMapper.class), eq(USER_ID));
        assertThat(queries.getAllValues()).anyMatch(sql -> sql.contains("FROM consensus_snapshots"))
                .anyMatch(sql -> sql.contains("estimate_type") && sql.contains("estimate_label")
                        && sql.contains("period_end") && sql.contains("revenue_analyst_count")
                        && sql.contains("eps_analyst_count") && sql.contains("currency"))
                .anyMatch(sql -> sql.contains("FROM investment_decision_ledger"))
                .anyMatch(sql -> sql.contains("FROM monitoring_position_contexts"));
    }

    @Test
    void archivesNonemptyIncompatibleTabBeforeWritingCanonicalValues() throws Exception {
        var sheets = mock(GoogleSheetsClient.class);
        var originalIds = sheetIds();
        var verifiedIds = new java.util.LinkedHashMap<>(originalIds);
        verifiedIds.put("Security Snapshot Legacy before DB", 100);
        when(sheets.sheetIdsByTitle("sheet-1")).thenReturn(originalIds, verifiedIds);
        when(sheets.readValues(eq("sheet-1"), anyString())).thenAnswer(invocation -> {
            var range = (String) invocation.getArgument(1);
            var values = range.startsWith("'Security Snapshot'")
                    ? List.of(List.<Object>of("Legacy ticker"), List.<Object>of("AAPL", "manual"))
                    : List.<List<Object>>of();
            return new GoogleSheetsClient.SheetValues(range, values);
        });
        var sync = sync(sheets, mock(InvestmentContextService.class), mock(RiskPolicyService.class), mock(JdbcTemplate.class));

        sync.sync(USER_ID);

        var order = inOrder(sheets);
        order.verify(sheets).duplicateSheets("sheet-1", Map.of("Security Snapshot Legacy before DB", 1));
        order.verify(sheets).sheetIdsByTitle("sheet-1");
        order.verify(sheets).ensureSheets(eq("sheet-1"), any());
        order.verify(sheets).ensureSheetColumnCounts(eq("sheet-1"), any());
        order.verify(sheets).batchUpdateValues(eq("sheet-1"), any());
    }

    @Test
    void compatibleCanonicalTabSyncsWithoutCreatingLegacyArchive() throws Exception {
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.sheetIdsByTitle("sheet-1")).thenReturn(sheetIds());
        when(sheets.readValues(eq("sheet-1"), anyString())).thenAnswer(invocation -> {
            var range = (String) invocation.getArgument(1);
            var values = range.startsWith("'Watchlist'")
                    ? List.of(List.<Object>of("Ticker", "Status", "Levels", "Evidence", "Observed At",
                    "Created At", "Updated At"))
                    : range.startsWith("'Consensus History'")
                    ? List.of(List.<Object>of("Ticker", "As Of", "Horizon", "Revenue Consensus",
                    "EPS Consensus", "EBITDA Consensus", "FCF Consensus", "Source"))
                    : List.<List<Object>>of();
            return new GoogleSheetsClient.SheetValues(range, values);
        });
        var sync = sync(sheets, mock(InvestmentContextService.class), mock(RiskPolicyService.class), mock(JdbcTemplate.class));

        sync.sync(USER_ID);

        verify(sheets, never()).duplicateSheets(anyString(), anyMap());
        var updates = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(sheets).batchUpdateValues(eq("sheet-1"), updates.capture());
        @SuppressWarnings("unchecked")
        var tabs = (List<GoogleSheetsClient.SheetValueRange>) updates.getValue();
        verify(sheets).ensureSheetColumnCounts(eq("sheet-1"), argThat(columns ->
                columns.get("Security Snapshot") == tabs.getFirst().values().getFirst().size()));
        assertThat(tabs.get(2).values().getFirst())
                .startsWith("Ticker", "As Of", "Horizon", "Revenue Consensus", "EPS Consensus",
                        "EBITDA Consensus", "FCF Consensus", "Source")
                .contains("Estimate Type", "Currency");
    }

    @Test
    void appendsNewSecurityColumnsToExistingHeaderWithoutArchivingOrRebuildingTab() throws Exception {
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.sheetIdsByTitle("sheet-1")).thenReturn(sheetIds());
        when(sheets.readValues(eq("sheet-1"), anyString())).thenAnswer(invocation -> {
            var range = (String) invocation.getArgument(1);
            var oldSecurityHeaders = List.of("Ticker", "As Of", "Quantity", "Weight", "Currency",
                    "Regular Close", "Regular Close As Of", "Latest Price", "Latest Price As Of", "Session",
                    "Source", "Secondary Source", "Price Status", "Trend Status", "SMA20", "SMA50", "RSI14",
                    "Fundamental Fiscal Period", "Fundamental Reported At", "Fundamental As Of", "Fundamental Source",
                    "Market Cap", "Enterprise Value", "Cash", "Debt", "Diluted Shares", "Revenue TTM",
                    "Revenue Growth YoY", "EBITDA TTM", "EPS", "FCF TTM", "Fundamental Status",
                    "Balance Sheet Status", "Consensus As Of", "Consensus Horizon", "Consensus Source",
                    "Revenue Consensus", "EPS Consensus", "EBITDA Consensus", "FCF Consensus",
                    "Revenue Revision 30D", "Revenue Revision 90D", "EPS Revision 30D", "EPS Revision 90D",
                    "Valuation Status", "EV/Sales TTM", "EV/Sales Forward", "EV/EBITDA TTM",
                    "EV/EBITDA Forward", "Forward P/E", "FCF Yield TTM", "FCF Yield Forward",
                    "Normalized FCF", "Overall Data Status", "Missing Fields", "Thesis", "Risk");
            var values = range.equals("'Security Snapshot'!A:ZZ")
                    ? List.<List<Object>>of(new java.util.ArrayList<>(oldSecurityHeaders))
                    : List.<List<Object>>of();
            return new GoogleSheetsClient.SheetValues(range, values);
        });
        var sync = sync(sheets, mock(InvestmentContextService.class), mock(RiskPolicyService.class), mock(JdbcTemplate.class));

        sync.sync(USER_ID);

        verify(sheets, never()).duplicateSheets(anyString(), anyMap());
        var updates = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(sheets).batchUpdateValues(eq("sheet-1"), updates.capture());
        @SuppressWarnings("unchecked")
        var tabs = (List<GoogleSheetsClient.SheetValueRange>) updates.getValue();
        assertThat(tabs.getFirst().values().getFirst()).startsWith("Ticker", "As Of", "Quantity", "Weight")
                .contains("Basic Shares", "Market Cap Formula", "Field Provenance");
    }

    @Test
    void archiveFailurePreventsAllManagedTabWrites() throws Exception {
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.sheetIdsByTitle("sheet-1")).thenReturn(sheetIds());
        when(sheets.readValues(eq("sheet-1"), anyString())).thenAnswer(invocation -> {
            var range = (String) invocation.getArgument(1);
            var values = range.startsWith("'Security Snapshot'")
                    ? List.of(List.<Object>of("Legacy ticker"), List.<Object>of("AAPL", "manual"))
                    : List.<List<Object>>of();
            return new GoogleSheetsClient.SheetValues(range, values);
        });
        doThrow(GoogleSheetsException.network("archive rejected"))
                .when(sheets).duplicateSheets("sheet-1", Map.of("Security Snapshot Legacy before DB", 1));
        var sync = sync(sheets, mock(InvestmentContextService.class), mock(RiskPolicyService.class), mock(JdbcTemplate.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sync.sync(USER_ID))
                .isInstanceOf(GoogleSheetsException.class);

        verify(sheets, never()).ensureSheets(anyString(), any());
        verify(sheets, never()).batchUpdateValues(anyString(), any());
    }

    @Test
    void matchingExistingArchiveAllowsSafeRetryWithoutDuplicatingArchive() throws Exception {
        var sheets = mock(GoogleSheetsClient.class);
        var ids = new java.util.LinkedHashMap<>(sheetIds());
        ids.put("Security Snapshot Legacy before DB", 100);
        when(sheets.sheetIdsByTitle("sheet-1")).thenReturn(ids);
        when(sheets.readValues(eq("sheet-1"), anyString())).thenAnswer(invocation -> {
            var range = (String) invocation.getArgument(1);
            var values = range.startsWith("'Security Snapshot")
                    ? List.of(List.<Object>of("Legacy ticker"), List.<Object>of("AAPL", "manual"))
                    : List.<List<Object>>of();
            return new GoogleSheetsClient.SheetValues(range, values);
        });
        var sync = sync(sheets, mock(InvestmentContextService.class), mock(RiskPolicyService.class), mock(JdbcTemplate.class));

        sync.sync(USER_ID);

        verify(sheets, never()).duplicateSheets(anyString(), anyMap());
        verify(sheets).batchUpdateValues(eq("sheet-1"), any());
    }

    @Test
    void existingArchiveWithDifferentRowsBlocksOverwrite() throws Exception {
        var sheets = mock(GoogleSheetsClient.class);
        var ids = new java.util.LinkedHashMap<>(sheetIds());
        ids.put("Security Snapshot Legacy before DB", 100);
        when(sheets.sheetIdsByTitle("sheet-1")).thenReturn(ids);
        when(sheets.readValues(eq("sheet-1"), anyString())).thenAnswer(invocation -> {
            var range = (String) invocation.getArgument(1);
            var values = range.equals("'Security Snapshot'!A:ZZ")
                    ? List.of(List.<Object>of("Legacy ticker"), List.<Object>of("AAPL", "manual"))
                    : range.equals("'Security Snapshot Legacy before DB'!A:ZZ")
                    ? List.of(List.<Object>of("Legacy ticker"), List.<Object>of("MSFT", "older"))
                    : List.<List<Object>>of();
            return new GoogleSheetsClient.SheetValues(range, values);
        });
        var sync = sync(sheets, mock(InvestmentContextService.class), mock(RiskPolicyService.class), mock(JdbcTemplate.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sync.sync(USER_ID))
                .isInstanceOf(GoogleSheetsException.class)
                .hasMessageContaining("Legacy archive does not match current tab");

        verify(sheets, never()).ensureSheets(anyString(), any());
        verify(sheets, never()).batchUpdateValues(anyString(), any());
    }

    private static Map<String, Integer> sheetIds() {
        return Map.of("Security Snapshot", 1, "Thesis State", 2, "Consensus History", 3,
                "Watchlist", 4, "Decision Ledger", 5, "Alpha State", 6, "Risk Policy", 7);
    }

    private static InvestmentOsResearchSheetSync sync(
            GoogleSheetsClient sheets,
            InvestmentContextService investment,
            RiskPolicyService riskPolicies,
            JdbcTemplate jdbc
    ) throws Exception {
        when(riskPolicies.history(USER_ID, 100)).thenReturn(List.of());
        when(investment.context(USER_ID)).thenReturn(context());
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), eq(USER_ID));
        return new InvestmentOsResearchSheetSync(
                new InvestmentOsSheetProperties(true, "sheet-1", USER_ID, UUID.randomUUID(),
                        Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(2)),
                sheets, investment, riskPolicies, jdbc, new ObjectMapper());
    }

    private static InvestmentContextService.ContextView context() throws Exception {
        var mapper = new ObjectMapper();
        var contextAsOf = Instant.parse("2026-09-16T20:00:00Z");
        var position = new InvestmentContextService.PositionView(
                "AAPL", "Apple", new BigDecimal("4"), "USD", new BigDecimal("400"), new BigDecimal("0.4"),
                new BigDecimal("100"), contextAsOf, "ACCOUNT_1+ACCOUNT_2", "TOSS_API+MANUAL_SHEET",
                contextAsOf, Instant.parse("2026-09-16T20:01:00Z"), LocalDate.parse("2026-09-16"));
        var security = new InvestmentContextService.SecurityView(
                "AAPL", position, contextAsOf,
                mapper.readTree("""
                        {"regularClose":100,"regularCloseAsOf":"2026-09-16T20:00:00Z",
                         "latestPrice":101,"latestPriceAsOf":"2026-09-16T20:01:00Z",
                         "session":"REGULAR_CLOSE","source":"FMP","secondarySource":"POLYGON",
                         "status":"SOURCE_CONFLICT"}
                        """),
                mapper.readTree("{}"), mapper.readTree("""
                        {"fiscalPeriod":"FY2025","reportedAt":"2026-02-01T00:00:00Z",
                         "asOf":"2026-02-02T00:00:00Z","source":"FMP",
                         "basicShares":50,"basicSharesBasis":"SEC_INSTANT",
                         "marketCap":150,"marketCapAsOf":"2026-02-03T00:00:00Z",
                         "marketCapFormula":"TOSS_REGULAR_CLOSE * SEC_BASIC_SHARES",
                         "fullyDilutedMarketCap":250,"fullyDilutedMarketCapAsOf":"2026-02-04T00:00:00Z",
                         "fullyDilutedMarketCapFormula":"TOSS_REGULAR_CLOSE * SEC_DILUTED_SHARES",
                         "enterpriseValue":175,"enterpriseValueAsOf":"2026-02-05T00:00:00Z",
                         "enterpriseValueSource":"MARKET_CAP_PLUS_LATEST_DEBT_MINUS_LATEST_CASH",
                         "balanceSheetAsOf":"2026-02-05T00:00:00Z","currency":"USD",
                         "fieldProvenance":{"marketCap":{"source":"TOSS+SEC"},
                           "enterpriseValue":{"source":"MARKET_CAP_PLUS_LATEST_DEBT_MINUS_LATEST_CASH",
                             "formula":"marketCap + latestDebt - latestCash"},
                           "cash":{"source":"SEC","asOf":"2026-02-05T00:00:00Z"},
                           "debt":{"source":"SEC","asOf":"2026-02-06T00:00:00Z"}},
                         "ebitdaTTMType":"TTM_REPORTED","ebitdaTTMFormula":"REPORTED",
                         "ebitdaTTMSource":"SEC"}
                        """),
                mapper.readTree("""
                        {"asOf":"2026-09-15T00:00:00Z","horizon":"FY2026","source":"CONSENSUS",
                         "revenueConsensus":100,"epsConsensus":2,"ebitdaConsensus":10,"fcfConsensus":8,
                         "missingReason":"DAILY_QUOTA_EXHAUSTED","status":"PARTIAL",
                         "estimateType":"ANNUAL","estimateLabel":"FY2026","periodEnd":"2026-12-31",
                         "epsAnalystCount":21,"revenueAnalystCount":18,"currency":"USD"}
                        """),
                mapper.readTree("""
                        {"revenueRevision30D":{"value":12.5,"baselineAsOf":"2026-08-15T00:00:00Z","status":"OK"},
                         "revenueRevision90D":{"value":null,"baselineAsOf":null,"status":"INSUFFICIENT_HISTORY"},
                         "epsRevision30D":{"value":null,"baselineAsOf":null,"status":"DATA_MISSING"},
                         "epsRevision90D":{"value":2.5,"baselineAsOf":"2026-07-15T00:00:00Z","status":"OK"}}
                        """), mapper.readTree("""
                        {"status":"PARTIAL","ttmAsOf":"2026-09-15T00:00:00Z","ttmSource":"SEC",
                         "forwardAsOf":"2026-09-15T00:00:00Z","forwardHorizon":"FY2027",
                         "forwardSource":"CONSENSUS","forwardEstimateType":"ANNUAL",
                         "forwardPeriodEnd":"2027-12-31","forwardCurrency":null,
                         "metricStatuses":{"evSalesTTM":"OK","evSalesForward":"DATA_MISSING"},
                         "metricReasons":{"evSalesForward":"CONSENSUS_MISSING"},
                         "metricProvenance":{"evSalesTTM":{"formula":"EV_DIVIDED_BY_TTM_REVENUE",
                           "source":"SEC","asOf":"2026-09-15T00:00:00Z","currency":"USD"}},
                         "displayCurrency":"USD","displayCurrencySource":"SEC",
                         "displayCurrencySourceAsOf":"2026-06-30T00:00:00Z",
                         "displayCurrencyPeriod":"FY2026-Q2","displayCurrencyStatus":"OK"}
                        """), mapper.readTree("""
                        {"fundamentalStatus":"OK","balanceSheetStatus":"OK","overallDataStatus":"PARTIAL"}
                        """),
                new InvestmentContextService.ThesisView("AAPL", "Keep growing subscriptions", null, null,
                        null, null, null, null, "UNCONFIRMED", null, null, "GROWTH", Instant.parse("2026-09-16T20:00:00Z")),
                new InvestmentContextService.RiskContributionView(null, null, null,
                        com.jmj.trade.investment.InvestmentDataCalculator.DataStatus.DATA_MISSING,
                        null, com.jmj.trade.investment.InvestmentDataCalculator.DataStatus.DATA_MISSING,
                        null, null, null,
                        com.jmj.trade.investment.InvestmentDataCalculator.DataStatus.DATA_MISSING,
                        false, "DATA_MISSING"));
        var portfolio = new InvestmentContextService.PortfolioView(
                contextAsOf, List.of(position), Map.of("USD", new BigDecimal("1000")), false, List.of(), "OK",
                "TOSS_API+MANUAL_SHEET", contextAsOf, LocalDate.parse("2026-09-16"),
                Instant.parse("2026-09-16T20:02:00Z"), "OK", "SUCCEEDED", false, true);
        var policy = new RiskPolicyService.RiskPolicySnapshot(0, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, null, false);
        return new InvestmentContextService.ContextView(portfolio, List.of(security), List.of(), policy, List.of(), null);
    }

    private static Object mapConsensusRow(RowMapper<Object> mapper) throws SQLException {
        var resultSet = mock(ResultSet.class);
        when(resultSet.getString("ticker")).thenReturn("AAPL");
        when(resultSet.getObject("as_of", java.time.OffsetDateTime.class))
                .thenReturn(java.time.OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        when(resultSet.getString("horizon")).thenReturn("2027-12-31");
        when(resultSet.getString("estimate_type")).thenReturn("ANNUAL");
        when(resultSet.getString("estimate_label")).thenReturn("FY2027");
        when(resultSet.getObject("period_end", java.time.LocalDate.class))
                .thenReturn(java.time.LocalDate.parse("2027-12-31"));
        when(resultSet.getBigDecimal("revenue_consensus")).thenReturn(new BigDecimal("100"));
        when(resultSet.getObject("revenue_analyst_count", Integer.class)).thenReturn(18);
        when(resultSet.getBigDecimal("eps_consensus")).thenReturn(new BigDecimal("2"));
        when(resultSet.getObject("eps_analyst_count", Integer.class)).thenReturn(21);
        when(resultSet.getBigDecimal("ebitda_consensus")).thenReturn(new BigDecimal("10"));
        when(resultSet.getBigDecimal("fcf_consensus")).thenReturn(new BigDecimal("8"));
        when(resultSet.getString("currency")).thenReturn("USD");
        when(resultSet.getString("source")).thenReturn("ALPHA_VANTAGE");
        return mapper.mapRow(resultSet, 0);
    }
}
