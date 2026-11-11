package com.jmj.trade.investment;

import com.jmj.trade.investment.tactical.MarketRiskEvaluator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Horizon-aware BUY review gate. It evaluates evidence availability, not business thesis health. */
public final class InvestmentBuyRiskGate {

    public static final String POLICY_PROVIDERS = "PROVIDERS";
    public static final String POLICY_TOSS_ONLY = "TOSS_ONLY";
    public static final String EVIDENCE_EXTERNAL_CITED = "EXTERNAL_CITED";
    public static final String TOSS_SOURCE_UNSUPPORTED = "TOSS_SOURCE_UNSUPPORTED";
    public static final String HAZARD_CLEAR = "CLEAR";
    public static final String HAZARD_BLOCKED = "BLOCKED";
    public static final String HAZARD_UNAVAILABLE = "UNAVAILABLE";

    private InvestmentBuyRiskGate() {}

    /** Parses the configured source policy. Blank selects the default; any other unknown value is rejected. */
    public static String parsePolicy(String raw) {
        if (raw == null || raw.isBlank()) return POLICY_PROVIDERS;
        var normalized = raw.trim();
        if (POLICY_PROVIDERS.equals(normalized) || POLICY_TOSS_ONLY.equals(normalized)) return normalized;
        throw new IllegalArgumentException("investment.data.fundamental-source-policy must be PROVIDERS or TOSS_ONLY");
    }

    /** Legacy evaluation used by portfolio views: no live BUY checks, PROVIDERS semantics. */
    public static Decision evaluate(MarketRiskEvaluator.Assessment market,
                                    FundamentalVerification fundamentals,
                                    Instant evaluatedAt) {
        return evaluate(market, fundamentals, evaluatedAt, null);
    }

