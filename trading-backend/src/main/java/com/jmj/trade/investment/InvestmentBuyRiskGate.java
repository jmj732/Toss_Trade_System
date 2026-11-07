package com.jmj.trade.investment;

import com.jmj.trade.investment.tactical.MarketRiskEvaluator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Horizon-aware BUY review gate. It evaluates evidence availability, not business thesis health. */
public final class InvestmentBuyRiskGate {

    private InvestmentBuyRiskGate() {}

    public static Decision evaluate(MarketRiskEvaluator.Assessment market,
                                    FundamentalVerification fundamentals,
                                    Instant evaluatedAt) {
        var horizon = market == null || market.investmentHorizon() == null
                ? "UNKNOWN" : market.investmentHorizon();
        var marketStatus = market == null ? "NOT_EVALUABLE" : market.marketDataStatus();
        var marketHealth = market == null ? "NOT_EVALUABLE" : market.marketHealth();
        var priceRiskState = market == null ? "NOT_EVALUABLE" : market.priceRiskState();
        var reasons = new ArrayList<String>();

        if ("UNKNOWN".equals(horizon) || !Set.of("TACTICAL", "STRATEGIC").contains(horizon)) {
            reasons.add("INVESTMENT_HORIZON_UNKNOWN");
            if (Set.of("STALE", "SOURCE_CONFLICT", "DATA_MISSING").contains(marketStatus)) {
                reasons.add("MARKET_DATA_" + safeCode(marketStatus));
                return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                        priceRiskState, fundamentals, evaluatedAt);
            }
            if (!"OK".equals(marketStatus)) reasons.add("MARKET_DATA_" + safeCode(marketStatus));
            return decision("REVIEW_REQUIRED", reasons, horizon, marketStatus, marketHealth,
                    priceRiskState, fundamentals, evaluatedAt);
        }
        if (!"OK".equals(marketStatus)) {
            reasons.add("MARKET_DATA_" + safeCode(marketStatus));
            return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                    priceRiskState, fundamentals, evaluatedAt);
        }
        if ("NOT_EVALUABLE".equals(marketHealth)) {
            reasons.add("MARKET_HEALTH_NOT_EVALUABLE");
            return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                    priceRiskState, fundamentals, evaluatedAt);
        }

        if ("TACTICAL".equals(horizon)) {
            if ("TRIGGER_BREACHED".equals(priceRiskState)) reasons.add("APPROVED_PRICE_TRIGGER_BREACHED");
            if (!"INTACT".equals(marketHealth)) reasons.add("TACTICAL_MARKET_RISK_" + safeCode(marketHealth));
            return decision(reasons.isEmpty() ? "ELIGIBLE" : "BLOCKED", reasons, horizon, marketStatus,
                    marketHealth, priceRiskState, fundamentals, evaluatedAt);
        }

        if (fundamentals == null || !"AVAILABLE".equals(fundamentals.status())) {
            if (fundamentals == null || fundamentals.reasons().isEmpty()) reasons.add("FUNDAMENTAL_VERIFICATION_UNAVAILABLE");
            else reasons.addAll(fundamentals.reasons());
            return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                    priceRiskState, fundamentals, evaluatedAt);
        }

        if ("TRIGGER_BREACHED".equals(priceRiskState)) reasons.add("APPROVED_PRICE_TRIGGER_REVIEW_REQUIRED");
        if (Set.of("IMPAIRED", "BREAKDOWN").contains(marketHealth)) {
            reasons.add("STRATEGIC_MARKET_RISK_REVIEW_REQUIRED_" + marketHealth);
        }
        return decision(reasons.isEmpty() ? "ELIGIBLE" : "REVIEW_REQUIRED", reasons, horizon, marketStatus,
                marketHealth, priceRiskState, fundamentals, evaluatedAt);
    }

    private static Decision decision(String status, List<String> reasons, String horizon, String marketStatus,
                                     String marketHealth, String priceRiskState,
                                     FundamentalVerification fundamentals, Instant evaluatedAt) {
        return new Decision(status, List.copyOf(reasons), horizon, marketStatus, marketHealth,
                priceRiskState, fundamentals, evaluatedAt);
    }

    private static String safeCode(String value) {
        return value == null || !value.matches("[A-Z0-9_]+") ? "UNKNOWN" : value;
    }

    public record FundamentalVerification(String status, List<String> reasons,
                                          String fundamentalsStatus, String source, Instant asOf,
                                          String revisionStatus, String consensusSource, Instant consensusAsOf) {
        public FundamentalVerification {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public record Decision(String status, List<String> reasons, String investmentHorizon,
                           String marketDataStatus, String marketHealth, String priceRiskState,
                           FundamentalVerification fundamentalVerification, Instant evaluatedAt) {
        public Decision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public Decision withStatusAndReasons(String nextStatus, List<String> nextReasons) {
            return new Decision(nextStatus, nextReasons, investmentHorizon, marketDataStatus, marketHealth,
                    priceRiskState, fundamentalVerification, evaluatedAt);
        }
    }
}
