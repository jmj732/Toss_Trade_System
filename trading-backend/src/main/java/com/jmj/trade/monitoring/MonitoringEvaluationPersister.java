package com.jmj.trade.monitoring;

import com.jmj.trade.notification.NotificationEventType;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
class MonitoringEvaluationPersister {

    private static final Map<String, Integer> MARKET_RANK = Map.of(
            "UNKNOWN", 0, "NORMAL", 0, "EARLY_WARNING", 1, "RISK_TRANSITION", 2,
            "STRONG_RISK_OFF", 3, "P0_SYSTEMIC", 4);
    private static final Map<String, Integer> PORTFOLIO_RANK = Map.of(
            "NORMAL", 0, "ATTENTION", 1, "REDUCE_CANDIDATE", 2,
            "THESIS_INVALIDATED", 3, "P0", 4);
    private static final Set<String> OFFICIAL_INCIDENT_SOURCES = Set.of("SEC", "COMPANY_IR", "EXCHANGE", "GOVERNMENT");

    private final MonitoringStateStore states;
    private final NotificationOutboxWriter notifications;
    private final JdbcTemplate jdbc;

    MonitoringEvaluationPersister(
            MonitoringStateStore states,
            NotificationOutboxWriter notifications,
            JdbcTemplate jdbc
    ) {
        this.states = Objects.requireNonNull(states, "states");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    void persist(UUID userId, MonitoringEvaluationContract.Request request, JsonNode result) {
        requireResponse(request, result);
        persistMarket(userId, request, result.path("market"));
        persistPortfolio(userId, request, result.path("portfolio"));
        persistMaterialEvents(userId, request, result.path("events"));
        persistWatchlist(userId, request, result.path("watchlist"));
    }

    private void persistMarket(UUID userId, MonitoringEvaluationContract.Request request, JsonNode market) {
        var state = text(market, "state");
        var quality = text(market, "dataQuality");
        if (state == null || !MARKET_RANK.containsKey(state)) return;
        var contagion = market.path("contagion");
        var previous = currentState(userId, "MARKET", "US_MARKET");
        var holdStage = rank(state, MARKET_RANK) < rank(previous, MARKET_RANK)
                && hasAdverseAxisNowUnknown(userId, contagion);
        persistAxes(userId, request, contagion);
        var evidenceNode = market.path("stateEvidence");
        var supported = evidenceNode.isArray() && !evidenceNode.isEmpty();
        if ("NORMAL".equals(state) && "INSUFFICIENT_DATA".equals(quality)) return;
        if (!"NORMAL".equals(state) && !supported && !verifiedIncident(request, market)) return;
        if (holdStage) return;
        var observedAt = request.asOf();
        var evidence = objectEvidence(evidenceNode, marketSummary(market));
        var transition = states.observe(userId, "MARKET", "US_MARKET", state, evidence,
                "EVALUATION", request.requestId() + ":MARKET", observedAt);
        if (transition != null && rank(state, MARKET_RANK) > rank(previous, MARKET_RANK)) {
            alert(transition, "MARKET", evidence);
        }
    }

    private void persistPortfolio(UUID userId, MonitoringEvaluationContract.Request request, JsonNode portfolio) {
        var quality = text(portfolio, "dataQuality");
        var breaches = portfolio.path("policyBreaches");
        if (breaches.isArray()) {
            var activePolicyKeys = new java.util.HashSet<String>();
            for (var breach : breaches) {
                var policy = text(breach, "policy");
                var targetType = text(breach, "targetType");
                var target = text(breach, "target");
                if (policy == null || targetType == null || target == null) continue;
                var subject = policyKey(policy, targetType, target);
                activePolicyKeys.add(subject);
                var evidence = objectEvidence(breach, policySummary(breach));
                var transition = states.observe(userId, "EVENT", subject, "BREACH", evidence,
                        "RISK_POLICY", request.requestId() + ":" + subject, timestamp(breach.path("asOf"), request.asOf()));
                if (transition != null) alert(transition, "RISK POLICY", evidence);
            }
            if (concentrationSnapshotClearable(request)) {
                clearResolvedPolicyBreaches(userId, request, activePolicyKeys, "POLICY:%:SYMBOL:%");
            }
            if (policySnapshotComplete(request, quality)) {
                clearResolvedPolicyBreaches(userId, request, activePolicyKeys, "POLICY:%:SECTOR:%");
                clearResolvedPolicyBreaches(userId, request, activePolicyKeys, "POLICY:%:FACTOR:%");
            }
        }

        var positions = portfolio.path("positions");
        if (!positions.isArray()) return;
        var activeSymbols = new java.util.HashSet<String>();
        for (var position : positions) {
            var symbol = text(position, "symbol");
            var state = text(position, "state");
            var dataQuality = text(position, "dataQuality");
            if (symbol == null || state == null || !PORTFOLIO_RANK.containsKey(state)) continue;
            activeSymbols.add(symbol);
            if ("INSUFFICIENT_DATA".equals(dataQuality) && !hasMaterialPortfolioEvidence(position, state)) continue;
            if ("ATTENTION".equals(state) && !hasSupportedEvidence(position.path("evidence"))) continue;
            if ("THESIS_INVALIDATED".equals(state) && !hasThesisInvalidation(position.path("evidence"))) continue;
            var evidence = objectEvidence(position.path("evidence"), portfolioSummary(symbol, state, position.path("evidence")));
            var previous = currentState(userId, "PORTFOLIO", symbol);
            var observedAt = request.portfolio().asOf() == null ? request.asOf() : request.portfolio().asOf();
            var transition = states.observe(userId, "PORTFOLIO", symbol, state, evidence,
                    "EVALUATION", request.requestId() + ":PORTFOLIO:" + symbol, observedAt);
            if (transition != null && rank(state, PORTFOLIO_RANK) > rank(previous, PORTFOLIO_RANK)) {
                alert(transition, "PORTFOLIO", evidence);
            }
        }
        if (policySnapshotComplete(request, quality)) {
            closeMissingPositions(userId, request, activeSymbols);
        }
    }

    private void persistMaterialEvents(UUID userId, MonitoringEvaluationContract.Request request, JsonNode events) {
        if (!events.isArray()) return;
        var inputs = request.events().stream().collect(java.util.stream.Collectors.toMap(
                MonitoringEvaluationContract.EventInput::sourceEventId, value -> value, (left, right) -> left));
        for (var event : events) {
            if (!event.path("material").asBoolean(false)
                    || !event.path("thesisRecheckRequired").asBoolean(false)) continue;
            var sourceEventId = text(event, "sourceEventId");
            var symbol = text(event, "symbol");
            if (sourceEventId == null || symbol == null) continue;
            var input = inputs.get(sourceEventId);
            if (input == null || !input.official()) continue;
            var observedAt = input.collectedAt() == null ? request.asOf() : input.collectedAt();
            var evidence = objectEvidence(event, eventSummary(event));
            var transition = states.observe(userId, "EVENT", "MATERIAL:" + sourceEventId,
                    "THESIS_RECHECK_REQUIRED", evidence, input.sourceType(), sourceEventId, observedAt);
            if (transition != null) alert(transition, "EVENT", evidence);
        }
    }

    private void persistWatchlist(UUID userId, MonitoringEvaluationContract.Request request, JsonNode watchlist) {
        if (!watchlist.isArray()) return;
        for (var item : watchlist) {
            if (!item.path("changed").asBoolean(false)) continue;
            var symbol = text(item, "symbol");
            var state = text(item, "state");
            if (symbol == null || state == null) continue;
            var evidence = objectEvidence(item.path("evidence"), watchlistSummary(item));
            var observedAt = timestamp(item.path("asOf"), request.asOf());
            var transition = states.observe(userId, "WATCHLIST", symbol, state, evidence,
                    "QUOTE", request.requestId() + ":WATCHLIST:" + symbol, observedAt);
            if (transition != null) alert(transition, "WATCHLIST", evidence);
        }
    }

    private void clearResolvedPolicyBreaches(UUID userId, MonitoringEvaluationContract.Request request,
                                             Set<String> active, String subjectPattern) {
        var current = jdbc.query("""
                SELECT subject_key FROM monitoring_current_states
                 WHERE user_id = ? AND scope = 'EVENT' AND subject_key LIKE ? AND state = 'BREACH'
                """, (resultSet, rowNum) -> resultSet.getString(1), userId, subjectPattern);
        for (var subject : current) {
            if (active.contains(subject)) continue;
            states.observe(userId, "EVENT", subject, "CLEARED",
                    Map.of("action", "RISK_POLICY_RESOLVED", "summary", subject + " resolved"),
                    "RISK_POLICY", request.requestId() + ":resolved:" + subject, request.asOf());
        }
    }

    private void closeMissingPositions(UUID userId, MonitoringEvaluationContract.Request request, Set<String> active) {
        var current = jdbc.query("""
                SELECT subject_key FROM monitoring_current_states
                 WHERE user_id = ? AND scope = 'PORTFOLIO' AND state <> 'CLOSED'
                """, (resultSet, rowNum) -> resultSet.getString(1), userId);
        for (var symbol : current) {
            if (active.contains(symbol)) continue;
            states.observe(userId, "PORTFOLIO", symbol, "CLOSED",
                    Map.of("action", "POSITION_CLOSED", "symbol", symbol,
                            "summary", symbol + " no longer appears in the complete holdings snapshot"),
                    "PORTFOLIO_SNAPSHOT", request.requestId() + ":closed:" + symbol, request.asOf());
        }
    }

    private void persistAxes(UUID userId, MonitoringEvaluationContract.Request request, JsonNode contagion) {
        for (var axis : List.of("EQUITY", "CREDIT", "FUNDING", "FX")) {
            var result = contagion.path(axis);
            var state = text(result, "status");
            if (state == null || !Set.of("NORMAL", "WARN", "STRESS", "UNKNOWN").contains(state)) continue;
            var previous = currentState(userId, "MARKET", "AXIS:" + axis);
            var evidence = new LinkedHashMap<>(objectEvidence(result, axisSummary(axis, result)));
            evidence.put("previousAdverse", "UNKNOWN".equals(state)
                    && (("WARN".equals(previous) || "STRESS".equals(previous))
                    || previousAdverseEvidence(userId, axis)));
            var observedAt = latestAxisTimestamp(result, request.asOf());
            states.observe(userId, "MARKET", "AXIS:" + axis, state, evidence,
                    "EVALUATION", request.requestId() + ":AXIS:" + axis, observedAt);
        }
    }

    private boolean hasAdverseAxisNowUnknown(UUID userId, JsonNode contagion) {
        for (var axis : List.of("EQUITY", "CREDIT", "FUNDING", "FX")) {
            var previous = currentState(userId, "MARKET", "AXIS:" + axis);
            var current = text(contagion.path(axis), "status");
            if ("UNKNOWN".equals(current) && (("WARN".equals(previous) || "STRESS".equals(previous))
                    || previousAdverseEvidence(userId, axis))) return true;
        }
        return false;
    }

    private boolean previousAdverseEvidence(UUID userId, String axis) {
        return jdbc.query("""
                SELECT evidence->>'previousAdverse' FROM monitoring_current_states
                 WHERE user_id = ? AND scope = 'MARKET' AND subject_key = ?
                """, (resultSet, rowNum) -> Boolean.parseBoolean(resultSet.getString(1)),
                userId, "AXIS:" + axis).stream().findFirst().orElse(false);
    }

    private static Instant latestAxisTimestamp(JsonNode axis, Instant fallback) {
        var latest = (Instant) null;
        var signals = axis.path("signals");
        if (!signals.isArray()) return latest;
        for (var signal : signals) {
            var evidence = signal.path("evidence");
            if (!evidence.isArray()) continue;
            for (var item : evidence) {
                var timestamp = timestamp(item.path("asOf"), fallback);
                if (latest == null || timestamp.isAfter(latest)) latest = timestamp;
            }
        }
        return latest == null ? fallback : latest;
    }

    private static String axisSummary(String axis, JsonNode result) {
        var signals = result.path("signals");
        var descriptions = new ArrayList<String>();
        if (signals.isArray()) {
            for (var signal : signals) {
                var metric = text(signal, "metric");
                if (metric != null) {
                    var velocity = signal.path("velocity");
                    var change = velocity.path("10D");
                    if (change.isMissingNode() || change.isNull()) change = velocity.path("5D");
                    descriptions.add(metric + (numericText(change) == null ? "" : " " + numericText(change))
                            + " " + text(signal, "severity"));
                }
            }
        }
        var status = text(result, "status");
        var missing = result.path("unknownFields");
        return axis + "=" + status + (descriptions.isEmpty() ? "" : "; " + String.join(", ", descriptions))
                + (missing.isArray() && !missing.isEmpty() ? "; unknown fields=" + missing.size() : "");
    }

    private void alert(MonitoringStateStore.StateTransition transition, String scope, Map<String, Object> evidence) {
        notifications.emit(transition.userId(), NotificationEventType.MONITORING_ALERT, transition.id(), Map.of(
                "scope", scope,
                "subjectKey", transition.subjectKey(),
                "previousState", transition.previousState(),
                "newState", transition.newState(),
                "evidence", evidence,
                "observedAt", transition.observedAt()), transition.observedAt());
    }

    private static boolean verifiedIncident(MonitoringEvaluationContract.Request request, JsonNode market) {
        return "P0_SYSTEMIC".equals(text(market, "state")) && request.market().incidents() != null
                && request.market().incidents().stream().anyMatch(incident -> incident.official()
                && incident.forcedDeleveraging() && OFFICIAL_INCIDENT_SOURCES.contains(incident.sourceType())
                && !incident.asOf().isAfter(request.asOf())
                && !incident.asOf().isBefore(request.asOf().minusSeconds(86_400)));
    }

    private static boolean hasMaterialPortfolioEvidence(JsonNode position, String state) {
        if ("P0".equals(state)) return hasEvidenceType(position.path("evidence"), "SYSTEMIC_INCIDENT");
        if ("REDUCE_CANDIDATE".equals(state)) return hasEvidenceType(position.path("evidence"), "POLICY_BREACH");
        if ("THESIS_INVALIDATED".equals(state)) return hasThesisInvalidation(position.path("evidence"));
        return false;
    }

    private static boolean hasSupportedEvidence(JsonNode evidence) {
        return evidence.isArray() && !evidence.isEmpty();
    }

    private static boolean hasThesisInvalidation(JsonNode evidence) {
        if (!evidence.isArray()) return false;
        for (var item : evidence) {
            if ("THESIS_INVALIDATION".equals(text(item, "type"))
                    && !"PRICE".equals(text(item, "kind")) && text(item, "source") != null) return true;
        }
        return false;
    }

    private static boolean hasEvidenceType(JsonNode evidence, String type) {
        if (!evidence.isArray()) return false;
        for (var item : evidence) if (type.equals(text(item, "type"))) return true;
        return false;
    }

    private String currentState(UUID userId, String scope, String subject) {
        return jdbc.query("""
                SELECT state FROM monitoring_current_states
                 WHERE user_id = ? AND scope = ? AND subject_key = ?
                """, (resultSet, rowNum) -> resultSet.getString(1), userId, scope, subject)
                .stream().findFirst().orElse("UNKNOWN");
    }

    private static int rank(String state, Map<String, Integer> ranks) {
        return ranks.getOrDefault(state, 0);
    }

    private static String policyKey(String policy, String targetType, String target) {
        try {
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(target.getBytes(StandardCharsets.UTF_8)));
            return "POLICY:" + policy + ":" + targetType + ":" + digest;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<String, Object> objectEvidence(JsonNode node, String summary) {
        var result = new LinkedHashMap<String, Object>();
        result.put("summary", summary);
        if (node != null && !node.isMissingNode() && !node.isNull()) result.put("details", node);
        return result;
    }

    private static String marketSummary(JsonNode market) {
        var parts = new ArrayList<String>();
        var axes = market.path("contagion");
        for (var axis : List.of("EQUITY", "CREDIT", "FUNDING", "FX")) {
            var status = text(axes.path(axis), "status");
            parts.add(axis + "=" + (status == null ? "UNKNOWN" : status));
        }
        var signals = new ArrayList<String>();
        for (var axis : List.of("EQUITY", "CREDIT", "FUNDING", "FX")) {
            var items = axes.path(axis).path("signals");
            if (!items.isArray()) continue;
            for (var item : items) {
                var metric = text(item, "metric");
                if (metric == null) continue;
                var velocity = item.path("velocity");
                var change = velocity.path("10D");
                if (change.isMissingNode() || change.isNull()) change = velocity.path("5D");
                signals.add(metric + (numericText(change) == null ? "" : " " + numericText(change))
                        + " " + text(item, "severity"));
            }
        }
        var yield = market.path("yieldDecomposition");
        if (yield.path("observationZone").asBoolean(false)) parts.add("10Y in observation band");
        var signalText = signals.isEmpty() ? "no supported adverse velocity" : String.join(", ", signals.stream().limit(3).toList());
        return "Contagion " + String.join(" ", parts) + "; " + signalText;
    }

    private static String portfolioSummary(String symbol, String state, JsonNode evidence) {
        var first = evidence.isArray() && !evidence.isEmpty() ? evidence.get(0) : null;
        var type = first == null ? null : text(first, "type");
        return symbol + " " + state + (type == null ? "" : "; evidence=" + type);
    }

    private static String policySummary(JsonNode breach) {
        return "Risk Policy breach: " + text(breach, "policy") + " " + text(breach, "target")
                + " value=" + text(breach, "value") + " limit=" + text(breach, "limit");
    }

    private static String eventSummary(JsonNode event) {
        return text(event, "symbol") + " " + text(event, "kind") + ": " + text(event, "evidence");
    }

    private static String watchlistSummary(JsonNode item) {
        var evidence = item.path("evidence");
        var condition = evidence.isArray() && !evidence.isEmpty() ? text(evidence.get(0), "condition") : null;
        return text(item, "symbol") + " " + text(item, "previousState") + " → " + text(item, "state")
                + (condition == null ? "" : "; " + condition);
    }

    private static Instant timestamp(JsonNode node, Instant fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        try {
            return Instant.parse(node.asText());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private static String numericText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        var text = value.asText();
        try {
            new java.math.BigDecimal(text);
            return text;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean policySnapshotComplete(
            MonitoringEvaluationContract.Request request,
            String quality
    ) {
        var asOf = request.portfolio().asOf();
        if (!"COMPLETE".equals(quality) || asOf == null || asOf.isAfter(request.asOf())
                || asOf.isBefore(request.asOf().minus(Duration.ofMinutes(30)))) return false;
        return request.portfolio().positions().stream().allMatch(position -> position.weight() != null);
    }

    /**
     * A symbol-concentration (weight-only) breach may be cleared from a fresh holdings snapshot whose weights are
     * all known, independent of the response's overall data quality label: concentration depends only on weight and
     * the configured limit, both of which are present here. Sector/factor breaches still require a COMPLETE snapshot
     * because their inputs (sector/factor mapping) can be missing while the holdings themselves are complete.
     */
    private static boolean concentrationSnapshotClearable(MonitoringEvaluationContract.Request request) {
        var asOf = request.portfolio().asOf();
        if (asOf == null || asOf.isAfter(request.asOf())
                || asOf.isBefore(request.asOf().minus(Duration.ofMinutes(30)))) return false;
        if (request.portfolio().riskPolicy() == null
                || request.portfolio().riskPolicy().maxConcentration() == null) return false;
        return request.portfolio().positions().stream().allMatch(position -> position.weight() != null);
    }

    private static void requireResponse(MonitoringEvaluationContract.Request request, JsonNode result) {
        if (request == null || result == null || result.isNull()
                || !request.requestId().toString().equals(text(result, "requestId"))
                || !"1".equals(text(result, "schemaVersion"))
                || !result.path("market").isObject()
                || text(result.path("market"), "state") == null
                || text(result.path("market"), "dataQuality") == null
                || !result.path("market").path("contagion").isObject()
                || !result.path("portfolio").isObject()
                || text(result.path("portfolio"), "dataQuality") == null
                || !result.path("portfolio").path("positions").isArray()
                || !result.path("portfolio").path("policyBreaches").isArray()
                || !result.path("events").isArray()
                || !result.path("watchlist").isArray()
                || !responseCoversRequestPositions(request, result)) {
            throw new IllegalArgumentException("monitoring evaluation response contract mismatch");
        }
    }

    /**
     * FastAPI echoes exactly one response position per input position. A response that drops a requested symbol is a
     * contract violation, and must be rejected before persisting anything: otherwise a complete-snapshot path could
     * wrongly close a live holding that the evaluator merely failed to return.
     */
    private static boolean responseCoversRequestPositions(
            MonitoringEvaluationContract.Request request, JsonNode result) {
        var responseSymbols = new java.util.HashSet<String>();
        for (var position : result.path("portfolio").path("positions")) {
            var symbol = text(position, "symbol");
            if (symbol != null) responseSymbols.add(symbol);
        }
        for (var input : request.portfolio().positions()) {
            if (input.symbol() != null && !responseSymbols.contains(input.symbol())) return false;
        }
        return true;
    }
}
