package com.jmj.trade.account;

import com.jmj.trade.broker.connection.BrokerConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads the asset/P&amp;L trend across a connection's already-persisted successful sync
 * snapshots. Amounts are the broker-reported, already-stored figures per currency
 * (no FX conversion, no return-rate estimation) — this is the multi-point time-series sibling
 * of {@link PortfolioReadService}, which reads only the single latest snapshot.
 */
@Service
public final class PortfolioHistoryService {

    private static final int MAX_ROWS = 2000;
    private static final int MIN_MAX_POINTS = 2;
    private static final int MAX_MAX_POINTS = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PortfolioReadService portfolios;

    PortfolioHistoryService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PortfolioReadService portfolios
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.portfolios = Objects.requireNonNull(portfolios, "portfolios");
    }

    PortfolioHistoryView read(
            UUID userId,
            UUID connectionId,
            Instant from,
            Instant to,
            int maxPoints
    ) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (maxPoints < MIN_MAX_POINTS || maxPoints > MAX_MAX_POINTS) {
            throw new PortfolioHistoryException(PortfolioHistoryException.Code.INVALID_INPUT);
        }
        var effectiveFrom = from == null ? Instant.EPOCH : from;
        var effectiveTo = to == null ? Instant.now() : to;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new PortfolioHistoryException(PortfolioHistoryException.Code.INVALID_INPUT);
        }
        requireOwnedConnection(userId, connectionId);

        if (!hasAnySuccessfulRun(userId, connectionId)) {
            return unavailable("PORTFOLIO_HISTORY_NOT_FOUND");
        }

        var totalMatched = countMatching(userId, connectionId, effectiveFrom, effectiveTo);
        var rows = fetchRows(userId, connectionId, effectiveFrom, effectiveTo);
        var points = downsample(rows, maxPoints);
        var quality = currentQuality(userId, connectionId);

        return new PortfolioHistoryView(
                quality.stale(),
                quality.staleReason(),
                quality.unknown(),
                quality.unknownFields(),
                false,
                null,
                new PortfolioHistoryData(
                        effectiveFrom, effectiveTo, points.size() < totalMatched,
                        totalMatched, points.size(), points));
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

    private boolean hasAnySuccessfulRun(UUID userId, UUID connectionId) {
        return !jdbc.queryForList("""
                SELECT 1
                  FROM account_sync_runs
                 WHERE user_id = ?
                   AND broker_connection_id = ?
                   AND status = 'SUCCEEDED'
                 LIMIT 1
                """, Integer.class, userId, connectionId).isEmpty();
    }

    private int countMatching(UUID userId, UUID connectionId, Instant from, Instant to) {
        return jdbc.queryForObject("""
                SELECT count(*)
                  FROM account_sync_runs run
                  JOIN account_snapshots snapshot ON snapshot.sync_run_id = run.id
                 WHERE run.user_id = ?
                   AND run.broker_connection_id = ?
                   AND run.status = 'SUCCEEDED'
                   AND run.completed_at >= ?
                   AND run.completed_at <= ?
                """, Integer.class, userId, connectionId, offset(from), offset(to));
    }

    /**
     * Ordered oldest-first for downsampling, but fetched newest-first under the row cap so a
     * connection with more history than {@link #MAX_ROWS} keeps its most recent points rather
     * than silently dropping them in favor of the oldest ones.
     */
    private List<Row> fetchRows(UUID userId, UUID connectionId, Instant from, Instant to) {
        var rows = jdbc.query("""
                SELECT run.id AS sync_run_id, run.completed_at,
                       snapshot.market_value_amounts, snapshot.profit_loss_amounts,
                       snapshot.profit_loss_rate, snapshot.daily_profit_loss_rate
                  FROM account_sync_runs run
                  JOIN account_snapshots snapshot ON snapshot.sync_run_id = run.id
                 WHERE run.user_id = ?
                   AND run.broker_connection_id = ?
                   AND run.status = 'SUCCEEDED'
                   AND run.completed_at >= ?
                   AND run.completed_at <= ?
                 ORDER BY run.completed_at DESC, run.id DESC
                 LIMIT ?
                """, (resultSet, rowNum) -> new Row(
                resultSet.getObject("sync_run_id", UUID.class),
                resultSet.getObject("completed_at", OffsetDateTime.class).toInstant(),
                amounts(resultSet.getString("market_value_amounts")),
                amounts(resultSet.getString("profit_loss_amounts")),
                resultSet.getBigDecimal("profit_loss_rate"),
                resultSet.getBigDecimal("daily_profit_loss_rate")
        ), userId, connectionId, offset(from), offset(to), MAX_ROWS);
        var ascending = new ArrayList<>(rows);
        Collections.reverse(ascending);
        return ascending;
    }

    private static List<PortfolioHistoryPoint> downsample(List<Row> rows, int maxPoints) {
        var selected = rows.size() <= maxPoints ? rows : evenlySpaced(rows, maxPoints);
        return selected.stream()
                .map(row -> new PortfolioHistoryPoint(
                        row.syncRunId(), row.completedAt(), row.marketValueAmounts(),
                        row.profitLossAmounts(), row.profitLossRate(), row.dailyProfitLossRate()))
                .toList();
    }

    private static List<Row> evenlySpaced(List<Row> rows, int maxPoints) {
        var result = new ArrayList<Row>(maxPoints);
        var lastIndex = rows.size() - 1;
        for (var i = 0; i < maxPoints; i++) {
            var index = (int) Math.round(i * (double) lastIndex / (maxPoints - 1));
            result.add(rows.get(index));
        }
        return result;
    }

    /**
     * {@code hasAnySuccessfulRun} already confirmed at least one successful run exists, so
     * reaching this catch means {@link PortfolioReadService} found none under the
     * connection's *current* credential revision — i.e. every run this history includes
     * predates a credential rotation. That's not "no known issue"; it's "freshness cannot be
     * verified right now," so it's reported as stale rather than defaulting to available.
     */
    private Quality currentQuality(UUID userId, UUID connectionId) {
        try {
            var portfolio = portfolios.read(userId, connectionId);
            return new Quality(
                    portfolio.stale(), portfolio.staleReason(),
                    !portfolio.unknownFields().isEmpty(), portfolio.unknownFields());
        } catch (PortfolioReadException exception) {
            return new Quality(true, "CREDENTIAL_REVISION_CHANGED", false, List.of());
        }
    }

    private static PortfolioHistoryView unavailable(String reason) {
        return new PortfolioHistoryView(false, null, false, List.of(), true, reason, null);
    }

    private Map<String, BigDecimal> amounts(String json) {
        try {
            return Map.copyOf(objectMapper.readValue(
                    json, new TypeReference<Map<String, BigDecimal>>() {
                    }));
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored snapshot amounts are invalid", exception);
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Row(
            UUID syncRunId,
            Instant completedAt,
            Map<String, BigDecimal> marketValueAmounts,
            Map<String, BigDecimal> profitLossAmounts,
            BigDecimal profitLossRate,
            BigDecimal dailyProfitLossRate
    ) {
    }

    private record Quality(boolean stale, String staleReason, boolean unknown, List<String> unknownFields) {
    }

    public record PortfolioHistoryView(
            boolean stale,
            String staleReason,
            boolean unknown,
            List<String> unknownFields,
            boolean unavailable,
            String unavailableReason,
            PortfolioHistoryData data
    ) {
    }

    public record PortfolioHistoryData(
            Instant from,
            Instant to,
            boolean partial,
            int totalMatched,
            int returnedPoints,
            List<PortfolioHistoryPoint> points
    ) {
    }

    public record PortfolioHistoryPoint(
            UUID syncRunId,
            Instant completedAt,
            Map<String, BigDecimal> marketValueAmounts,
            Map<String, BigDecimal> profitLossAmounts,
            BigDecimal profitLossRate,
            BigDecimal dailyProfitLossRate
    ) {
    }
}
