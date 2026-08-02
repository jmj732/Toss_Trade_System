package com.jmj.trade.dashboard;

import com.jmj.trade.account.PortfolioReadException;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.analysis.PortfolioAnalysisWorkflowService;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import com.jmj.trade.intelligence.EventIntelligenceService;
import com.jmj.trade.order.OrderIntentStatus;
import com.jmj.trade.order.OrderSide;
import com.jmj.trade.order.OrderType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public final class DashboardReadModelService {

    private static final int COLLECTION_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PortfolioReadService portfolios;
    private final PortfolioAnalysisWorkflowService analyses;

    DashboardReadModelService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PortfolioReadService portfolios,
            PortfolioAnalysisWorkflowService analyses
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.portfolios = Objects.requireNonNull(portfolios, "portfolios");
        this.analyses = Objects.requireNonNull(analyses, "analyses");
    }

    DashboardView read(UUID userId, UUID connectionId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        requireOwnedConnection(userId, connectionId);

        PortfolioReadService.PortfolioView portfolio = null;
        try {
            portfolio = portfolios.read(userId, connectionId);
        } catch (PortfolioReadException ignored) {
            // Missing source is section quality, not whole-dashboard failure.
        }
        var analysis = analyses.latestOptional(userId, connectionId).orElse(null);

        return new DashboardView(
                portfolioSection(portfolio),
                analysisSection(analysis, portfolio),
                available(pendingEvents(userId, connectionId), false, List.of()),
                available(pendingProposals(userId, connectionId), false, List.of()));
    }

    private void requireOwnedConnection(UUID userId, UUID connectionId) {
        if (jdbc.queryForList("""
                SELECT 1
                  FROM broker_connections
                 WHERE id = ?
                   AND user_id = ?
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                """, Integer.class, connectionId, userId).isEmpty()) {
            throw BrokerConnectionException.notFound();
        }
    }

    private Section<PortfolioReadService.PortfolioView> portfolioSection(
            PortfolioReadService.PortfolioView portfolio
    ) {
        if (portfolio == null) {
            return unavailable("PORTFOLIO_SNAPSHOT_NOT_FOUND");
        }
        return available(portfolio, portfolio.stale(), portfolio.unknownFields());
    }

    private Section<PortfolioAnalysisWorkflowService.AnalysisView> analysisSection(
            PortfolioAnalysisWorkflowService.AnalysisView analysis,
            PortfolioReadService.PortfolioView portfolio
    ) {
        if (analysis == null) {
            return unavailable("ANALYSIS_RESULT_NOT_FOUND");
        }
        var quality = analysis.result().quality();
        var stale = quality.stale()
                || portfolio != null && !analysis.inputSnapshotId().equals(portfolio.syncRunId());
        return available(analysis, stale, quality.unknownFields());
    }

    private List<PendingEventView> pendingEvents(UUID userId, UUID connectionId) {
        return jdbc.query("""
                SELECT event.id, event.source, event.source_event_id, event.event_type,
                       event.summary, event.affected_symbols::text, event.occurred_at,
                       event.macro_scope::text, event.collected_at,
                       EXISTS (
                           SELECT 1
                             FROM event_analysis_comparisons comparison
                            WHERE comparison.event_id = event.id
                              AND comparison.user_id = event.user_id
                              AND comparison.broker_connection_id =
                                  event.broker_connection_id
                       ) AS comparison_available
                  FROM intelligence_events event
                  LEFT JOIN event_reviews review
                    ON review.event_id = event.id
                   AND review.user_id = event.user_id
                   AND review.broker_connection_id = event.broker_connection_id
                 WHERE event.user_id = ?
                   AND event.broker_connection_id = ?
                   AND review.event_id IS NULL
                 ORDER BY event.collected_at DESC, event.id DESC
                 LIMIT ?
                """, (resultSet, rowNumber) -> new PendingEventView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source"),
                resultSet.getString("source_event_id"),
                resultSet.getString("event_type"),
                resultSet.getString("summary"),
                decodeSymbols(resultSet.getString("affected_symbols")),
                decodeMacroScope(resultSet.getString("macro_scope")),
                instant(resultSet.getObject("occurred_at", OffsetDateTime.class)),
                instant(resultSet.getObject("collected_at", OffsetDateTime.class)),
                resultSet.getBoolean("comparison_available")
        ), userId, connectionId, COLLECTION_LIMIT);
    }

    private List<PendingProposalView> pendingProposals(UUID userId, UUID connectionId) {
        return jdbc.query("""
                SELECT id, side, order_type, symbol, quantity, limit_price,
                       trading_currency, status
                  FROM order_intents
                 WHERE user_id = ?
                   AND broker_connection_id = ?
                   AND status = 'PROPOSED'
                 ORDER BY symbol, id
                 LIMIT ?
                """, (resultSet, rowNumber) -> new PendingProposalView(
                resultSet.getObject("id", UUID.class),
                OrderSide.valueOf(resultSet.getString("side")),
                OrderType.valueOf(resultSet.getString("order_type")),
                resultSet.getString("symbol"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getBigDecimal("limit_price"),
                Currency.valueOf(resultSet.getString("trading_currency")),
                OrderIntentStatus.valueOf(resultSet.getString("status"))
        ), userId, connectionId, COLLECTION_LIMIT);
    }

    private List<String> decodeSymbols(String json) {
        try {
            return List.copyOf(List.of(objectMapper.readValue(json, String[].class)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored event symbols are invalid", exception);
        }
    }

    private List<EventIntelligenceService.MacroScope> decodeMacroScope(String json) {
        try {
            return List.of(objectMapper.readValue(
                    json, EventIntelligenceService.MacroScope[].class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored event macro scope is invalid", exception);
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private static <T> Section<T> available(
            T data,
            boolean stale,
            List<String> unknownFields
    ) {
        var fields = List.copyOf(unknownFields);
        return new Section<>(stale, !fields.isEmpty(), fields, false, null, data);
    }

    private static <T> Section<T> unavailable(String reason) {
        return new Section<>(false, false, List.of(), true, reason, null);
    }

    public record DashboardView(
            Section<PortfolioReadService.PortfolioView> portfolio,
            Section<PortfolioAnalysisWorkflowService.AnalysisView> analysis,
            Section<List<PendingEventView>> pendingEvents,
            Section<List<PendingProposalView>> pendingOrderProposals
    ) {
    }

    public record Section<T>(
            boolean stale,
            boolean unknown,
            List<String> unknownFields,
            boolean unavailable,
            String unavailableReason,
            T data
    ) {
    }

    public record PendingEventView(
            UUID id,
            String source,
            String sourceEventId,
            String type,
            String summary,
            List<String> affectedSymbols,
            List<EventIntelligenceService.MacroScope> macroScope,
            Instant occurredAt,
            Instant collectedAt,
            boolean comparisonAvailable
    ) {
    }

    public record PendingProposalView(
            UUID id,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency,
            OrderIntentStatus status
    ) {
    }
}
