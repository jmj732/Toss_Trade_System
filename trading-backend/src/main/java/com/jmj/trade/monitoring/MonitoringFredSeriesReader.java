package com.jmj.trade.monitoring;

import com.jmj.trade.intelligence.EventIntelligenceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class MonitoringFredSeriesReader {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    MonitoringFredSeriesReader(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<MonitoringEvaluationContract.MetricSeries> load(UUID userId) {
        var rows = jdbc.query("""
                SELECT source_event_id, macro_scope::text, occurred_at, collected_at
                  FROM intelligence_events
                 WHERE user_id = ? AND source = 'FRED' AND event_type = 'FRED_OBSERVATION'
                 ORDER BY collected_at DESC, id DESC
                 LIMIT 5000
                """, (resultSet, rowNum) -> new FredRow(
                resultSet.getString("source_event_id"), resultSet.getString("macro_scope"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("collected_at", OffsetDateTime.class).toInstant()), userId);
        var unique = new LinkedHashMap<String, MonitoringFredSeriesAdapter.Observation>();
        for (var row : rows) {
            var identifier = fredSeries(row.macroScope());
            if (identifier == null || unique.containsKey(row.sourceEventId())) {
                continue;
            }
            MonitoringFredSeriesAdapter.parse(row.sourceEventId(), identifier,
                    row.occurredAt(), row.collectedAt())
                    .ifPresent(observation -> unique.put(row.sourceEventId(), observation));
        }
        var grouped = new HashMap<String, List<MonitoringFredSeriesAdapter.Observation>>();
        MonitoringFredSeriesAdapter.latestByMetricAndDate(List.copyOf(unique.values())).forEach(observation ->
                grouped.computeIfAbsent(observation.metric(), ignored -> new ArrayList<>()).add(observation));
        return grouped.entrySet().stream().map(entry -> {
            var observations = entry.getValue().stream()
                    .sorted(Comparator.comparing(MonitoringFredSeriesAdapter.Observation::asOf))
                    .toList();
            var points = observations.subList(Math.max(0, observations.size() - 60), observations.size())
                    .stream().map(value -> new MonitoringEvaluationContract.MetricPoint(
                            value.value(), value.asOf(), value.collectedAt())).toList();
            var latest = observations.getLast();
            return new MonitoringEvaluationContract.MetricSeries(
                    latest.metric(), latest.unit(), latest.source(), latest.cadence(), points);
        }).sorted(Comparator.comparing(MonitoringEvaluationContract.MetricSeries::metric)).toList();
    }

    private String fredSeries(String json) {
        try {
            for (var scope : objectMapper.readValue(json, EventIntelligenceService.MacroScope[].class)) {
                if ("FRED".equals(scope.provider())) {
                    return scope.identifier();
                }
            }
            return null;
        } catch (JacksonException exception) {
            return null;
        }
    }

    private record FredRow(String sourceEventId, String macroScope, Instant occurredAt, Instant collectedAt) {
    }
}
