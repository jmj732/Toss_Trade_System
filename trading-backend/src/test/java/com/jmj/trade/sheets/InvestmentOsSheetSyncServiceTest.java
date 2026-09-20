package com.jmj.trade.sheets;

import com.jmj.trade.connector.ConnectorResponse;
import com.jmj.trade.connector.ConnectorService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InvestmentOsSheetSyncServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void concurrentSyncSkipsBrokerAndSheetWrites() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(false);
        var connector = mock(ConnectorService.class);
        var sheets = mock(GoogleSheetsClient.class);

        var result = service(lease, connector, sheets).sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.SKIPPED);
        verifyNoInteractions(connector, sheets);
    }

    @Test
    void successfulSyncReadsBrokerSnapshotAndPublishesAllManagedTabs() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var connector = mock(ConnectorService.class);
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio());
        when(connector.orders(USER_ID, CONNECTION_ID, "OPEN")).thenReturn(List.of());
        when(connector.orders(USER_ID, CONNECTION_ID, "CLOSED")).thenReturn(List.of());

        var result = service(lease, connector, sheets).sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.SUCCEEDED);
        verify(connector).fills(eq(USER_ID), eq(CONNECTION_ID), any());
        verify(sheets).batchUpdateValues(eq("sheet-1"), argThat(updates -> updates.size() == 4
                && updates.stream().anyMatch(update -> update.range().contains("Account State")
                && update.values().stream().anyMatch(row -> row.contains("ACCOUNT_2")))));
        verify(lease).release(any());
    }

    @Test
    void brokerFailureDoesNotPublishAZeroSnapshot() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var connector = mock(ConnectorService.class);
        doThrow(new RuntimeException("timeout")).when(connector).portfolio(USER_ID, CONNECTION_ID);
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());

        var result = service(lease, connector, sheets).sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.FAILED);
        verify(sheets, never()).batchUpdateValues(eq("sheet-1"), argThat(updates ->
                updates.stream().anyMatch(update -> update.range().contains("Account State"))));
        verify(connector).portfolio(USER_ID, CONNECTION_ID);
    }

    @Test
    void googleReadFailureDoesNotWriteAnything() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenThrow(new RuntimeException("google down"));

        var result = service(lease, mock(ConnectorService.class), sheets).sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.FAILED);
        verify(sheets, never()).batchUpdateValues(any(), any());
        verify(lease).release(any());
    }

    @Test
    void partialOrderFailurePreservesUnseenOrdersAndMarksSyncFailed() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var connector = mock(ConnectorService.class);
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio());
        when(connector.orders(USER_ID, CONNECTION_ID, "OPEN")).thenThrow(new RuntimeException("timeout"));
        when(connector.orders(USER_ID, CONNECTION_ID, "CLOSED")).thenReturn(List.of());
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());

        var result = service(lease, connector, sheets).sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.FAILED);
        verify(sheets).batchUpdateValues(eq("sheet-1"), argThat(updates -> updates.size() == 4));
    }

    private InvestmentOsSheetSyncService service(
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            GoogleSheetsClient sheets
    ) {
        return new InvestmentOsSheetSyncService(
                new InvestmentOsSheetProperties(true, "sheet-1", USER_ID, CONNECTION_ID,
                        Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(2)),
                lease, connector, null, sheets, Instant::now);
    }

    private static GoogleSheetsClient.SheetValues emptyValues() {
        return new GoogleSheetsClient.SheetValues("range", List.of());
    }

    private static ConnectorResponse.Portfolio portfolio() {
        var position = new ConnectorResponse.Position("ABC", "ABC", "US", bd("2"), "USD", bd("10"),
                bd("11"), bd("20"), bd("22"), bd("22"), bd("2"), bd("2"), bd("0.1"), bd("0.1"),
                bd("0"), bd("0"), bd("0"), bd("0"), bd("2"), NOW);
        return new ConnectorResponse.Portfolio(NOW, false, null, false, List.of(), List.of(), null,
                List.of(position), java.util.Map.of(
                        "USD", new ConnectorResponse.BuyingPower(bd("100"), NOW),
                        "KRW", new ConnectorResponse.BuyingPower(bd("0"), NOW)));
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
