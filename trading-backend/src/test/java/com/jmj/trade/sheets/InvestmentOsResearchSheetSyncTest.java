package com.jmj.trade.sheets;

import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
        assertThat(tabs).extracting(GoogleSheetsClient.SheetValueRange::range).containsExactly(
                "'Security Snapshot'!A1", "'Thesis State'!A1", "'Consensus History'!A1", "'Watchlist'!A1",
                "'Decision Ledger'!A1", "'Alpha State'!A1", "'Risk Policy'!A1");
        var security = tabs.getFirst().values();
        assertThat(security.getFirst()).contains("Ticker", "Latest Price", "Price Status", "Fundamental Status",
                "Fundamental Fiscal Period", "Fundamental Reported At", "Fundamental As Of", "Fundamental Source",
                "EBITDA Consensus", "FCF Consensus");
        assertThat(security.get(1)).contains("AAPL", new BigDecimal("101"), "REGULAR_CLOSE", "SOURCE_CONFLICT",
                "PARTIAL", "FY2025", "2026-02-01T00:00:00Z", "2026-02-02T00:00:00Z", "FMP",
                "2026-09-15T00:00:00Z", "FY2026", "CONSENSUS", new BigDecimal("10"), new BigDecimal("8"));
        assertThat(tabs.get(1).values().get(1)).contains("AAPL", "Keep growing subscriptions");

        var queries = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(queries.capture(), any(RowMapper.class), eq(USER_ID));
        assertThat(queries.getAllValues()).anyMatch(sql -> sql.contains("FROM consensus_snapshots"))
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
                    : List.<List<Object>>of();
            return new GoogleSheetsClient.SheetValues(range, values);
        });
        var sync = sync(sheets, mock(InvestmentContextService.class), mock(RiskPolicyService.class), mock(JdbcTemplate.class));

        sync.sync(USER_ID);

        verify(sheets, never()).duplicateSheets(anyString(), anyMap());
        verify(sheets).batchUpdateValues(eq("sheet-1"), any());
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
        var security = new InvestmentContextService.SecurityView(
                "AAPL", null, Instant.parse("2026-09-16T20:00:00Z"),
                mapper.readTree("""
                        {"regularClose":100,"regularCloseAsOf":"2026-09-16T20:00:00Z",
                         "latestPrice":101,"latestPriceAsOf":"2026-09-16T20:01:00Z",
                         "session":"REGULAR_CLOSE","source":"FMP","secondarySource":"POLYGON",
                         "status":"SOURCE_CONFLICT"}
                        """),
                mapper.readTree("{}"), mapper.readTree("""
                        {"fiscalPeriod":"FY2025","reportedAt":"2026-02-01T00:00:00Z",
                         "asOf":"2026-02-02T00:00:00Z","source":"FMP"}
                        """),
                mapper.readTree("""
                        {"asOf":"2026-09-15T00:00:00Z","horizon":"FY2026","source":"CONSENSUS",
                         "revenueConsensus":100,"epsConsensus":2,"ebitdaConsensus":10,"fcfConsensus":8}
                        """),
                mapper.readTree("{}"), mapper.readTree("{}"), mapper.readTree("""
                        {"fundamentalStatus":"OK","balanceSheetStatus":"OK","overallDataStatus":"PARTIAL"}
                        """),
                new InvestmentContextService.ThesisView("AAPL", "Keep growing subscriptions", null, null,
                        null, null, null, null, "UNCONFIRMED", null, null, "GROWTH", Instant.parse("2026-09-16T20:00:00Z")),
                null);
        var portfolio = new InvestmentContextService.PortfolioView(
                Instant.parse("2026-09-16T20:00:00Z"), List.of(), Map.of(), false, List.of(), "OK");
        var policy = new RiskPolicyService.RiskPolicySnapshot(0, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, null, false);
        return new InvestmentContextService.ContextView(portfolio, List.of(security), List.of(), policy, List.of(), null);
    }
}
