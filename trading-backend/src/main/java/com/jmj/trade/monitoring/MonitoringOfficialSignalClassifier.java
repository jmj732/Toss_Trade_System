package com.jmj.trade.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative keyword adapter: only explicit official event language becomes a shock or incident. */
final class MonitoringOfficialSignalClassifier {

    private static final Set<String> OFFICIAL = Set.of("SEC", "IR", "FED", "FRED", "BLS", "BEA");

    private MonitoringOfficialSignalClassifier() {
    }

    static Signals classify(String source, String sourceEventId, String type, String summary, Instant asOf) {
        if (source == null || !OFFICIAL.contains(source.toUpperCase(Locale.ROOT)) || sourceEventId == null
                || summary == null || summary.isBlank() || asOf == null) {
            return Signals.empty();
        }
        var normalizedSource = source.toUpperCase(Locale.ROOT);
        var sourceType = switch (normalizedSource) {
            case "SEC" -> "SEC";
            case "IR" -> "COMPANY_IR";
            default -> "GOVERNMENT";
        };
        var evidence = summary.trim();
        var text = ((type == null ? "" : type) + " " + evidence).toLowerCase(Locale.ROOT);
        var shock = shockCategory(text);
        var shocks = new ArrayList<MonitoringEvaluationContract.ShockInput>();
        if (shock != null) {
            shocks.add(new MonitoringEvaluationContract.ShockInput(shock, severity(text), evidence,
                    normalizedSource + ":" + sourceEventId, asOf));
        }
        var incident = incidentKind(text);
        var incidents = incident == null ? List.<MonitoringEvaluationContract.IncidentInput>of()
                : List.of(new MonitoringEvaluationContract.IncidentInput(
                incident, true, evidence, normalizedSource + ":" + sourceEventId,
                sourceType, true, asOf));
        return new Signals(List.copyOf(shocks), incidents);
    }

    private static String shockCategory(String text) {
        var explicitSignal = containsAny(text, "shock", "surprise", "spike", "surge", "crisis", "failure",
                "disruption", "disorderly", "unexpectedly");
        if (!explicitSignal) return null;
        if (containsAny(text, "inflation", "cpi", "pce price")) return "INFLATION";
        if (containsAny(text, "oil supply", "crude oil", "brent", "wti")) return "OIL";
        if (containsAny(text, "recession", "gdp contraction", "economic contraction")) return "GROWTH";
        if (containsAny(text, "treasury auction", "term premium", "debt ceiling", "fiscal shutdown")) {
            return "TREASURY_FISCAL";
        }
        if (containsAny(text, "ai revenue", "hyperscaler capex", "artificial intelligence demand")) {
            return "AI_MEGA_CAP";
        }
        if (containsAny(text, "usd/jpy", "usdjpy", "yen carry", "dollar index")) return "DXY_USDJPY";
        if (containsAny(text, "war", "sanction", "tariff", "geopolitical")) return "GEOPOLITICAL";
        if (containsAny(text, "bank", "fund", "clearing", "exchange", "market infrastructure")) {
            return "INSTITUTION";
        }
        return null;
    }

    private static String severity(String text) {
        return containsAny(text, "failure", "disorderly", "crisis", "severe", "forced", "default", "surge")
                ? "STRESS" : "WARN";
    }

    private static String incidentKind(String text) {
        if (containsAny(text, "risk of forced", "possible forced", "potential forced", "may face forced",
                "might face forced", "could face forced", "no forced", "not forced")) return null;
        if (!containsAny(text, "forced deleveraging", "forced liquidation", "forced sale",
                "forced unwind", "margin call triggered liquidation")) return null;
        if (containsAny(text, "market infrastructure", "clearinghouse", "clearing house", "exchange outage",
                "settlement system")) return "MARKET_INFRASTRUCTURE";
        if (containsAny(text, "fund", "hedge fund", "private credit", "money market fund")) return "FUND";
        if (containsAny(text, "bank", "broker", "dealer", "financial institution")) return "INSTITUTION";
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        for (var needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    record Signals(
            List<MonitoringEvaluationContract.ShockInput> shocks,
            List<MonitoringEvaluationContract.IncidentInput> incidents
    ) {
        static Signals empty() {
            return new Signals(List.of(), List.of());
        }
    }
}
