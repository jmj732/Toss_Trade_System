package com.jmj.trade.investment;

import java.util.Locale;
import java.util.Set;

/** Maps persisted position horizons to review signals; it never creates trade actions or orders. */
public final class PositionMandatePolicy {

    private static final Set<String> CONFIRMED_BREACH_STATES = Set.of("INTRADAY_BREACH", "PRICE_RISK_BREACH");
    private static final Set<String> NO_BREACH_STATES = Set.of("CLEAR", "NEAR", "RECOVERED");

    private PositionMandatePolicy() {
    }

    public static String riskPriceRole(String investmentHorizon) {
        return switch (normalize(investmentHorizon)) {
            case "TACTICAL" -> "EXIT_EVALUATION";
            case "STRATEGIC" -> "REVIEW_TRIGGER";
            default -> throw new IllegalArgumentException("Unsupported investment horizon");
        };
    }

    public static Response resolve(
            String investmentHorizon,
            String priceRiskState,
            String thesisHealth,
            boolean hardRisk
    ) {
        if (hardRisk) return Response.URGENT_REVIEW;
        if ("INVALIDATED".equals(normalize(thesisHealth))) return Response.URGENT_REVIEW;

        var state = normalize(priceRiskState);
        if (NO_BREACH_STATES.contains(state)) return Response.NO_PRICE_ACTION;
        if (!CONFIRMED_BREACH_STATES.contains(state)) return Response.MANDATE_REVIEW;

        return switch (normalize(investmentHorizon)) {
            case "TACTICAL" -> Response.EXIT_EVALUATION;
            case "STRATEGIC" -> switch (normalize(thesisHealth)) {
                case "IMPAIRED", "INVALIDATED" -> Response.EXIT_EVALUATION;
                default -> Response.THESIS_REVALIDATION;
            };
            default -> Response.MANDATE_REVIEW;
        };
    }

    private static String normalize(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    public enum Response {
        NO_PRICE_ACTION,
        EXIT_EVALUATION,
        THESIS_REVALIDATION,
        URGENT_REVIEW,
        MANDATE_REVIEW
    }
}
