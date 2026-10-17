package com.jmj.trade.monitoring;

import com.jmj.trade.notification.NotificationEventType;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Persists thesis-price state transitions and reads their user-scoped append-only history. */
@Service
public class InvestmentRiskEventService {

    public static final String SCOPE = "THESIS_REVIEW";
    private static final String SOURCE = "SPRING_POSTGRESQL";
    private static final String SOURCE_TYPE = "CONFIRMED_THESIS_PRICE_TRIGGER";
    private static final int MAX_LIMIT = 100;
    private static final int MAX_RELATED_DECISIONS = 5;
    private static final int MAX_DECISION_TEXT_LENGTH = 500;
    private static final List<String> REQUIRED_EVENT_FIELDS = List.of(
            "sourceAsOf", "priceSource", "priceSession", "priceBasis", "observedPrice",
            "triggerPrice", "riskMarkStatus", "thesisRevisionId", "invalidationStatus", "reasonCode");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MonitoringStateStore stateStore;
    private final NotificationOutboxWriter notifications;
    private final TransactionTemplate transaction;

    public InvestmentRiskEventService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            MonitoringStateStore stateStore,
            NotificationOutboxWriter notifications
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    /** The event and Telegram outbox row commit atomically; Telegram delivery itself remains retryable. */
    public MonitoringStateStore.StateTransition recordObservation(
            UUID userId, String ticker, String desiredState, Map<String, Object> observation, Instant observedAt
    ) {
        return transaction.execute(status -> {
            var transition = stateStore.observeThesisRisk(userId, ticker, desiredState, observation, observedAt);
            if (transition != null) {
                notifications.emit(userId, NotificationEventType.MONITORING_ALERT, transition.id(),
                        telegramPayload(transition.evidence()), transition.observedAt());
            }
            return transition;
        });
    }

    public RiskEventsResult read(UUID userId, RiskEventsQuery query) {
        if (userId == null) throw new IllegalArgumentException("userId is required");
        var normalized = normalize(query);
        var cursor = normalized.cursor() == null ? null : decodeCursor(normalized.cursor());
        var rows = jdbc.query(eventSql(normalized, cursor), eventRowMapper(), eventParameters(userId, normalized, cursor));
        var hasMore = rows.size() > normalized.limit();
        var page = hasMore ? List.copyOf(rows.subList(0, normalized.limit())) : List.copyOf(rows);
        var nextCursor = hasMore && !page.isEmpty() ? encodeCursor(page.getLast()) : null;
        var unresolvedRows = normalized.includeUnresolved()
                ? readUnresolvedRows(userId, normalized.ticker(), normalized.since(), normalized.limit())
                : List.<UnresolvedRow>of();
        var eventIds = new LinkedHashSet<UUID>();
        page.forEach(row -> eventIds.add(row.id()));
        unresolvedRows.forEach(row -> eventIds.add(row.event().id()));
        var decisionLinks = readDecisionLinks(userId, eventIds);
        var events = page.stream().map(row -> eventView(row, decisionLinks)).toList();
        var latestByTicker = new LinkedHashMap<String, Map<String, Object>>();
        for (var event : events) {
            latestByTicker.putIfAbsent((String) event.get("ticker"), event);
        }
        var unresolved = unresolvedRows.stream().map(row -> {
            var event = eventView(row.event(), decisionLinks);
            var enriched = new LinkedHashMap<>(event);
            enriched.put("currentState", row.currentState());
            enriched.put("latestObservation", parse(row.currentEvidence()).get("latestObservation"));
            return Collections.unmodifiableMap(enriched);
        }).toList();
        var missingFields = new LinkedHashSet<String>();
        for (var event : events) {
            @SuppressWarnings("unchecked")
            var eventMissing = (List<String>) event.get("missingFields");
            missingFields.addAll(eventMissing);
        }
        for (var event : unresolved) {
            @SuppressWarnings("unchecked")
            var eventMissing = (List<String>) event.get("missingFields");
            missingFields.addAll(eventMissing);
        }
        return new RiskEventsResult(events, latestByTicker, unresolved, nextCursor, hasMore,
                Instant.now(), missingFields.isEmpty() ? "OK" : "PARTIAL", SOURCE,
                List.copyOf(missingFields));
    }

