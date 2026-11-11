package com.jmj.trade.investment;

import com.jmj.trade.broker.MarketDataAdapter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure mapping from Toss stock reference data to BUY hazards. Any missing input is UNAVAILABLE (fail-closed). */
final class TossStockHazardEvaluator {

    private static final Set<String> KNOWN_WARNING_CODES = Set.of(
            "LIQUIDATION_TRADING", "OVERHEATED", "INVESTMENT_WARNING", "INVESTMENT_RISK",
            "VI_STATIC_AND_DYNAMIC", "VI_STATIC", "VI_DYNAMIC", "STOCK_WARRANTS");

    private TossStockHazardEvaluator() {}

    static InvestmentBuyRiskGate.TossStockHazards evaluate(MarketDataAdapter.StockInfo info,
                                                           List<MarketDataAdapter.StockWarning> warnings,
                                                           LocalDate today) {
        if (info == null || warnings == null || today == null) {
            return InvestmentBuyRiskGate.TossStockHazards.unavailable();
        }
        var reasons = new ArrayList<String>();
        if (!"ACTIVE".equals(info.status())) reasons.add("TOSS_STOCK_NOT_ACTIVE");
        if (info.delistDate() != null && !info.delistDate().isAfter(today)) reasons.add("TOSS_STOCK_DELISTING");
        for (var warning : warnings) {
            if (warning == null || warning.warningType() == null) {
                return InvestmentBuyRiskGate.TossStockHazards.unavailable();
            }
            var code = warning.warningType();
            reasons.add(KNOWN_WARNING_CODES.contains(code)
                    ? "TOSS_STOCK_WARNING_" + code : "TOSS_STOCK_WARNING_UNKNOWN");
        }
        if (info.leverageFactor() != null && info.leverageFactor().compareTo(BigDecimal.ONE) != 0) {
            reasons.add("TOSS_LEVERAGED_PRODUCT");
        }
        if ("STOCK_WARRANTS".equals(info.securityType())) reasons.add("TOSS_SECURITY_TYPE_UNSUPPORTED");
        return new InvestmentBuyRiskGate.TossStockHazards(
                reasons.isEmpty() ? InvestmentBuyRiskGate.HAZARD_CLEAR : InvestmentBuyRiskGate.HAZARD_BLOCKED,
                reasons, info.sharesOutstanding());
    }
}
