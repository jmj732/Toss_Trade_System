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
    void tossQuotesUpdateHoldingsInBothAccountsWithoutChangingManualPositionData() {
        var syncedAt = "2026-09-20T00:00:00Z";
        var account = new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.accountHeaders(), List.of(
                List.of("ACCOUNT_1", "ABC", "HOLDING", "USD", "2", "10", "11", "22", "", "TOSS_API", "HIGH", syncedAt,
                        "TOSS_QUOTE_API", syncedAt),
                List.of("ACCOUNT_2", "XYZ", "HOLDING", "USD", "3", "20", "12", "36", "", "MANUAL", "HIGH", "manual-sync",
                        "MANUAL", "manual-price-time")));

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
        assertThat(InvestmentOsSheetModel.hasCompleteQuotes(updated)).isTrue();
    }

    @Test
    void orderUpsertIsIdempotentAndUsesBrokerStatusAndConfirmedFillOnly() {
        var existing = table(row("ACCOUNT_1", "order-1", "ABC", "OPEN"));
        var order = new ConnectorResponse.Order(
                "order-1", ConnectorResponse.BrokerOrderSide.BUY,
                ConnectorResponse.BrokerOrderType.LIMIT, "ABC", bd("2"), bd("1"),
                bd("10"), "USD", ConnectorResponse.BrokerOrderLifecycle.FILLED,
                ConnectorResponse.BrokerOrderGroup.CLOSED, SYNCED_AT, bd("9.5"), null, null);

        var once = InvestmentOsSheetModel.orders(existing, List.of(order), List.of(), SYNCED_AT);
        var twice = InvestmentOsSheetModel.orders(once, List.of(order), List.of(), SYNCED_AT);

        assertThat(twice.rows()).hasSize(1);
        var row = twice.rows().getFirst();
        assertThat(row.get(twice.column("Status"))).isEqualTo("FILLED");
        assertThat(row.get(twice.column("Average Filled Price"))).isEqualTo("9.5");
    }

    @Test
    void doesNotInferFillPriceFromLimitPrice() {
        var order = new ConnectorResponse.Order(
                "order-2", ConnectorResponse.BrokerOrderSide.BUY,
                ConnectorResponse.BrokerOrderType.LIMIT, "ABC", bd("2"), bd("1"),
                bd("10"), "USD", ConnectorResponse.BrokerOrderLifecycle.PARTIALLY_FILLED,
                ConnectorResponse.BrokerOrderGroup.OPEN, null, null, null, null);
        var updated = InvestmentOsSheetModel.orders(new InvestmentOsSheetModel.SheetTable(List.of(), List.of()),
                List.of(order), List.of(), SYNCED_AT);
        var row = updated.rows().getFirst();
        assertThat(row.get(updated.column("Average Filled Price"))).isBlank();
        assertThat(row.get(updated.column("Status"))).isEqualTo("PARTIALLY_FILLED");
    }

    @Test
    void failedBrokerSectionLeavesExistingRowsUntouched() {
        var existing = table(row("ACCOUNT_1", "order-1", "ABC", "OPEN"));

        var unchanged = InvestmentOsSheetModel.orders(existing, null, null, SYNCED_AT);

        assertThat(unchanged.rows()).hasSize(1);
        assertThat(unchanged.rows().getFirst().get(unchanged.column("Order ID")))
                .isEqualTo("order-1");
    }

    @Test
    void partialOrderReadAddsConfirmedRowsButDoesNotDeleteUnseenRows() {
        var existing = table(row("ACCOUNT_1", "order-old", "ABC", "OPEN"));
        var order = new ConnectorResponse.Order(
                "order-new", ConnectorResponse.BrokerOrderSide.SELL,
                ConnectorResponse.BrokerOrderType.MARKET, "XYZ", bd("1"), bd("0"),
                null, "USD", ConnectorResponse.BrokerOrderLifecycle.PENDING,
                ConnectorResponse.BrokerOrderGroup.OPEN, null, null, null, null);

        var updated = InvestmentOsSheetModel.orders(existing, List.of(order), null, SYNCED_AT);

        assertThat(updated.rows()).hasSize(2);
        assertThat(updated.rows()).extracting(row -> row.get(updated.column("Order ID")))
                .containsExactly("order-old", "order-new");
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