    private Map<String, Object> eventView(EventRow row, Map<UUID, List<Map<String, Object>>> decisionLinks) {
        var evidence = parse(row.evidence());
        var event = new LinkedHashMap<String, Object>();
        evidence.properties().forEach(field -> event.put(field.getKey(), nativeValue(field.getValue())));
        event.put("eventId", row.id().toString());
        event.put("ticker", row.ticker());
        event.put("previousState", row.previousState());
        event.put("currentState", row.currentState());
        event.put("observedAt", row.observedAt().toString());
        event.put("persistedAt", row.persistedAt().toString());
        event.put("dedupKey", row.dedupKey());
        addDecisionLinks(decisionLinks.getOrDefault(row.id(), List.of()), event);
        event.put("missingFields", missingFields(event));
        return Collections.unmodifiableMap(event);
    }

    private List<UnresolvedRow> readUnresolvedRows(UUID userId, String ticker, Instant since, int limit) {
        var parameters = new ArrayList<Object>();
        parameters.add(userId);
        if (ticker != null) parameters.add(ticker);
        if (since != null) parameters.add(offset(since));
        parameters.add(limit);
        var sql = """
                SELECT h.id, h.subject_key, h.previous_state, h.new_state, h.source_event_id,
                       h.evidence::text AS evidence, h.observed_at, h.created_at,
                       current.state AS current_state, current.evidence::text AS current_evidence
                  FROM monitoring_current_states current
                  JOIN monitoring_state_history h
                    ON h.id = (current.evidence->>'eventId')::uuid AND h.user_id = current.user_id
                 WHERE current.user_id = ? AND current.scope = 'THESIS_REVIEW'
                   AND current.state IN ('NEAR', 'INTRADAY_BREACH', 'PRICE_RISK_BREACH')
                """ + (ticker == null ? "" : " AND current.subject_key = ?")
                + (since == null ? "" : " AND h.observed_at >= ?") + """
                 ORDER BY current.updated_at DESC, h.id DESC
                 LIMIT ?
                """;
        var rows = jdbc.query(sql, (rs, rowNum) -> new UnresolvedRow(
                new EventRow(rs.getObject("id", UUID.class), rs.getString("subject_key"),
                        rs.getString("previous_state"), rs.getString("new_state"),
                        rs.getString("source_event_id"), rs.getString("evidence"),
                        rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                rs.getString("current_state"), rs.getString("current_evidence")), parameters.toArray());
        return List.copyOf(rows);
    }

    private Map<UUID, List<Map<String, Object>>> readDecisionLinks(UUID userId, LinkedHashSet<UUID> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        var placeholders = String.join(",", Collections.nCopies(eventIds.size(), "?"));
        var parameters = new ArrayList<Object>();
        parameters.add(userId);
        parameters.addAll(eventIds);
        parameters.add(MAX_RELATED_DECISIONS + 1);
        var rows = jdbc.query("""
                WITH ranked_links AS (
                    SELECT links.event_id, d.decision_id, d.as_of, d.asset, d.action, d.price_session, d.horizon,
                           d.alpha_thesis, d.invalidation, d.next_review_trigger, d.confidence,
                           links.thesis_revision_id, links.linked_at,
                           row_number() OVER (PARTITION BY links.event_id
                               ORDER BY links.linked_at DESC, d.decision_id DESC) AS link_rank
                      FROM investment_risk_event_decision_links links
                      JOIN investment_decision_ledger d
                        ON d.decision_id = links.decision_id AND d.user_id = links.user_id
                     WHERE links.user_id = ? AND links.event_id IN (%s)
                )
                SELECT event_id, decision_id, as_of, asset, action, price_session, horizon,
                       alpha_thesis, invalidation, next_review_trigger, confidence,
                       thesis_revision_id, linked_at
                  FROM ranked_links
                 WHERE link_rank <= ?
                 ORDER BY event_id, link_rank
                """.formatted(placeholders), (rs, rowNum) -> {
            var decision = new LinkedHashMap<String, Object>();
            decision.put("decisionId", rs.getObject("decision_id", UUID.class).toString());
            decision.put("decisionAsOf", rs.getObject("as_of", OffsetDateTime.class).toInstant().toString());
            decision.put("ticker", rs.getString("asset"));
            decision.put("action", rs.getString("action"));
            decision.put("priceSession", rs.getString("price_session"));
            decision.put("horizon", rs.getString("horizon"));
            putBoundedText(decision, "rationale", rs.getString("alpha_thesis"));
            putBoundedText(decision, "invalidation", rs.getString("invalidation"));
            putBoundedText(decision, "nextReviewTrigger", rs.getString("next_review_trigger"));
            decision.put("confidence", rs.getBigDecimal("confidence"));
            var revisionId = rs.getObject("thesis_revision_id", UUID.class);
            decision.put("thesisRevisionId", revisionId == null ? null : revisionId.toString());
            decision.put("linkedAt", rs.getObject("linked_at", OffsetDateTime.class).toInstant().toString());
            return new DecisionLinkRow(rs.getObject("event_id", UUID.class), Collections.unmodifiableMap(decision));
        }, parameters.toArray());
        var grouped = new LinkedHashMap<UUID, List<Map<String, Object>>>();
        for (var row : rows) grouped.computeIfAbsent(row.eventId(), ignored -> new ArrayList<>()).add(row.decision());
        grouped.replaceAll((ignored, decisions) -> List.copyOf(decisions));
        return Collections.unmodifiableMap(grouped);
    }

    private void addDecisionLinks(List<Map<String, Object>> linkedDecisions, Map<String, Object> event) {
        var truncated = linkedDecisions.size() > MAX_RELATED_DECISIONS;
        var decisions = truncated
                ? List.copyOf(linkedDecisions.subList(0, MAX_RELATED_DECISIONS)) : linkedDecisions;
        event.put("relatedDecisions", decisions);
        event.put("relatedDecisionsTruncated", truncated);
        event.put("decisionLinkStatus", decisions.isEmpty() ? "PRIOR_DECISION_UNAVAILABLE" : "LINKED");
    }

    private void putBoundedText(Map<String, Object> decision, String field, String value) {
        var truncated = value != null && value.length() > MAX_DECISION_TEXT_LENGTH;
        decision.put(field, truncated ? value.substring(0, MAX_DECISION_TEXT_LENGTH) : value);
        decision.put(field + "Truncated", truncated);
    }

    private String eventSql(RiskEventsQuery query, Cursor cursor) {
        return """
                SELECT id, subject_key, previous_state, new_state, source_event_id,
                       evidence::text AS evidence, observed_at, created_at
                  FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'THESIS_REVIEW'
                """ + (query.ticker() == null ? "" : " AND subject_key = ?")
                + (query.since() == null ? "" : " AND observed_at >= ?")
                + (cursor == null ? "" : " AND (observed_at, id) < (?, ?)")
                + " ORDER BY observed_at DESC, id DESC LIMIT ?";
    }

    private Object[] eventParameters(UUID userId, RiskEventsQuery query, Cursor cursor) {
        var parameters = new ArrayList<Object>();
        parameters.add(userId);
        if (query.ticker() != null) parameters.add(query.ticker());
        if (query.since() != null) parameters.add(offset(query.since()));
        if (cursor != null) {
            parameters.add(offset(cursor.observedAt()));
            parameters.add(cursor.id());
        }
        parameters.add(query.limit() + 1);
        return parameters.toArray();
    }

    private RowMapper<EventRow> eventRowMapper() {
        return (rs, rowNum) -> new EventRow(rs.getObject("id", UUID.class), rs.getString("subject_key"),
                rs.getString("previous_state"), rs.getString("new_state"), rs.getString("source_event_id"),
                rs.getString("evidence"), rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private RiskEventsQuery normalize(RiskEventsQuery query) {
        if (query == null) return new RiskEventsQuery(null, null, true, 50, null);
        var ticker = query.ticker() == null ? null : query.ticker().trim().toUpperCase();
        if (ticker != null && !ticker.matches("[A-Z0-9._-]{1,32}"))
            throw new IllegalArgumentException("invalid ticker");
        var limit = query.limit() == null ? 50 : query.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (query.cursor() != null && query.cursor().length() > 512)
            throw new IllegalArgumentException("invalid cursor");
        return new RiskEventsQuery(ticker, query.since(), query.includeUnresolved() == null
                || query.includeUnresolved(), limit, query.cursor());
    }

    private Map<String, Object> telegramPayload(Map<String, Object> event) {
        var state = Objects.toString(event.get("currentState"), "UNKNOWN");
        var type = Objects.toString(event.get("eventType"), state);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("scope", SCOPE);
        payload.put("alertType", "REVIEW");
        payload.put("subjectKey", event.get("ticker"));
        payload.put("level", "RECOVERED".equals(state) ? "RECOVERED"
                : "NEAR".equals(state) ? "NEAR" : "BREACH");
        payload.put("eventType", type);
        payload.put("eventId", event.get("eventId"));
        payload.put("incidentId", event.get("incidentId"));
        payload.put("transition", event.get("transition"));
        payload.put("severity", event.get("severity"));
        payload.put("priceBasis", event.get("priceBasis"));
        payload.put("priceSession", event.get("priceSession"));
        payload.put("priceSource", event.get("priceSource"));
        payload.put("sessionDate", event.get("sessionDate"));
        payload.put("observedAt", event.get("observedAt"));
        payload.put("distancePct", distancePct(event.get("observedPrice"), event.get("triggerPrice")));
        payload.put("thesisStatusChanged", false);
        payload.put("orderAction", "NONE");
        payload.put("message", "RECOVERED".equals(state)
                ? "가격 위험구간 회복 확인 — 자동 thesis 변경·주문 없음"
                : "투자 논리 재검토 필요 — 자동 무효화·주문 없음");
        return payload;
    }

    private static String distancePct(Object priceValue, Object triggerValue) {
        var price = decimal(priceValue);
        var trigger = decimal(triggerValue);
        if (price == null || trigger == null || trigger.signum() <= 0) return null;
        var distance = price.subtract(trigger).multiply(BigDecimal.valueOf(100))
                .divide(trigger, 2, RoundingMode.HALF_UP);
        return (distance.signum() > 0 ? "+" : "") + distance.toPlainString();
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof JsonNode node) {
            if (node.isNumber()) return node.decimalValue();
            if (node.isTextual()) return new BigDecimal(node.asText());
        }
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String text) return new BigDecimal(text);
        return null;
    }

    private List<String> missingFields(Map<String, Object> event) {
        var missing = new ArrayList<String>();
        for (var field : REQUIRED_EVENT_FIELDS) {
            var value = event.get(field);
            if (value == null || value instanceof JsonNode node && node.isNull()) missing.add(field);
        }
        return List.copyOf(missing);
    }

    private Object nativeValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.decimalValue();
        if (value.isArray()) {
            var result = new ArrayList<>();
            value.forEach(item -> result.add(nativeValue(item)));
            return Collections.unmodifiableList(result);
        }
        if (value.isObject()) {
            var result = new LinkedHashMap<String, Object>();
            value.properties().forEach(field -> result.put(field.getKey(), nativeValue(field.getValue())));
            return Collections.unmodifiableMap(result);
        }
        return value.asText();
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("risk event evidence is invalid", exception);
        }
    }

    private String encodeCursor(EventRow row) {
        var content = row.observedAt().toString() + "\n" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String value) {
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\n", -1);
            if (decoded.length != 2) throw new IllegalArgumentException("invalid cursor");
            return new Cursor(Instant.parse(decoded[0]), UUID.fromString(decoded[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private record EventRow(UUID id, String ticker, String previousState, String currentState,
                            String dedupKey, String evidence, Instant observedAt, Instant persistedAt) {
    }

    private record UnresolvedRow(EventRow event, String currentState, String currentEvidence) {
    }

    private record DecisionLinkRow(UUID eventId, Map<String, Object> decision) {
    }

    private record Cursor(Instant observedAt, UUID id) {
    }

    public record RiskEventsQuery(String ticker, Instant since, Boolean includeUnresolved, Integer limit, String cursor) {
    }

    public record RiskEventsResult(List<Map<String, Object>> events,
                                   Map<String, Map<String, Object>> latestEventsByTicker,
                                   List<Map<String, Object>> unresolvedEvents,
                                   String nextCursor, boolean hasMore, Instant asOf, String status,
                                   String source, List<String> missingFields) {
    }
}
