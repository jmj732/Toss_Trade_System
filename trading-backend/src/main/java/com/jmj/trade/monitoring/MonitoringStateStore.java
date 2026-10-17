package com.jmj.trade.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
class MonitoringStateStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    MonitoringStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Transactional
    StateTransition observe(
            UUID userId,
            String scope,
            String subjectKey,
            String state,
            Map<String, Object> evidence,
            String sourceType,
            String sourceEventId,
            Instant observedAt
    ) {
        require(userId, scope, subjectKey, state, sourceType, observedAt);
        observedAt = observedAt.truncatedTo(ChronoUnit.MICROS);
        var normalizedState = state.trim().toUpperCase();
        var now = now();
        jdbc.update("""
                INSERT INTO monitoring_current_states (user_id, scope, subject_key, state,
                    evidence, observed_at, updated_at)
                VALUES (?, ?, ?, 'UNKNOWN', '{}'::jsonb, ?, ?)
                ON CONFLICT (user_id, scope, subject_key) DO NOTHING
                """, userId, scope, subjectKey, offset(observedAt), now);
        var current = jdbc.query("""
                SELECT state, observed_at FROM monitoring_current_states
                 WHERE user_id = ? AND scope = ? AND subject_key = ?
                 FOR UPDATE
                """, (resultSet, rowNum) -> new CurrentState(
                resultSet.getString("state"),
                resultSet.getObject("observed_at", OffsetDateTime.class).toInstant()),
                userId, scope, subjectKey).stream().findFirst().orElseThrow();
        if (observedAt.isBefore(current.observedAt())) {
            return null;
        }
        var previous = current.state();
        if (sourceEventId != null && jdbc.queryForList("""
                SELECT 1 FROM monitoring_state_history
                 WHERE user_id = ? AND scope = ? AND source_type = ? AND source_event_id = ?
                """, Integer.class, userId, scope, sourceType, sourceEventId).size() > 0) {
            return null;
        }
        var normalizedEvidence = evidence == null ? Map.<String, Object>of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        var json = encode(normalizedEvidence);
        if (!Objects.equals(previous, normalizedState)) {
            var id = UUID.randomUUID();
            var inserted = jdbc.update("""
                    INSERT INTO monitoring_state_history (id, user_id, scope, subject_key,
                        previous_state, new_state, source_type, source_event_id, evidence,
                        observed_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    ON CONFLICT DO NOTHING
                    """, id, userId, scope, subjectKey, previous, normalizedState,
                    sourceType, sourceEventId, json, offset(observedAt), now);
            if (inserted != 1) {
                return null;
            }
            upsertCurrent(userId, scope, subjectKey, normalizedState, json, observedAt, now);
            updateWatchlistState(userId, scope, subjectKey, normalizedState, json, observedAt, now);
            return new StateTransition(id, userId, scope, subjectKey, previous, normalizedState,
                    normalizedEvidence, observedAt);
        }
        upsertCurrent(userId, scope, subjectKey, normalizedState, json, observedAt, now);
        updateWatchlistState(userId, scope, subjectKey, normalizedState, json, observedAt, now);
        return null;
    }

    /** Persists one approved-thesis price-risk state transition; repeated observations only refresh the snapshot. */
    @Transactional
    StateTransition observeThesisRisk(
            UUID userId,
            String ticker,
            String desiredState,
            Map<String, Object> observation,
            Instant observedAt
    ) {
        if (userId == null || ticker == null || ticker.isBlank() || desiredState == null
                || observation == null || observedAt == null) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
        var target = desiredState.trim().toUpperCase();
        if (!Set.of("NORMAL", "NEAR", "INTRADAY_BREACH", "PRICE_RISK_BREACH").contains(target)) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
        observedAt = observedAt.truncatedTo(ChronoUnit.MICROS);
        var now = now();
        jdbc.update("""
                INSERT INTO monitoring_current_states (user_id, scope, subject_key, state,
                    evidence, observed_at, updated_at)
                VALUES (?, 'THESIS_REVIEW', ?, 'NORMAL', '{}'::jsonb, ?, ?)
                ON CONFLICT (user_id, scope, subject_key) DO NOTHING
                """, userId, ticker, offset(observedAt), now);
        var current = jdbc.query("""
                SELECT state, evidence::text AS evidence, observed_at
                  FROM monitoring_current_states
                 WHERE user_id = ? AND scope = 'THESIS_REVIEW' AND subject_key = ?
                 FOR UPDATE
                """, (resultSet, rowNum) -> new RiskCurrentState(
                resultSet.getString("state"), resultSet.getString("evidence"),
                resultSet.getObject("observed_at", OffsetDateTime.class).toInstant()),
                userId, ticker).stream().findFirst().orElseThrow();
        if (observedAt.isBefore(current.observedAt())) return null;

        var previous = "UNKNOWN".equals(current.state()) ? "NORMAL" : current.state();
        if ("NORMAL".equals(target)) {
            if (isActiveRiskState(previous)) target = "RECOVERED";
            else {
                upsertCurrent(userId, "THESIS_REVIEW", ticker, previous,
                        encode(withLatestObservation(current.evidence(), observation)), observedAt, now);
                return null;
            }
        } else if (target.equals(previous)) {
            upsertCurrent(userId, "THESIS_REVIEW", ticker, previous,
                    encode(withLatestObservation(current.evidence(), observation)), observedAt, now);
            return null;
        }

        var incidentId = isActiveRiskState(previous) ? incidentId(current.evidence()) : null;
        if (incidentId == null) incidentId = UUID.randomUUID();
        var transitionName = transitionName(previous, target);
        var eventId = UUID.randomUUID();
        var dedupKey = String.join("|", userId.toString(), ticker, incidentId.toString(), transitionName,
                observedAt.toString());
        var event = new LinkedHashMap<String, Object>(observation);
        event.put("eventId", eventId.toString());
        event.put("incidentId", incidentId.toString());
        event.put("ticker", ticker);
        event.put("ruleId", "CONFIRMED_THESIS_PRICE_TRIGGER");
        event.put("eventType", target);
        event.put("transition", transitionName);
        event.put("severity", severity(target));
        event.put("previousState", previous);
        event.put("currentState", target);
        event.put("observedAt", observedAt.toString());
        event.put("occurredAt", observedAt.toString());
        event.put("resolvedAt", "RECOVERED".equals(target) ? observedAt.toString() : null);
        event.put("persistedAt", now.toInstant().toString());
        event.put("dedupKey", dedupKey);
        event.put("latestObservation", new LinkedHashMap<>(observation));
        var json = encode(event);
        var inserted = jdbc.update("""
                INSERT INTO monitoring_state_history (id, user_id, scope, subject_key,
                    previous_state, new_state, source_type, source_event_id, evidence,
                    observed_at, created_at)
                VALUES (?, ?, 'THESIS_REVIEW', ?, ?, ?, 'CONFIRMED_THESIS_PRICE_TRIGGER', ?,
                    CAST(? AS jsonb), ?, ?)
                ON CONFLICT DO NOTHING
                """, eventId, userId, ticker, previous, target, dedupKey, json,
                offset(observedAt), now);
        if (inserted != 1) return null;
        upsertCurrent(userId, "THESIS_REVIEW", ticker, target, json, observedAt, now);
        return new StateTransition(eventId, userId, "THESIS_REVIEW", ticker, previous, target,
                Collections.unmodifiableMap(event), observedAt);
    }

    @Transactional
    void remove(UUID userId, String scope, String subjectKey, Instant observedAt) {
        var now = now();
        var current = jdbc.query("""
                SELECT state, observed_at FROM monitoring_current_states
                 WHERE user_id = ? AND scope = ? AND subject_key = ? FOR UPDATE
                """, (resultSet, rowNum) -> new CurrentState(
                resultSet.getString("state"),
                resultSet.getObject("observed_at", OffsetDateTime.class).toInstant()),
                userId, scope, subjectKey).stream().findFirst().orElse(null);
        if (current != null) {
            var previous = current.state();
            var id = UUID.randomUUID();
            var evidence = encode(Map.of(
                    "action", "REMOVE_WATCHLIST",
                    "symbol", subjectKey,
                    "reason", "USER_REQUESTED"));
            jdbc.update("""
                    INSERT INTO monitoring_state_history (id, user_id, scope, subject_key,
                        previous_state, new_state, source_type, source_event_id, evidence,
                        observed_at, created_at)
                    VALUES (?, ?, ?, ?, ?, 'REMOVED', 'CONFIGURATION', ?, CAST(? AS jsonb), ?, ?)
                    """, id, userId, scope, subjectKey, previous, id.toString(), evidence,
                    offset(observedAt), now);
            jdbc.update("""
                    DELETE FROM monitoring_current_states
                     WHERE user_id = ? AND scope = ? AND subject_key = ?
                    """, userId, scope, subjectKey);
        }
    }

    private void upsertCurrent(
            UUID userId,
            String scope,
            String subjectKey,
            String state,
            String evidence,
            Instant observedAt,
            OffsetDateTime now
    ) {
        jdbc.update("""
                INSERT INTO monitoring_current_states (
                    user_id, scope, subject_key, state, evidence, observed_at, updated_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (user_id, scope, subject_key) DO UPDATE SET
                    state = EXCLUDED.state, evidence = EXCLUDED.evidence,
                    observed_at = EXCLUDED.observed_at, updated_at = EXCLUDED.updated_at
                """, userId, scope, subjectKey, state, evidence, offset(observedAt), now);
    }

    private void updateWatchlistState(
            UUID userId,
            String scope,
            String subjectKey,
            String state,
            String evidence,
            Instant observedAt,
            OffsetDateTime now
    ) {
        if ("WATCHLIST".equals(scope)) {
            jdbc.update("""
                    UPDATE monitoring_watchlist
                       SET status = ?, evidence = CAST(? AS jsonb), observed_at = ?, updated_at = ?
                     WHERE user_id = ? AND symbol = ?
                    """, state, evidence, offset(observedAt), now, userId, subjectKey);
        }
    }

    private Map<String, Object> withLatestObservation(String evidenceJson, Map<String, Object> observation) {
        var result = new LinkedHashMap<String, Object>();
        try {
            var node = objectMapper.readTree(evidenceJson);
            node.properties().forEach(field -> result.put(field.getKey(), field.getValue()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("monitoring evidence deserialization failed", exception);
        }
        result.put("latestObservation", new LinkedHashMap<>(observation));
        return result;
    }

    private UUID incidentId(String evidenceJson) {
        try {
            var value = objectMapper.readTree(evidenceJson).path("incidentId").asText(null);
            return value == null ? null : UUID.fromString(value);
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isActiveRiskState(String state) {
        return Set.of("NEAR", "INTRADAY_BREACH", "PRICE_RISK_BREACH").contains(state);
    }

    private static String transitionName(String previous, String target) {
        if ("RECOVERED".equals(target)) {
            return (previous.endsWith("BREACH") ? "BREACH" : previous) + "_TO_RECOVERED";
        }
        if ("RECOVERED".equals(previous)) {
            return isActiveRiskState(target) && !"NEAR".equals(target)
                    ? "RECOVERED_TO_BREACH" : "RECOVERED_TO_NEAR";
        }
        if ("NORMAL".equals(previous)) return "NORMAL_TO_" + target;
        if ("NEAR".equals(previous) && isActiveRiskState(target)) return "NEAR_TO_BREACH";
        if (isActiveRiskState(previous) && isActiveRiskState(target)) {
            return previous + "_TO_" + target;
        }
        return previous + "_TO_" + target;
    }

    private static String severity(String state) {
        return switch (state) {
            case "NEAR" -> "WARNING";
            case "INTRADAY_BREACH", "PRICE_RISK_BREACH" -> "CRITICAL";
            case "RECOVERED" -> "INFO";
            default -> "UNKNOWN";
        };
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("monitoring evidence serialization failed", exception);
        }
    }

    private static void require(UUID userId, String scope, String subjectKey, String state,
                                String sourceType, Instant observedAt) {
        if (userId == null || scope == null || subjectKey == null || subjectKey.isBlank()
                || state == null || state.isBlank() || sourceType == null || sourceType.isBlank()
                || observedAt == null) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private record CurrentState(String state, Instant observedAt) {
    }

    private record RiskCurrentState(String state, String evidence, Instant observedAt) {
    }

    record StateTransition(
            UUID id,
            UUID userId,
            String scope,
            String subjectKey,
            String previousState,
            String newState,
            Map<String, Object> evidence,
            Instant observedAt
    ) {
    }
}