    /**
     * BUY evaluation. A non-null {@code buy} enables the live checks that apply to every BUY prepare and submit:
     * quote sanity and Toss stock hazards on both horizons. Under TOSS_ONLY, a STRATEGIC horizon whose only
     * fundamental gap is the Toss-unsupported marker reaches REVIEW_REQUIRED with the EXTERNAL_CITED evidence mode.
     */
    public static Decision evaluate(MarketRiskEvaluator.Assessment market,
                                    FundamentalVerification fundamentals,
                                    Instant evaluatedAt,
                                    BuyEvidence buy) {
        var horizon = market == null || market.investmentHorizon() == null
                ? "UNKNOWN" : market.investmentHorizon();
        var marketStatus = market == null ? "NOT_EVALUABLE" : market.marketDataStatus();
        var marketHealth = market == null ? "NOT_EVALUABLE" : market.marketHealth();
        var priceRiskState = market == null ? "NOT_EVALUABLE" : market.priceRiskState();
        var policy = buy == null ? POLICY_PROVIDERS : buy.fundamentalSourcePolicy();
        var reasons = new ArrayList<String>();

        if ("UNKNOWN".equals(horizon) || !Set.of("TACTICAL", "STRATEGIC").contains(horizon)) {
            reasons.add("INVESTMENT_HORIZON_UNKNOWN");
            if (Set.of("STALE", "SOURCE_CONFLICT", "DATA_MISSING").contains(marketStatus)) {
                reasons.add("MARKET_DATA_" + safeCode(marketStatus));
                return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                        priceRiskState, fundamentals, evaluatedAt, policy);
            }
            if (!"OK".equals(marketStatus)) reasons.add("MARKET_DATA_" + safeCode(marketStatus));
            return decision("REVIEW_REQUIRED", reasons, horizon, marketStatus, marketHealth,
                    priceRiskState, fundamentals, evaluatedAt, policy);
        }
        if (!"OK".equals(marketStatus)) {
            reasons.add("MARKET_DATA_" + safeCode(marketStatus));
            return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                    priceRiskState, fundamentals, evaluatedAt, policy);
        }
        if ("NOT_EVALUABLE".equals(marketHealth)) {
            reasons.add("MARKET_HEALTH_NOT_EVALUABLE");
            return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                    priceRiskState, fundamentals, evaluatedAt, policy);
        }

        if (buy != null) {
            if (!quoteIsSane(market, evaluatedAt, buy.priceStaleAfter())) reasons.add("MARKET_DATA_UNVERIFIED");
            var hazards = buy.hazards();
            if (hazards == null || !Set.of(HAZARD_CLEAR, HAZARD_BLOCKED).contains(hazards.status())) {
                reasons.add("TOSS_STOCK_HAZARD_UNAVAILABLE");
            } else if (HAZARD_BLOCKED.equals(hazards.status())) {
                reasons.addAll(hazards.reasons());
            }
            if (!reasons.isEmpty()) {
                return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                        priceRiskState, fundamentals, evaluatedAt, policy);
            }
        }

        if ("TACTICAL".equals(horizon)) {
            if ("TRIGGER_BREACHED".equals(priceRiskState)) reasons.add("APPROVED_PRICE_TRIGGER_BREACHED");
            if (!"INTACT".equals(marketHealth)) reasons.add("TACTICAL_MARKET_RISK_" + safeCode(marketHealth));
            return decision(reasons.isEmpty() ? "ELIGIBLE" : "BLOCKED", reasons, horizon, marketStatus,
                    marketHealth, priceRiskState, fundamentals, evaluatedAt, policy);
        }

        if (fundamentals == null || !"AVAILABLE".equals(fundamentals.status())) {
            if (buy != null && POLICY_TOSS_ONLY.equals(policy) && onlyTossUnsupported(fundamentals)) {
                reasons.add("FUNDAMENTALS_EXTERNAL_EVIDENCE_REQUIRED");
                addStrategicMarketReasons(reasons, priceRiskState, marketHealth);
                return decision("REVIEW_REQUIRED", reasons, horizon, marketStatus, marketHealth,
                        priceRiskState, fundamentals, evaluatedAt, EVIDENCE_EXTERNAL_CITED);
            }
            if (fundamentals == null || fundamentals.reasons().isEmpty()) reasons.add("FUNDAMENTAL_VERIFICATION_UNAVAILABLE");
            else reasons.addAll(fundamentals.reasons());
            return decision("BLOCKED", reasons, horizon, marketStatus, marketHealth,
                    priceRiskState, fundamentals, evaluatedAt, policy);
        }

        addStrategicMarketReasons(reasons, priceRiskState, marketHealth);
        return decision(reasons.isEmpty() ? "ELIGIBLE" : "REVIEW_REQUIRED", reasons, horizon, marketStatus,
                marketHealth, priceRiskState, fundamentals, evaluatedAt, policy);
    }

    private static void addStrategicMarketReasons(List<String> reasons, String priceRiskState, String marketHealth) {
        if ("TRIGGER_BREACHED".equals(priceRiskState)) reasons.add("APPROVED_PRICE_TRIGGER_REVIEW_REQUIRED");
        if (Set.of("IMPAIRED", "BREAKDOWN").contains(marketHealth)) {
            reasons.add("STRATEGIC_MARKET_RISK_REVIEW_REQUIRED_" + marketHealth);
        }
    }

    private static boolean quoteIsSane(MarketRiskEvaluator.Assessment market, Instant evaluatedAt,
                                       Duration priceStaleAfter) {
        if (market == null || market.currentPrice() == null || market.currentPrice().signum() <= 0) return false;
        var asOf = market.currentPriceAsOf();
        if (asOf == null || asOf.isAfter(evaluatedAt)) return false;
        return priceStaleAfter == null || !asOf.isBefore(evaluatedAt.minus(priceStaleAfter));
    }

    /** True only when every fundamental gap is the Toss-unsupported marker, so no stored value exists to contradict. */
    private static boolean onlyTossUnsupported(FundamentalVerification fundamentals) {
        return fundamentals != null && !fundamentals.reasons().isEmpty()
                && fundamentals.reasons().stream().allMatch(TOSS_SOURCE_UNSUPPORTED::equals);
    }

    private static Decision decision(String status, List<String> reasons, String horizon, String marketStatus,
                                     String marketHealth, String priceRiskState,
                                     FundamentalVerification fundamentals, Instant evaluatedAt,
                                     String evidenceMode) {
        return new Decision(status, List.copyOf(reasons), horizon, marketStatus, marketHealth,
                priceRiskState, fundamentals, evaluatedAt, evidenceMode);
    }

    private static String safeCode(String value) {
        return value == null || !value.matches("[A-Z0-9_]+") ? "UNKNOWN" : value;
    }

    /** Buy-path inputs. The policy is already parsed; hazards are null when they were not evaluated live. */
    public record BuyEvidence(String fundamentalSourcePolicy, Duration priceStaleAfter, TossStockHazards hazards) {
        public BuyEvidence {
            fundamentalSourcePolicy = fundamentalSourcePolicy == null ? POLICY_PROVIDERS : fundamentalSourcePolicy;
        }
    }

    /** Live Toss stock hazards for one symbol. UNAVAILABLE covers fetch failure, timeout and unparsable data. */
    public record TossStockHazards(String status, List<String> reasons, BigDecimal sharesOutstanding) {
        public TossStockHazards {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public static TossStockHazards unavailable() {
            return new TossStockHazards(HAZARD_UNAVAILABLE, List.of("TOSS_STOCK_HAZARD_UNAVAILABLE"), null);
        }
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
                           FundamentalVerification fundamentalVerification, Instant evaluatedAt,
                           String evidenceMode) {
        public Decision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            evidenceMode = evidenceMode == null ? POLICY_PROVIDERS : evidenceMode;
        }

        public Decision(String status, List<String> reasons, String investmentHorizon,
                        String marketDataStatus, String marketHealth, String priceRiskState,
                        FundamentalVerification fundamentalVerification, Instant evaluatedAt) {
            this(status, reasons, investmentHorizon, marketDataStatus, marketHealth, priceRiskState,
                    fundamentalVerification, evaluatedAt, POLICY_PROVIDERS);
        }

        public Decision withStatusAndReasons(String nextStatus, List<String> nextReasons) {
            return new Decision(nextStatus, nextReasons, investmentHorizon, marketDataStatus, marketHealth,
                    priceRiskState, fundamentalVerification, evaluatedAt, evidenceMode);
        }
    }
}
