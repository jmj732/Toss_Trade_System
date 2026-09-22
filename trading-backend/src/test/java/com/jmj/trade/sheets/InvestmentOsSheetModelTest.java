package com.jmj.trade.sheets;

import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.connector.ConnectorResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentOsSheetModelTest {

    private static final Instant SYNCED_AT = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void canonicalAccountStateIncludesHoldingAndCashState() {
        var updated = InvestmentOsSheetModel.accountState(
                new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.accountHeaders(), List.of()),
                portfolio(position("ABC", "2", "10"), cash("USD", "100")), SYNCED_AT);

        assertThat(updated.rows()).filteredOn(row -> row.get(updated.column("Ticker")).equals("ABC"))
                .singleElement().satisfies(row -> assertThat(row.get(updated.column("State"))).isEqualTo("HELD"));
        assertThat(updated.rows()).filteredOn(row -> row.get(updated.column("Ticker")).equals("CASH_USD"))
                .singleElement().satisfies(row -> assertThat(row.get(updated.column("State")).toString()).isEqualTo("CASH"));
    }

    @Test
    void replacesAccount1FromConfirmedSnapshotAndPreservesAccount2() {
        var existing = table(
                row("ACCOUNT_1", "OLD", "HOLDING", "USD", "2", "10"),
                row("ACCOUNT_2", "KEEP", "HOLDING", "USD", "3", "20"));

        var updated = InvestmentOsSheetModel.accountState(
                existing,
                portfolio(position("NEW", "4", "12"), cash("USD", "100")),
                SYNCED_AT);

        assertThat(updated.rows()).extracting(row -> row.get(updated.column("Account")))
                .containsExactly("ACCOUNT_2", "ACCOUNT_1", "ACCOUNT_1");
        assertThat(updated.rows()).anySatisfy(row -> {
            assertThat(row.get(updated.column("Ticker"))).isEqualTo("NEW");
            assertThat(row.get(updated.column("Quantity"))).isEqualTo("4");
        });
        assertThat(updated.rows()).anySatisfy(row ->
                assertThat(row.get(updated.column("Ticker"))).isEqualTo("CASH_USD"));
    }

    @Test
    void mapsBothBrokerCashCurrenciesAndRemovesVanishedAccount1Position() {
        var existing = table(
                row("ACCOUNT_1", "OLD", "HOLDING", "USD", "2", "10"),
                row("ACCOUNT_2", "KEEP", "HOLDING", "USD", "3", "20"));
        var updated = InvestmentOsSheetModel.accountState(existing,
                new ConnectorResponse.Portfolio(SYNCED_AT, false, null, false, List.of(), List.of(), null,
                        List.of(), java.util.Map.of(
                                "USD", cash("USD", "100"), "KRW", cash("KRW", "200"))), SYNCED_AT);

        assertThat(updated.rows()).extracting(row -> row.get(updated.column("Ticker")))
                .containsExactly("KEEP", "CASH_KRW", "CASH_USD");
        assertThat(updated.rows()).filteredOn(row -> row.get(updated.column("Asset Type")).equals("CASH"))
                .extracting(row -> row.get(updated.column("Cash")))
                .containsExactly("200", "100");
    }

    @Test
    void replacesConfiguredAccount2AndPreservesAccount1() {
        var existing = table(
                row("ACCOUNT_1", "KEEP", "HOLDING", "USD", "2", "10"),
                row("ACCOUNT_2", "OLD", "HOLDING", "USD", "3", "20"));

        var updated = InvestmentOsSheetModel.accountState(
                existing,
                portfolio(position("NEW", "4", "12"), cash("USD", "100")),
                SYNCED_AT,
                InvestmentOsSheetModel.ACCOUNT_2);

        assertThat(updated.rows()).extracting(row -> row.get(updated.column("Account")))
                .containsExactly("ACCOUNT_1", "ACCOUNT_2", "ACCOUNT_2");
        assertThat(updated.rows()).anySatisfy(row ->
                assertThat(row.get(updated.column("Ticker"))).isEqualTo("NEW"));
        assertThat(updated.rows()).anySatisfy(row ->
                assertThat(row.get(updated.column("Ticker"))).isEqualTo("KEEP"));
    }

    @Test
    void aggregatesSameTickerWithQuantityWeightedAverageAndCash() {
        var account = table(
                row("ACCOUNT_1", "ABC", "HOLDING", "USD", "2", "10", "40"),
                row("ACCOUNT_2", "ABC", "HOLDING", "USD", "3", "20", "90"),
                cashRow("ACCOUNT_1", "USD", "100"),
                cashRow("ACCOUNT_2", "USD", "50"));

        var aggregate = InvestmentOsSheetModel.aggregate(account, SYNCED_AT);

        assertThat(aggregate.rows()).hasSize(2);
        var holding = aggregate.rows().stream()
                .filter(row -> row.get(aggregate.column("Ticker")).equals("ABC"))
                .findFirst().orElseThrow();
        assertThat(holding.get(aggregate.column("Quantity"))).isEqualTo("5");
        assertThat(holding.get(aggregate.column("Combined Avg Cost"))).isEqualTo("16");
        var cash = aggregate.rows().stream()
                .filter(row -> row.get(aggregate.column("Ticker")).equals("CASH_USD"))
                .findFirst().orElseThrow();
        assertThat(cash.get(aggregate.column("Cash"))).isEqualTo("150");
    }

    @Test
    void leavesCombinedAverageUnknownWhenOneAccountAverageIsMissing() {
        var account = table(
                row("ACCOUNT_1", "ABC", "HOLDING", "USD", "2", "10", "40"),
                row("ACCOUNT_2", "ABC", "HOLDING", "USD", "3", "", "90"));
        var aggregate = InvestmentOsSheetModel.aggregate(account, SYNCED_AT);
        var holding = aggregate.rows().stream()
                .filter(row -> row.get(aggregate.column("Ticker")).equals("ABC"))
                .findFirst().orElseThrow();
        assertThat(holding.get(aggregate.column("Quantity"))).isEqualTo("5");
        assertThat(holding.get(aggregate.column("Combined Avg Cost"))).isBlank();
    }

    @Test
    void leavesCombinedQuantityAndMarketValueUnknownWhenAnyAccountQuantityIsMissing() {
        var account = table(
                row("ACCOUNT_1", "ABC", "HOLDING", "USD", "2", "10", "40"),
                row("ACCOUNT_2", "ABC", "HOLDING", "USD", "", "20", "90"));

        var aggregate = InvestmentOsSheetModel.aggregate(account, SYNCED_AT);
        var holding = aggregate.rows().getFirst();

        assertThat(holding.get(aggregate.column("Quantity"))).isBlank();
        assertThat(holding.get(aggregate.column("Market Value"))).isBlank();
    }

    @Test
    void aggregateMarketValueUsesCombinedQuantityAndSharedLatestQuote() {
        var account = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.accountHeaders(), List.of(
                List.of("ACCOUNT_1", "ABC", "HOLDING", "USD", "0.333", "10", "1", "0.33", "", "TOSS_API", "HIGH", "now", "TOSS_QUOTE_API", "now"),
                List.of("ACCOUNT_2", "ABC", "HOLDING", "USD", "0.333", "20", "1", "0.33", "", "MANUAL", "HIGH", "manual", "TOSS_QUOTE_API", "now")));

        var aggregate = InvestmentOsSheetModel.aggregate(account, SYNCED_AT);

        assertThat(aggregate.rows().getFirst().get(aggregate.column("Market Value"))).isEqualTo("0.67");
    }

    @Test
    void updatesOnlyAccount1LastSyncInAccountRegistry() {
        var registry = new InvestmentOsSheetModel.SheetTable(
                List.of("Account", "Label", "Sync Mode", "Source", "Default Confidence", "Enabled", "Last Sync", "Notes"),
                List.of(List.of("ACCOUNT_1", "Toss", "AUTO", "TOSS_API", "HIGH", "TRUE", "old", "keep"),
                        List.of("ACCOUNT_2", "Manual", "MANUAL", "MANUAL", "MEDIUM", "TRUE", "manual-time", "manual-note")));

        var updated = InvestmentOsSheetModel.accountRegistry(registry, SYNCED_AT);

        assertThat(updated.rows().get(0)).containsExactly("ACCOUNT_1", "Toss", "AUTO", "TOSS_API", "HIGH", "TRUE", SYNCED_AT.toString(), "");
        assertThat(updated.rows().get(1)).containsExactlyElementsOf(registry.rows().get(1));
    }

    @Test
    void authoritativeAccount1SyncClearsLegacyManualNotes() {
        var existing = new InvestmentOsSheetModel.SheetTable(
                InvestmentOsSheetModel.accountHeaders().stream().toList(),
                List.of(List.of("ACCOUNT_1", "ABC", "HOLDING", "USD", "2", "10", "11", "22", "",
                        "TOSS_API", "HIGH", "old", "TOSS_QUOTE_API", "old", "HELD")));
        var legacy = new InvestmentOsSheetModel.SheetTable(
                List.of("asOf", "Account", "Asset", "Quantity", "Avg Cost", "Currency", "State", "Source",
                        "Confidence", "Synced At", "Notes", "Current Price", "Market Value", "Price Source", "Price Synced At"),
                List.of(List.of("2026-09-21", "ACCOUNT_1", "ABC", "2", "10", "USD", "HELD", "TOSS_API",
                        "HIGH", "old", "Fresh screenshot: stale", "11", "22", "TOSS_QUOTE_API", "old")));

        var updated = InvestmentOsSheetModel.accountState(legacy,
                portfolio(position("ABC", "2", "10"), cash("USD", "100")), SYNCED_AT);

        assertThat(updated.rows()).allSatisfy(row -> assertThat(row.get(updated.column("Notes"))).isBlank());
    }

    @Test
    void aggregateSourceCoverageDeduplicatesAccountAndQuoteSources() {
        var account = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.accountHeaders(), List.of(
                List.of("ACCOUNT_2", "ABC", "HOLDING", "USD", "3", "20", "15", "45", "", "USER_SCREENSHOT", "HIGH", "manual",
                        "TOSS_QUOTE_API", "now", "HELD"),
                List.of("ACCOUNT_1", "ABC", "HOLDING", "USD", "2", "10", "15", "30", "", "TOSS_API", "HIGH", "now",
                        "TOSS_QUOTE_API", "now", "HELD")));

        var aggregate = InvestmentOsSheetModel.aggregate(account, SYNCED_AT);

        var row = aggregate.rows().getFirst();
        assertThat(row.get(aggregate.column("Source Coverage")))
                .isEqualTo("USER_SCREENSHOT+TOSS_API+TOSS_QUOTE_API");
    }

    @Test
    void metricsKeepHighWaterMarkAndReportUsdValueWithoutInventingKrwFx() {
        var account = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.accountHeaders(), List.of(
                List.of("ACCOUNT_1", "ABC", "HOLDING", "USD", "5", "10", "20", "100", "", "TOSS_API", "HIGH", "now", "TOSS_QUOTE_API", "now"),
                List.of("ACCOUNT_1", "CASH_USD", "CASH", "USD", "", "", "", "", "20", "TOSS_API", "HIGH", "now", "", ""),
                List.of("ACCOUNT_1", "CASH_KRW", "CASH", "KRW", "", "", "", "", "500000", "TOSS_API", "HIGH", "now", "", ""),
                List.of("ACCOUNT_2", "CASH_USD", "CASH", "USD", "", "", "", "", "10", "MANUAL", "HIGH", "manual", "", "")));
        var metrics = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.metricsHeaders(), List.of(
                List.of("2026-09-15", "ACCOUNT_1", "180", "20", "11%", "200", "-10%", "", "", "old", "TOSS_API", "old")));

        var updated = InvestmentOsSheetModel.portfolioMetrics(metrics, account, SYNCED_AT);

        var account1 = updated.rows().stream().filter(row -> row.get(updated.column("Scope")).equals("ACCOUNT_1"))
                .findFirst().orElseThrow();
        assertThat(account1.get(updated.column("Total Value"))).isEqualTo("120");
        assertThat(account1.get(updated.column("High-water Mark"))).isEqualTo("200");
        assertThat(account1.get(updated.column("Drawdown %"))).isEqualTo("-40%");
        assertThat(account1.get(updated.column("Notes"))).contains("KRW cash excluded");
        assertThat(updated.rows()).anySatisfy(row -> assertThat(row.get(updated.column("Scope"))).isEqualTo("COMBINED"));
    }

    @Test
    void metricsPreservePriorSnapshotWhenUsdCashIsUnknown() {
        var account = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.accountHeaders(), List.of(
                List.of("ACCOUNT_1", "CASH_USD", "CASH", "USD", "", "", "", "", "", "TOSS_API", "HIGH", "old", "", "")));
        var previous = List.of("2026-09-15", "ACCOUNT_1", "123.45", "23.45", "19%", "150", "-17.7%", "", "", "previous", "TOSS_API", "old");
        var metrics = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.metricsHeaders(), List.of(previous));

        var updated = InvestmentOsSheetModel.portfolioMetrics(metrics, account, SYNCED_AT);

        assertThat(updated.rows()).containsExactly(previous);
    }

    @Test
    void tossQuotesUpdateHoldingsInBothAccountsWithoutChangingManualPositionData() {
        var syncedAt = "2026-09-20T00:00:00Z";
        var account = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.accountHeaders(), List.of(
                List.of("ACCOUNT_1", "ABC", "HOLDING", "USD", "2", "10", "11", "22", "", "TOSS_API", "HIGH", syncedAt,
                        "TOSS_QUOTE_API", syncedAt),
                List.of("ACCOUNT_2", "XYZ", "HOLDING", "USD", "3", "20", "12", "36", "", "MANUAL", "HIGH", "manual-sync",
                        "MANUAL", "manual-price-time"),
                List.of("ACCOUNT_2", "CASH_USD", "CASH", "USD", "", "", "", "", "19.24", "MANUAL", "HIGH", "manual-cash",
                        "", "")));

        assertThat(InvestmentOsSheetModel.heldSymbols(account)).containsExactly("ABC", "XYZ");
        var updated = InvestmentOsSheetModel.refreshPrices(account, List.of(
                new BrokerSurfaceResponse.PriceView("ABC", bd("15"), null, null, "USD", SYNCED_AT, null),
                new BrokerSurfaceResponse.PriceView("XYZ", bd("25"), null, null, "USD", SYNCED_AT, null)),
                SYNCED_AT);

        var account1 = updated.rows().getFirst();
        var account2 = updated.rows().get(1);
        assertThat(account1.get(updated.column("Current Price"))).isEqualTo("15");
        assertThat(account1.get(updated.column("Market Value"))).isEqualTo("30");
        assertThat(account2.get(updated.column("Ticker"))).isEqualTo("XYZ");
        assertThat(account2.get(updated.column("Quantity"))).isEqualTo("3");
        assertThat(account2.get(updated.column("Avg Cost"))).isEqualTo("20");
        assertThat(account2.get(updated.column("Source"))).isEqualTo("MANUAL");
        assertThat(account2.get(updated.column("Current Price"))).isEqualTo("25");
        assertThat(account2.get(updated.column("Market Value"))).isEqualTo("75");
        assertThat(account2.get(updated.column("Price Source"))).isEqualTo("TOSS_QUOTE_API");
        assertThat(updated.rows().get(2).get(updated.column("Cash"))).isEqualTo("19.24");
        assertThat(updated.rows().get(2).get(updated.column("Source"))).isEqualTo("MANUAL");
        assertThat(InvestmentOsSheetModel.hasCompleteQuotes(updated)).isTrue();
    }

    @Test
    void ordersOnlyContainsConfirmedOpenOrdersInCanonicalColumns() {
        var oldHistory = new ConnectorResponse.Order(
                "filled-1", ConnectorResponse.BrokerOrderSide.BUY,
                ConnectorResponse.BrokerOrderType.LIMIT, "OPEN", bd("2"), bd("2"),
                bd("10"), "USD", ConnectorResponse.BrokerOrderLifecycle.FILLED,
                ConnectorResponse.BrokerOrderGroup.CLOSED, SYNCED_AT, bd("9.5"), null, null);
        var open = new ConnectorResponse.Order(
                "pending-1", ConnectorResponse.BrokerOrderSide.BUY,
                ConnectorResponse.BrokerOrderType.LIMIT, "ABC", bd("2"), bd("1"),
                bd("10"), "USD", ConnectorResponse.BrokerOrderLifecycle.PARTIALLY_FILLED,
                ConnectorResponse.BrokerOrderGroup.OPEN, null, null, null, null);

        var updated = InvestmentOsSheetModel.openOrders(table(
                        row("ACCOUNT_1", "stale-open-1", "ABC", "PENDING"),
                        row("ACCOUNT_2", "manual-1", "XYZ", "PENDING"),
                        row("ACCOUNT_2", "manual-filled", "XYZ", "FILLED")),
                List.of(open, oldHistory, open), SYNCED_AT);

        assertThat(updated.headers()).containsExactlyElementsOf(InvestmentOsSheetModel.orderHeaders());
        assertThat(updated.rows()).hasSize(2);
        assertThat(updated.rows()).extracting(row -> row.get(updated.column("Order ID")))
                .containsExactly("manual-1", "pending-1");
        assertThat(updated.rows().get(1).get(updated.column("Status"))).isEqualTo("PARTIALLY_FILLED");
    }

    @Test
    void doesNotInferFillPriceFromLimitPrice() {
        var order = new ConnectorResponse.Order(
                "order-2", ConnectorResponse.BrokerOrderSide.BUY,
                ConnectorResponse.BrokerOrderType.LIMIT, "ABC", bd("2"), bd("1"),
                bd("10"), "USD", ConnectorResponse.BrokerOrderLifecycle.PARTIALLY_FILLED,
                ConnectorResponse.BrokerOrderGroup.OPEN, null, null, null, null);
        var updated = InvestmentOsSheetModel.openOrders(new InvestmentOsSheetModel.SheetTable(List.of(), List.of()),
                List.of(order), SYNCED_AT);
        var row = updated.rows().getFirst();
        assertThat(row.get(updated.column("Average Filled Price"))).isBlank();
        assertThat(row.get(updated.column("Status"))).isEqualTo("PARTIALLY_FILLED");
    }

    @Test
    void failedBrokerSectionLeavesExistingRowsUntouched() {
        var existing = table(row("ACCOUNT_1", "order-1", "ABC", "OPEN"));

        var unchanged = InvestmentOsSheetModel.openOrders(existing, null, SYNCED_AT);

        assertThat(unchanged.rows()).hasSize(1);
        assertThat(unchanged.rows().getFirst().get(unchanged.column("Order ID")))
                .isEqualTo("order-1");
    }

    @Test
    void orderHistoryRowsRemainIdempotentAcrossSyncs() {
        var existing = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.orderHeaders(), List.of());
        var order = new ConnectorResponse.Order(
                "new-order", ConnectorResponse.BrokerOrderSide.SELL, ConnectorResponse.BrokerOrderType.MARKET,
                "XYZ", bd("1"), bd("1"), null, "USD", ConnectorResponse.BrokerOrderLifecycle.CANCELED,
                ConnectorResponse.BrokerOrderGroup.CLOSED, SYNCED_AT, bd("12"), null, null,
                InvestmentOsSheetModel.ORDER_HISTORY_CUTOVER);

        var once = InvestmentOsSheetModel.orderHistory(existing, List.of(), List.of(order),
                new InvestmentOsSheetModel.SheetTable(List.of(), List.of()), SYNCED_AT,
                InvestmentOsSheetModel.ACCOUNT_1);
        var twice = InvestmentOsSheetModel.orderHistory(once, List.of(), List.of(order),
                new InvestmentOsSheetModel.SheetTable(List.of(), List.of()), SYNCED_AT,
                InvestmentOsSheetModel.ACCOUNT_1);

        assertThat(twice.rows()).hasSize(1);
        assertThat(twice.headers()).contains("Ordered At");
        assertThat(twice.rows().getFirst().get(twice.column("Order ID"))).isEqualTo("new-order");
        assertThat(twice.rows().getFirst().get(twice.column("Ordered At"))).isEqualTo("2026-09-21T12:35:53Z");
        assertThat(twice.rows().getFirst().get(twice.column("Status"))).isEqualTo("CANCELED");
    }

    @Test
    void orderHistoryStoresOnlyTerminalOrdersAndRefreshesByAccountAndBrokerId() {
        var existing = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.orderHeaders(), List.of(
                List.of("ACCOUNT_1", "active-old", "ABC", "BUY", "LIMIT", "USD", "1", "0", "10", "", "PENDING", "", "TOSS_API", "old", "2026-09-22T00:00:00Z")));
        var orderedAt = InvestmentOsSheetModel.ORDER_HISTORY_CUTOVER.plusSeconds(1);
        var open = new ConnectorResponse.Order("active-new", ConnectorResponse.BrokerOrderSide.BUY,
                ConnectorResponse.BrokerOrderType.LIMIT, "ABC", bd("1"), bd("0"), bd("10"), "USD",
                ConnectorResponse.BrokerOrderLifecycle.PARTIALLY_FILLED, ConnectorResponse.BrokerOrderGroup.OPEN,
                null, null, null, null, orderedAt);
        var filled = new ConnectorResponse.Order("active-new", ConnectorResponse.BrokerOrderSide.BUY,
                ConnectorResponse.BrokerOrderType.LIMIT, "ABC", bd("1"), bd("1"), bd("10"), "USD",
                ConnectorResponse.BrokerOrderLifecycle.FILLED, ConnectorResponse.BrokerOrderGroup.CLOSED,
                SYNCED_AT, bd("9.8"), null, null, orderedAt);

        var history = InvestmentOsSheetModel.orderHistory(existing, List.of(open), List.of(filled),
                new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.orderHeaders(), List.of()),
                SYNCED_AT, InvestmentOsSheetModel.ACCOUNT_1);

        assertThat(history.rows()).hasSize(1);
        assertThat(history.rows().getFirst().get(history.column("Order ID"))).isEqualTo("active-new");
        assertThat(history.rows().getFirst().get(history.column("Status"))).isEqualTo("FILLED");
        assertThat(history.rows().getFirst().get(history.column("Filled Quantity"))).isEqualTo("1");
        assertThat(history.rows().getFirst().get(history.column("Average Filled Price"))).isEqualTo("9.8");
    }

    @Test
    void doesNotInferOrderAgeWhenBrokerOrderTimeIsMissing() {
        var order = new ConnectorResponse.Order(
                "unknown-time", ConnectorResponse.BrokerOrderSide.BUY, ConnectorResponse.BrokerOrderType.LIMIT,
                "ABC", bd("1"), bd("1"), bd("10"), "USD", ConnectorResponse.BrokerOrderLifecycle.FILLED,
                ConnectorResponse.BrokerOrderGroup.CLOSED, SYNCED_AT, bd("10"), null, null);

        var history = InvestmentOsSheetModel.orderHistory(
                new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.orderHeaders(), List.of()),
                List.of(), List.of(order), new InvestmentOsSheetModel.SheetTable(List.of(), List.of()),
                SYNCED_AT, InvestmentOsSheetModel.ACCOUNT_1);

        assertThat(history.rows()).isEmpty();
    }

    @Test
    void doesNotMigrateLegacyRowsWithoutAnOrderTimestamp() {
        var legacy = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.orderHeaders(), List.of(
                List.of("ACCOUNT_1", "legacy-1", "ABC", "BUY", "LIMIT", "USD", "2", "2", "10", "9.5",
                        "FILLED", "2026-09-16T00:00:00Z", "TOSS_API", "old-sync")));

        var history = InvestmentOsSheetModel.orderHistory(
                new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.orderHeaders(), List.of()),
                List.of(), legacy, SYNCED_AT, InvestmentOsSheetModel.ACCOUNT_1);

        assertThat(history.rows()).isEmpty();
    }

    @Test
    void failedClosedOrderReadLeavesHistoryUntouched() {
        var existing = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.orderHeaders(),
                List.of(List.of("ACCOUNT_1", "old", "ABC", "BUY", "LIMIT", "USD", "1", "0", "10", "",
                        "PENDING", "", "TOSS_API", "old")));

        var unchanged = InvestmentOsSheetModel.orderHistory(existing, null, SYNCED_AT);

        assertThat(unchanged).isEqualTo(existing);
    }

    @Test
    void orderHistoryDropsPreCutoverRowsAndKeepsRowsPlacedAtOrAfterCutover() {
        var headers = List.of("Account", "Order ID", "Ticker", "Side", "Type", "Currency", "Quantity",
                "Filled Quantity", "Order Price", "Average Filled Price", "Status", "Filled At", "Source",
                "Synced At", "Ordered At");
        var previousOrder = List.of("ACCOUNT_1", "old-order", "ABC", "BUY", "LIMIT", "USD", "1", "1",
                "10", "10", "FILLED", "2026-09-20T12:00:00Z", "TOSS_API", "old-sync",
                "2026-09-20T11:59:59Z");
        var newOrder = List.of("ACCOUNT_1", "new-order", "XYZ", "BUY", "LIMIT", "USD", "1", "1",
                "20", "20", "FILLED", "2026-09-21T12:35:54Z", "TOSS_API", "new-sync", "2026-09-21T12:35:53Z");
        var existing = new InvestmentOsSheetModel.SheetTable(headers, List.of(previousOrder, newOrder));

        var history = InvestmentOsSheetModel.orderHistory(existing, List.of(), SYNCED_AT);

        assertThat(history.rows()).hasSize(1);
        assertThat(history.rows().getFirst().get(history.column("Order ID"))).isEqualTo("new-order");
        assertThat(history.headers()).contains("Ordered At");
        assertThat(history.rows().getFirst().get(history.column("Ordered At")))
                .isEqualTo("2026-09-21T12:35:53Z");
    }

    @Test
    void repeatedIdenticalReconciliationReplacesLatestRowInsteadOfAppending() {
        var empty = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.reconciliationHeaders(), List.of());
        var first = InvestmentOsSheetModel.reconciliation(empty, "sync-1", "ACCOUNT_1", "OK", "OK", "OK", "OK",
                "OK", 0, "NONE", true, SYNCED_AT, null);
        var second = InvestmentOsSheetModel.reconciliation(first, "sync-2", "ACCOUNT_1", "OK", "OK", "OK", "OK",
                "OK", 0, "NONE", true, SYNCED_AT.plusSeconds(300), null);

        assertThat(second.rows()).hasSize(1);
        assertThat(second.rows().getFirst().get(second.column("Sync ID"))).isEqualTo("sync-2");
        assertThat(second.rows().getFirst().get(second.column("Checked At")))
                .isEqualTo(SYNCED_AT.plusSeconds(300).toString());
    }

    private static InvestmentOsSheetModel.SheetTable table(List<String>... rows) {
        return new InvestmentOsSheetModel.SheetTable(
                List.of("Account", "Ticker", "Asset Type", "Currency", "Quantity", "Avg Cost",
                        "Market Value", "Order ID", "Status"), List.of(rows));
    }

    private static List<String> row(String account, String ticker, String type, String currency,
                                    String quantity, String avgCost) {
        return row(account, ticker, type, currency, quantity, avgCost, "");
    }

    private static List<String> row(String account, String orderId, String ticker, String status) {
        return List.of(account, ticker, "", "", "", "", "", orderId, status);
    }

    private static List<String> row(String account, String ticker, String type, String currency,
                                    String quantity, String avgCost, String marketValue) {
        return List.of(account, ticker, type, currency, quantity, avgCost, marketValue, "", "");
    }

    private static List<String> cashRow(String account, String currency, String amount) {
        return List.of(account, "CASH_" + currency, "CASH", currency, "", "", "", "", "", "", amount);
    }

    private static ConnectorResponse.Position position(String symbol, String quantity, String avg) {
        return new ConnectorResponse.Position(symbol, symbol, "US", bd(quantity), "USD", bd(avg),
                bd("15"), bd("48"), bd("60"), bd("60"), bd("12"), bd("12"), bd("0.2"),
                bd("0.2"), bd("0"), bd("0"), bd("0"), bd("0"), bd("0"), SYNCED_AT);
    }

    private static ConnectorResponse.BuyingPower cash(String currency, String amount) {
        return new ConnectorResponse.BuyingPower(bd(amount), SYNCED_AT);
    }

    private static ConnectorResponse.Portfolio portfolio(
            ConnectorResponse.Position position,
            ConnectorResponse.BuyingPower cash
    ) {
        return new ConnectorResponse.Portfolio(SYNCED_AT, false, null, false, List.of(), List.of(),
                null, List.of(position), java.util.Map.of(cash == null ? "USD" : "USD", cash));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
