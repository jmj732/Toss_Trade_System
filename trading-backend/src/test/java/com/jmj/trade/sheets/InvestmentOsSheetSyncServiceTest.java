package com.jmj.trade.sheets;

import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
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
import org.mockito.ArgumentCaptor;

class InvestmentOsSheetSyncServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final BrokerAccountRef BROKER_ACCOUNT =
            new BrokerAccountRef(CONNECTION_ID, "01", "GENERAL", "****0001");

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
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenReturn(List.of());
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenReturn(List.of(new ConnectorResponse.Order(
                "order-1", ConnectorResponse.BrokerOrderSide.BUY, ConnectorResponse.BrokerOrderType.LIMIT,
                "ABC", bd("2"), bd("2"), bd("10"), "USD", ConnectorResponse.BrokerOrderLifecycle.FILLED,
                ConnectorResponse.BrokerOrderGroup.CLOSED, Instant.now(), bd("9.5"), null, null)));

        var result = service(lease, connector, sheets).sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.SUCCEEDED);
        assertThat(result.fillsChanged()).isEqualTo(1);
        verify(connector, never()).fills(eq(USER_ID), eq(CONNECTION_ID), any());
        verify(connector).brokerAccount(CONNECTION_ID);
        verify(connector).orders(BROKER_ACCOUNT, "OPEN");
        verify(connector).orders(BROKER_ACCOUNT, "CLOSED");
        verify(sheets).batchUpdateValues(eq("sheet-1"), argThat(updates -> updates.size() == 5
                && updates.stream().anyMatch(update -> update.range().startsWith("'Orders'!A1"))
                && updates.stream().anyMatch(update -> update.range().startsWith("'Order History'!A1"))
                && updates.stream().anyMatch(update -> update.range().contains("Account State")
                && update.values().stream().anyMatch(row -> row.contains("ACCOUNT_1")))));
        verify(lease).release(any());
    }

    @Test
    void legacyOrdersAreArchivedBeforeTheMixedColumnsAreCleared() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var connector = mock(ConnectorService.class);
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());
        var legacyHeaders = List.of("asOf", "Asset", "Side", "Condition/Price", "Quantity", "Status",
                "Filled Price", "Notes", "Order ID", "Account", "Source", "Synced At", "Ticker", "Type",
                "Currency", "Filled Quantity", "Order Price", "Average Filled Price", "Filled At");
        var legacyOrder = List.of("", "", "BUY", "", "2", "FILLED", "", "", "closed-1", "ACCOUNT_1",
                "TOSS_API", "old-sync", "ABC", "LIMIT", "USD", "2", "10", "9.5", "2026-09-16T00:00:00Z");
        var legacyValues = new java.util.ArrayList<List<Object>>();
        legacyValues.add(new java.util.ArrayList<>(legacyHeaders));
        legacyValues.add(new java.util.ArrayList<>(legacyOrder));
        when(sheets.readValues(eq("sheet-1"), eq("'Orders'!A:Z"))).thenReturn(
                new GoogleSheetsClient.SheetValues("Orders!A:Z", legacyValues));
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio());
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenReturn(List.of());
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenReturn(List.of(new ConnectorResponse.Order(
                "closed-1", ConnectorResponse.BrokerOrderSide.BUY, ConnectorResponse.BrokerOrderType.LIMIT,
                "ABC", bd("2"), bd("2"), bd("10"), "USD", ConnectorResponse.BrokerOrderLifecycle.FILLED,
                ConnectorResponse.BrokerOrderGroup.CLOSED, NOW, bd("9.5"), null, null)));

        var result = service(lease, connector, sheets).sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.SUCCEEDED);
        ArgumentCaptor<List<GoogleSheetsClient.SheetValueRange>> updates = ArgumentCaptor.forClass(List.class);
        verify(sheets).batchUpdateValues(eq("sheet-1"), updates.capture());
        var orders = updates.getValue().stream().filter(update -> update.range().startsWith("'Orders'!"))
                .findFirst().orElseThrow();
        assertThat(orders.range()).isEqualTo("'Orders'!A1:S2");
        assertThat(orders.values().getFirst()).containsExactlyElementsOf(
                java.util.stream.Stream.concat(InvestmentOsSheetModel.orderHeaders().stream(),
                        java.util.stream.Stream.of("", "", "", "", "")).toList());
        assertThat(orders.values().get(1)).containsOnly("");
        var history = updates.getValue().stream().filter(update -> update.range().startsWith("'Order History'!"))
                .findFirst().orElseThrow();
        assertThat(history.values().get(1)).contains("closed-1", "FILLED", "9.5");
    }

    @Test
    void legacyOrdersStayUntouchedWhenClosedHistoryCouldNotBeFetched() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var connector = mock(ConnectorService.class);
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());
        var legacyValues = new java.util.ArrayList<List<Object>>();
        legacyValues.add(new java.util.ArrayList<>(List.of("asOf", "Asset", "Side", "Condition/Price", "Quantity",
                "Status", "Filled Price", "Notes", "Order ID", "Account", "Source", "Synced At", "Ticker",
                "Type", "Currency", "Filled Quantity", "Order Price", "Average Filled Price", "Filled At")));
        legacyValues.add(new java.util.ArrayList<>(List.of("", "", "BUY", "", "2", "FILLED", "", "",
                "closed-1", "ACCOUNT_1", "TOSS_API", "old-sync", "ABC", "LIMIT", "USD", "2", "10", "9.5", "old")));
        when(sheets.readValues(eq("sheet-1"), eq("'Orders'!A:Z"))).thenReturn(
                new GoogleSheetsClient.SheetValues("Orders!A:Z", legacyValues));
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio());
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenReturn(List.of());
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenThrow(new RuntimeException("timeout"));

        service(lease, connector, sheets).sync();

        verify(sheets).batchUpdateValues(eq("sheet-1"), argThat(updates -> updates.stream()
                .noneMatch(update -> update.range().startsWith("'Orders'!")
                        || update.range().startsWith("'Order History'!"))));
    }

    @Test
    void syncFetchesQuotesForHoldingsInBothAccounts() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var connector = mock(ConnectorService.class);
        var brokerSurface = mock(BrokerSurfaceService.class);
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());
        when(sheets.readValues(eq("sheet-1"), eq("'Account State'!A:Z"))).thenReturn(
                new GoogleSheetsClient.SheetValues("range", List.of(
                        List.of("Account", "Ticker", "Asset Type", "Currency", "Quantity", "Avg Cost",
                                "Current Price", "Market Value", "Cash", "Source", "Confidence", "Synced At",
                                "Price Source", "Price Synced At"),
                        List.of("ACCOUNT_1", "ABC", "HOLDING", "USD", "2", "10", "11", "22", "",
                                "TOSS_API", "HIGH", "old", "TOSS_QUOTE_API", "old"),
                        List.of("ACCOUNT_2", "XYZ", "HOLDING", "USD", "3", "20", "12", "36", "",
                                "MANUAL", "HIGH", "manual", "MANUAL", "manual"))));
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio());
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenReturn(List.of());
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenReturn(List.of());
        when(brokerSurface.prices(eq(USER_ID), eq(CONNECTION_ID), any())).thenAnswer(invocation ->
                BrokerSurfaceResponse.available(List.of(new BrokerSurfaceResponse.PriceView(
                        invocation.getArgument(2), bd("15"), null, null, "USD", NOW, NOW))));

        service(lease, connector, brokerSurface, sheets).sync();

        verify(brokerSurface).prices(USER_ID, CONNECTION_ID, "ABC");
        verify(brokerSurface).prices(USER_ID, CONNECTION_ID, "XYZ");
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
    void connectionFailureReportsSafePublicCodeAndPreservesAccountRows() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var connector = mock(ConnectorService.class);
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenThrow(BrokerConnectionException.notFound());
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());

        var result = service(lease, connector, sheets).sync();

        assertThat(result.error()).isEqualTo("BROKER_CONNECTION_NOT_FOUND");
        verify(sheets).batchUpdateValues(eq("sheet-1"), argThat(updates -> updates.size() == 1
                && updates.getFirst().range().contains("Reconciliation Log")));
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
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenThrow(new RuntimeException("timeout"));
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenReturn(List.of());
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());

        var result = service(lease, connector, sheets).sync();

        assertThat(result.outcome()).isEqualTo(InvestmentOsSheetSyncResult.Outcome.FAILED);
        verify(sheets).batchUpdateValues(eq("sheet-1"), argThat(updates -> updates.size() == 4
                && updates.stream().noneMatch(update -> update.range().startsWith("'Orders'!A1"))
                && updates.stream().anyMatch(update -> update.range().startsWith("'Order History'!A1"))));
    }

    @Test
    void brokerOrderFailureReportsSafeCategoryAndStatus() {
        var lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        var connector = mock(ConnectorService.class);
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio());
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenReturn(List.of());
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenThrow(new BrokerException(
                BrokerErrorCategory.RATE_LIMITED, 429, "private-error", "private-request-id", null,
                true, "token=must-not-leak"));
        var sheets = mock(GoogleSheetsClient.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(emptyValues());

        var result = service(lease, connector, sheets).sync();

        assertThat(result.error()).isEqualTo("CLOSED_ORDERS_FETCH_FAILED_BROKER_RATE_LIMITED_HTTP_429"
                + "+FILLS_NOT_DERIVED_ORDERS_UNAVAILABLE");
        assertThat(result.error()).doesNotContain("private", "token");
    }

    private InvestmentOsSheetSyncService service(
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            GoogleSheetsClient sheets
    ) {
        return service(lease, connector, null, sheets);
    }

    private InvestmentOsSheetSyncService service(
            InvestmentOsSheetLease lease,
            ConnectorService connector,
            BrokerSurfaceService brokerSurface,
            GoogleSheetsClient sheets
    ) {
        return new InvestmentOsSheetSyncService(
                new InvestmentOsSheetProperties(true, "sheet-1", USER_ID, CONNECTION_ID,
                        Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(2)),
                lease, connector, brokerSurface, sheets, () -> NOW);
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
