package com.jmj.trade.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
class MonitoringEventReader {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    MonitoringEventReader(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    MonitoringEvaluationContract.EventBatch load(UUID userId, Set<String> symbols, Instant now) {
        var baseline = baseline(userId, now);
        var rows = jdbc.query("""
                SELECT broker_connection_id, source, source_event_id, event_type, summary,
                       affected_symbols::text, occurred_at, collected_at
                  FROM intelligence_events
                 WHERE user_id = ? AND source IN ('SEC', 'IR', 'FED', 'FRED', 'BLS', 'BEA')
                   AND collected_at > ?
                 ORDER BY collected_at DESC, id DESC
                 LIMIT 5000
                """, (resultSet, rowNum) -> new StoredEvent(
                resultSet.getObject("broker_connection_id", UUID.class),
                resultSet.getString("source"), resultSet.getString("source_event_id"),
                resultSet.getString("event_type"), resultSet.getString("summary"),
                resultSet.getString("affected_symbols"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("collected_at", OffsetDateTime.class).toInstant()),
                userId, baseline);
        var results = new ArrayList<MonitoringEvaluationContract.EventInput>();
        var shocks = new ArrayList<MonitoringEvaluationContract.ShockInput>();
        var incidents = new ArrayList<MonitoringEvaluationContract.IncidentInput>();
        for (var row : rows) {
            var signals = MonitoringOfficialSignalClassifier.classify(
                    row.source(), row.sourceEventId(), row.type(), row.summary(), row.occurredAt());
            shocks.addAll(signals.shocks());
            incidents.addAll(signals.incidents());
            if (symbols == null || symbols.isEmpty() || !Set.of("SEC", "IR").contains(row.source())) continue;
            var kind = kind(row.type(), row.summary(), row.source());
            if (kind == null) {
                continue;
            }
            for (var symbol : symbols(row.affectedSymbols())) {
                if (!symbols.contains(symbol)) {
                    continue;
                }
                var sourceType = sourceType(row.source());
                if (sourceType == null) {
                    continue;
                }
                var stableId = row.source() + ":" + symbol + ":" + digest(row.sourceEventId());
                results.add(new MonitoringEvaluationContract.EventInput(
                        stableId, symbol, kind, sourceType, null,
                        row.type() + " for " + symbol, row.summary(), row.occurredAt(), row.collectedAt(), true));
            }
        }
        var materialEvents = results.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MonitoringEvaluationContract.EventInput::sourceEventId,
                        event -> event,
                        (left, right) -> left,
                        java.util.TreeMap::new))
                .values().stream().toList();
        var uniqueShocks = shocks.stream().collect(java.util.stream.Collectors.toMap(
                value -> value.source() + ":" + value.category(), value -> value,
                (left, right) -> left, java.util.TreeMap::new)).values().stream().toList();
        var uniqueIncidents = incidents.stream().collect(java.util.stream.Collectors.toMap(
                value -> value.source() + ":" + value.kind(), value -> value,
                (left, right) -> left, java.util.TreeMap::new)).values().stream().toList();
        return new MonitoringEvaluationContract.EventBatch(materialEvents, uniqueShocks, uniqueIncidents);
    }

    private OffsetDateTime baseline(UUID userId, Instant now) {
        var observedAt = OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO monitoring_event_cursors (user_id, baseline_collected_at, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, userId, observedAt, observedAt);
        return jdbc.queryForObject("""
                SELECT baseline_collected_at FROM monitoring_event_cursors WHERE user_id = ?
                """, OffsetDateTime.class, userId);
    }

    private List<String> symbols(String json) {
        try {
            var values = objectMapper.readValue(json, String[].class);
            return values == null ? List.of() : java.util.Arrays.stream(values)
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        } catch (JacksonException exception) {
            return List.of();
        }
    }

    private static String sourceType(String source) {
        return switch (source.toUpperCase(Locale.ROOT)) {
            case "SEC" -> "SEC";
            case "IR" -> "COMPANY_IR";
            default -> null;
        };
    }

    private static String kind(String type, String summary, String source) {
        var normalizedType = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if ("SEC".equalsIgnoreCase(source)) {
            return normalizedType.matches("SEC_(8-K|10-Q|10-K|FILING)") ? "FILING" : null;
        }
        if (!"IR".equalsIgnoreCase(source)) {
            return null;
        }
        var explicit = normalizedType.replace("IR_", "");
        if (Set.of("GUIDANCE", "EARNINGS", "DILUTION", "CONTRACT", "MNA", "REGULATION",
                "OFFICER", "RATING", "DEFAULT", "PROJECT_DELAY").contains(explicit)) {
            return explicit;
        }
        var text = (normalizedType + " " + (summary == null ? "" : summary)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "guidance", "outlook", "preliminary results")) return "GUIDANCE";
        if (containsAny(text, "earnings", "quarterly results", "annual results")) return "EARNINGS";
        if (containsAny(text, "at-the-market", "dilution", "share offering", "share issuance", "shelf registration")) {
            return "DILUTION";
        }
        if (containsAny(text, "merger", "acquisition", "acquire", "takeover")) return "MNA";
        if (containsAny(text, "bankruptcy", "default", "chapter 11")) return "DEFAULT";
        if (containsAny(text, "ceo resign", "cfo resign", "chief executive officer appointed",
                "chief financial officer appointed", "ceo appointed", "cfo appointed")) return "OFFICER";
        if (containsAny(text, "fda approval", "regulatory approval", "regulatory investigation",
                "regulatory action")) return "REGULATION";
        if (containsAny(text, "credit rating", "rating downgrade", "rating upgrade")) return "RATING";
        if (containsAny(text, "contract award", "contract termination", "agreement terminated")) return "CONTRACT";
        if (containsAny(text, "project delay", "project delayed", "production delay")) return "PROJECT_DELAY";
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        for (var needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record StoredEvent(
            UUID connectionId,
            String source,
            String sourceEventId,
            String type,
            String summary,
            String affectedSymbols,
            Instant occurredAt,
            Instant collectedAt
    ) {
    }
}
