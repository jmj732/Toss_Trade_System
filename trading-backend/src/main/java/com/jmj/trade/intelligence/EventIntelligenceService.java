package com.jmj.trade.intelligence;

import com.jmj.trade.analysis.PortfolioAnalysisContract;
import com.jmj.trade.analysis.PortfolioAnalysisWorkflowService;
import com.jmj.trade.notification.NotificationEventType;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

@Service
public class EventIntelligenceService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PortfolioAnalysisWorkflowService analysis;
    private final NotificationOutboxWriter notifications;

    EventIntelligenceService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PortfolioAnalysisWorkflowService analysis,
            NotificationOutboxWriter notifications
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Transactional
    public EventView create(UUID userId, UUID connectionId, CreateEvent command) {
        requireId(userId);
        requireId(connectionId);
        if (!ownedConnection(userId, connectionId)) {
            throw new EventIntelligenceException(
                    EventIntelligenceException.Code.CONNECTION_NOT_FOUND);
        }
        if (command == null || command.occurredAt() == null) {
            invalid();
        }
        var source = text(command.source(), 80);
        var sourceEventId = text(command.sourceEventId(), 200);
        var type = text(command.type(), 60);
        var summary = text(command.summary(), 1000);
        var symbols = symbols(command.affectedSymbols());
        var eventId = UUID.randomUUID();
        var collectedAt = now();
        var inserted = jdbc.update("""
                INSERT INTO intelligence_events (
                    id, user_id, broker_connection_id, source, source_event_id, event_type,
                    summary, affected_symbols, occurred_at, collected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (user_id, broker_connection_id, source, source_event_id)
                DO NOTHING
                """, eventId, userId, connectionId, source, sourceEventId, type, summary,
                encode(symbols), OffsetDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC),
                collectedAt);
        if (inserted != 1) {
            throw new EventIntelligenceException(
                    EventIntelligenceException.Code.EVENT_ALREADY_EXISTS);
        }
        notifications.emit(userId, NotificationEventType.EVENT_CREATED, eventId,
                Map.of(
                        "connectionId", connectionId,
                        "eventId", eventId,
                        "type", type,
                        "affectedSymbols", symbols),
                collectedAt.toInstant());
        return new EventView(eventId, source, sourceEventId, type, summary, symbols,
                command.occurredAt(), collectedAt.toInstant());
    }

    EventView get(UUID userId, UUID connectionId, UUID eventId) {
        requireId(userId);
        requireId(connectionId);
        requireId(eventId);
        return jdbc.query("""
                SELECT id, source, source_event_id, event_type, summary,
                       affected_symbols::text, occurred_at, collected_at
                  FROM intelligence_events
                 WHERE id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                """, (resultSet, rowNum) -> event(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source"),
                resultSet.getString("source_event_id"),
                resultSet.getString("event_type"),
                resultSet.getString("summary"),
                resultSet.getString("affected_symbols"),
                resultSet.getObject("occurred_at", OffsetDateTime.class),
                resultSet.getObject("collected_at", OffsetDateTime.class)
        ), eventId, userId, connectionId).stream().findFirst().orElseThrow(() ->
                new EventIntelligenceException(EventIntelligenceException.Code.EVENT_NOT_FOUND));
    }

    List<EventView> list(UUID userId, UUID connectionId, int limit) {
        requireId(userId);
        requireId(connectionId);
        if (limit < 1 || limit > 100) {
            invalid();
        }
        if (!ownedConnection(userId, connectionId)) {
            throw new EventIntelligenceException(
                    EventIntelligenceException.Code.CONNECTION_NOT_FOUND);
        }
        return jdbc.query("""
                SELECT id, source, source_event_id, event_type, summary,
                       affected_symbols::text, occurred_at, collected_at
                  FROM intelligence_events
                 WHERE user_id = ?
                   AND broker_connection_id = ?
                 ORDER BY collected_at DESC, id DESC
                 LIMIT ?
                """, (resultSet, rowNum) -> event(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source"),
                resultSet.getString("source_event_id"),
                resultSet.getString("event_type"),
                resultSet.getString("summary"),
                resultSet.getString("affected_symbols"),
                resultSet.getObject("occurred_at", OffsetDateTime.class),
                resultSet.getObject("collected_at", OffsetDateTime.class)
        ), userId, connectionId, limit);
    }

    ComparisonView reanalyze(UUID userId, UUID connectionId, UUID eventId) {
        var event = get(userId, connectionId, eventId);
        if (findComparison(userId, connectionId, eventId).isPresent()) {
            throw new EventIntelligenceException(
                    EventIntelligenceException.Code.EVENT_ALREADY_ANALYZED);
        }
        var execution = analysis.completedEventAnalysis(userId, connectionId, eventId)
                .orElseGet(() -> analysis.executeForEvent(userId, connectionId, eventId));
        var previous = execution.previous();
        var current = execution.current();
        var comparison = compare(event.affectedSymbols(),
                previous == null ? null : previous.result(), current.result());
        var createdAt = now();
        var inserted = jdbc.update("""
                INSERT INTO event_analysis_comparisons (
                    id, event_id, user_id, broker_connection_id, previous_analysis_run_id,
                    new_analysis_run_id, comparison, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (event_id) DO NOTHING
                """, UUID.randomUUID(), eventId, userId, connectionId,
                previous == null ? null : previous.runId(), current.runId(),
                encode(comparison), createdAt);
        if (inserted != 1) {
            throw new EventIntelligenceException(
                    EventIntelligenceException.Code.EVENT_ALREADY_ANALYZED);
        }
        return new ComparisonView(eventId, previous == null ? null : previous.runId(),
                current.runId(), createdAt.toInstant(), comparison);
    }

    ComparisonView getComparison(UUID userId, UUID connectionId, UUID eventId) {
        get(userId, connectionId, eventId);
        return findComparison(userId, connectionId, eventId).orElseThrow(() ->
                new EventIntelligenceException(
                        EventIntelligenceException.Code.COMPARISON_NOT_FOUND));
    }

    java.util.Optional<ComparisonView> findComparison(
            UUID userId,
            UUID connectionId,
            UUID eventId
    ) {
        return jdbc.query("""
                SELECT event_id, previous_analysis_run_id, new_analysis_run_id,
                       created_at, comparison::text
                  FROM event_analysis_comparisons
                 WHERE event_id = ?
                   AND user_id = ?
                   AND broker_connection_id = ?
                """, (resultSet, rowNum) -> new ComparisonView(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("previous_analysis_run_id", UUID.class),
                resultSet.getObject("new_analysis_run_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                decode(resultSet.getString("comparison"), Comparison.class)
        ), eventId, userId, connectionId).stream().findFirst();
    }

    private Comparison compare(
            List<String> affectedSymbols,
            PortfolioAnalysisContract.Response previous,
            PortfolioAnalysisContract.Response current
    ) {
        var affected = Set.copyOf(affectedSymbols);
        var previousPositions = previous == null ? List.<PortfolioAnalysisContract.PositionAnalysis>of()
                : previous.positions();
        var positionKeys = Stream.concat(previousPositions.stream(), current.positions().stream())
                .filter(position -> affected.contains(position.symbol().toUpperCase(Locale.ROOT)))
                .map(position -> new PositionKey(position.symbol(), position.currency()))
                .distinct()
                .sorted(Comparator.comparing(PositionKey::symbol)
                        .thenComparing(key -> key.currency().name()))
                .toList();
        var positions = positionKeys.stream().map(key -> {
            var before = find(previousPositions, key, PortfolioAnalysisContract.PositionAnalysis::symbol,
                    PortfolioAnalysisContract.PositionAnalysis::currency);
            var after = find(current.positions(), key,
                    PortfolioAnalysisContract.PositionAnalysis::symbol,
                    PortfolioAnalysisContract.PositionAnalysis::currency);
            var beforeMarket = value(previous, before, PortfolioAnalysisContract.PositionAnalysis::marketValue);
            var afterMarket = value(current, after, PortfolioAnalysisContract.PositionAnalysis::marketValue);
            var beforeProfit = value(previous, before, PortfolioAnalysisContract.PositionAnalysis::profitLoss);
            var afterProfit = value(current, after, PortfolioAnalysisContract.PositionAnalysis::profitLoss);
            var beforeWeight = value(previous, before, PortfolioAnalysisContract.PositionAnalysis::weight);
            var afterWeight = value(current, after, PortfolioAnalysisContract.PositionAnalysis::weight);
            return new PositionComparison(key.symbol(), key.currency(),
                    beforeMarket, afterMarket, change(beforeMarket, afterMarket),
                    beforeProfit, afterProfit, change(beforeProfit, afterProfit),
                    beforeWeight, afterWeight, change(beforeWeight, afterWeight));
        }).toList();

        var previousTotals = previous == null ? List.<PortfolioAnalysisContract.CurrencyTotal>of()
                : previous.currencyTotals();
        var currencies = Stream.concat(previousTotals.stream(), current.currencyTotals().stream())
                .map(PortfolioAnalysisContract.CurrencyTotal::currency)
                .distinct()
                .sorted()
                .toList();
        var totals = currencies.stream().map(currency -> {
            var before = previousTotals.stream()
                    .filter(total -> total.currency() == currency).findFirst().orElse(null);
            var after = current.currencyTotals().stream()
                    .filter(total -> total.currency() == currency).findFirst().orElse(null);
            var beforeMarket = value(previous, before, PortfolioAnalysisContract.CurrencyTotal::marketValue);
            var afterMarket = value(current, after, PortfolioAnalysisContract.CurrencyTotal::marketValue);
            var beforeProfit = value(previous, before, PortfolioAnalysisContract.CurrencyTotal::profitLoss);
            var afterProfit = value(current, after, PortfolioAnalysisContract.CurrencyTotal::profitLoss);
            var beforeConcentration = value(previous, before,
                    PortfolioAnalysisContract.CurrencyTotal::concentration);
            var afterConcentration = value(current, after,
                    PortfolioAnalysisContract.CurrencyTotal::concentration);
            return new CurrencyComparison(currency,
                    beforeMarket, afterMarket, change(beforeMarket, afterMarket),
                    beforeProfit, afterProfit, change(beforeProfit, afterProfit),
                    beforeConcentration, afterConcentration,
                    change(beforeConcentration, afterConcentration));
        }).toList();
        return new Comparison(previous != null, positions, totals);
    }

    private static PortfolioAnalysisContract.PositionAnalysis find(
            List<PortfolioAnalysisContract.PositionAnalysis> positions,
            PositionKey key,
            Function<PortfolioAnalysisContract.PositionAnalysis, String> symbol,
            Function<PortfolioAnalysisContract.PositionAnalysis,
                    PortfolioAnalysisContract.Currency> currency
    ) {
        return positions.stream()
                .filter(position -> symbol.apply(position).equals(key.symbol())
                        && currency.apply(position) == key.currency())
                .findFirst().orElse(null);
    }

    private static <T> BigDecimal value(
            PortfolioAnalysisContract.Response response,
            T item,
            Function<T, BigDecimal> getter
    ) {
        if (response == null) {
            return null;
        }
        return item == null ? BigDecimal.ZERO : getter.apply(item);
    }

    private static BigDecimal change(BigDecimal before, BigDecimal after) {
        return before == null || after == null ? null : after.subtract(before);
    }

    private EventView event(
            UUID id,
            String source,
            String sourceEventId,
            String type,
            String summary,
            String affectedSymbols,
            OffsetDateTime occurredAt,
            OffsetDateTime collectedAt
    ) {
        return new EventView(id, source, sourceEventId, type, summary,
                decodeSymbols(affectedSymbols), occurredAt.toInstant(), collectedAt.toInstant());
    }

    private boolean ownedConnection(UUID userId, UUID connectionId) {
        return !jdbc.queryForList("""
                SELECT 1
                  FROM broker_connections
                 WHERE id = ?
                   AND user_id = ?
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                """, Integer.class, connectionId, userId).isEmpty();
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("event intelligence serialization failed", exception);
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored event intelligence is invalid", exception);
        }
    }

    private List<String> decodeSymbols(String json) {
        return List.copyOf(List.of(decode(json, String[].class)));
    }

    private static String text(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            invalid();
        }
        var normalized = value.trim();
        if (normalized.length() > maxLength) {
            invalid();
        }
        return normalized;
    }

    private static List<String> symbols(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > 100) {
            invalid();
        }
        var symbols = new LinkedHashSet<String>();
        for (var value : values) {
            var symbol = text(value, 32).toUpperCase(Locale.ROOT);
            if (!symbol.matches("[A-Z0-9._-]+")) {
                invalid();
            }
            symbols.add(symbol);
        }
        return List.copyOf(symbols);
    }

    private static void requireId(UUID value) {
        if (value == null) {
            invalid();
        }
    }

    private static void invalid() {
        throw new EventIntelligenceException(EventIntelligenceException.Code.INVALID_INPUT);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public record CreateEvent(
            String source,
            String sourceEventId,
            String type,
            String summary,
            List<String> affectedSymbols,
            Instant occurredAt
    ) {
    }

    public record EventView(
            UUID id,
            String source,
            String sourceEventId,
            String type,
            String summary,
            List<String> affectedSymbols,
            Instant occurredAt,
            Instant collectedAt
    ) {
    }

    public record ComparisonView(
            UUID eventId,
            UUID previousAnalysisRunId,
            UUID newAnalysisRunId,
            Instant createdAt,
            Comparison comparison
    ) {
    }

    public record Comparison(
            boolean baselineAvailable,
            List<PositionComparison> positions,
            List<CurrencyComparison> currencyTotals
    ) {
    }

    public record PositionComparison(
            String symbol,
            PortfolioAnalysisContract.Currency currency,
            BigDecimal beforeMarketValue,
            BigDecimal afterMarketValue,
            BigDecimal marketValueChange,
            BigDecimal beforeProfitLoss,
            BigDecimal afterProfitLoss,
            BigDecimal profitLossChange,
            BigDecimal beforeWeight,
            BigDecimal afterWeight,
            BigDecimal weightChange
    ) {
    }

    public record CurrencyComparison(
            PortfolioAnalysisContract.Currency currency,
            BigDecimal beforeMarketValue,
            BigDecimal afterMarketValue,
            BigDecimal marketValueChange,
            BigDecimal beforeProfitLoss,
            BigDecimal afterProfitLoss,
            BigDecimal profitLossChange,
            BigDecimal beforeConcentration,
            BigDecimal afterConcentration,
            BigDecimal concentrationChange
    ) {
    }

    private record PositionKey(
            String symbol,
            PortfolioAnalysisContract.Currency currency
    ) {
    }
}
