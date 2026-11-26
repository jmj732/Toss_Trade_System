package com.jmj.trade.investment;

import com.jmj.trade.broker.MarketDataAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TossStockHazardEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-11");

    @Test
    void cleanActiveCommonStockIsClear() {
        var result = TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK", null, "1.0", null), List.of(), TODAY);

        assertThat(result.status()).isEqualTo("CLEAR");
        assertThat(result.reasons()).isEmpty();
        assertThat(result.sharesOutstanding()).isEqualByComparingTo("1000");
    }

    @Test
    void nonActiveStatusBlocks() {
        var result = TossStockHazardEvaluator.evaluate(info("SCHEDULED", "STOCK", null, null, null), List.of(), TODAY);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).containsExactly("TOSS_STOCK_NOT_ACTIVE");
    }

    @Test
    void delistDateNotAfterTodayBlocksAndFutureDelistDateDoesNot() {
        var past = TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK", "2026-11-11", null, null), List.of(), TODAY);
        var sameDay = TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK", "2026-11-10", null, null), List.of(), TODAY);
        var future = TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK", "2026-12-01", null, null), List.of(), TODAY);

        assertThat(past.reasons()).containsExactly("TOSS_STOCK_DELISTING");
        assertThat(sameDay.reasons()).containsExactly("TOSS_STOCK_DELISTING");
        assertThat(future.status()).isEqualTo("CLEAR");
    }

    @Test
    void everyKnownWarningCodeBlocksWithItsOwnReason() {
        for (var code : List.of("LIQUIDATION_TRADING", "OVERHEATED", "INVESTMENT_WARNING", "INVESTMENT_RISK",
                "VI_STATIC_AND_DYNAMIC", "VI_STATIC", "VI_DYNAMIC", "STOCK_WARRANTS")) {
            var result = TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK", null, null, null),
                    List.of(new MarketDataAdapter.StockWarning(code)), TODAY);

            assertThat(result.status()).as(code).isEqualTo("BLOCKED");
            assertThat(result.reasons()).as(code).containsExactly("TOSS_STOCK_WARNING_" + code);
        }
    }

    @Test
    void unknownWarningCodeStillBlocksAsUnknown() {
        var result = TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK", null, null, null),
                List.of(new MarketDataAdapter.StockWarning("FUTURE_CODE")), TODAY);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).containsExactly("TOSS_STOCK_WARNING_UNKNOWN");
    }

    @Test
    void leveragedProductBlocksUnlessFactorIsOne() {
        var leveraged = TossStockHazardEvaluator.evaluate(info("ACTIVE", "ETF", null, "2", null), List.of(), TODAY);
        var unit = TossStockHazardEvaluator.evaluate(info("ACTIVE", "ETF", null, "1.0", null), List.of(), TODAY);

        assertThat(leveraged.reasons()).containsExactly("TOSS_LEVERAGED_PRODUCT");
        assertThat(unit.status()).isEqualTo("CLEAR");
    }

    @Test
    void warrantsSecurityTypeBlocks() {
        var result = TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK_WARRANTS", null, null, null),
                List.of(), TODAY);

        assertThat(result.reasons()).containsExactly("TOSS_SECURITY_TYPE_UNSUPPORTED");
    }

    @Test
    void missingReferenceDataIsUnavailableNotClear() {
        assertThat(TossStockHazardEvaluator.evaluate(null, List.of(), TODAY).status()).isEqualTo("UNAVAILABLE");
        assertThat(TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK", null, null, null), null, TODAY)
                .status()).isEqualTo("UNAVAILABLE");
        assertThat(TossStockHazardEvaluator.evaluate(info("ACTIVE", "STOCK", null, null, null), List.of(), null)
                .reasons()).containsExactly("TOSS_STOCK_HAZARD_UNAVAILABLE");
    }

    private static MarketDataAdapter.StockInfo info(String status, String securityType, String delistDate,
                                                    String leverage, String unused) {
        return new MarketDataAdapter.StockInfo("AAPL", status, securityType, true,
                delistDate == null ? null : LocalDate.parse(delistDate),
                leverage == null ? null : new BigDecimal(leverage), BigDecimal.valueOf(1000), "USD");
    }
}
