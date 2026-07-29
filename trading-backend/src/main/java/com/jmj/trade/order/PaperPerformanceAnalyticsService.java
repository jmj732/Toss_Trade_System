package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Computes realized/unrealized P&amp;L, fees/tax, win rate, turnover and max drawdown from a
 * connection's already-persisted paper fills ({@code execution_snapshots} joined through
 * {@code broker_orders} to {@code order_intents}). Every order in this system is a paper
 * order, so no live/paper filter is needed. KRW/USD are computed and reported fully
 * separately, never summed or converted.
 */
@Service
public final class PaperPerformanceAnalyticsService {

    private static final int MIN_MAX_POINTS = 2;
    private static final int MAX_MAX_POINTS = 500;

    private final JdbcTemplate jdbc;

    PaperPerformanceAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    PaperPerformanceView read(
            UUID userId,
            UUID connectionId,
            Instant from,
            Instant to,
            int maxPoints
    ) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (maxPoints < MIN_MAX_POINTS || maxPoints > MAX_MAX_POINTS) {
            throw new PaperPerformanceAnalyticsException(PaperPerformanceAnalyticsException.Code.INVALID_INPUT);
        }
        var effectiveFrom = from == null ? Instant.EPOCH : from;
        var effectiveTo = to == null ? Instant.now() : to;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new PaperPerformanceAnalyticsException(PaperPerformanceAnalyticsException.Code.INVALID_INPUT);
        }
        requireOwnedConnection(userId, connectionId);

        var currenciesEverTraded = distinctCurrenciesEver(userId, connectionId);
        if (currenciesEverTraded.isEmpty()) {
            return unavailable("PAPER_PERFORMANCE_NOT_FOUND");
        }

        var fillsUpToCutoff = fetchFills(userId, connectionId, effectiveTo);
        var fillsByCurrency = new HashMap<Currency, List<Fill>>();
        for (var fill : fillsUpToCutoff) {
            fillsByCurrency.computeIfAbsent(fill.currency(), key -> new ArrayList<>()).add(fill);
        }
        var byCurrency = new EnumMap<Currency, PaperPerformanceBreakdown>(Currency.class);
        for (var currency : currenciesEverTraded) {
            var fills = fillsByCurrency.getOrDefault(currency, List.of());
            byCurrency.put(currency, breakdown(fills, effectiveFrom, effectiveTo, maxPoints));
        }

        return new PaperPerformanceView(
                false, null, new PaperPerformanceData(effectiveFrom, effectiveTo, byCurrency));
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

    /**
     * The set of currencies this connection has ever traded in paper fills, independent of
     * any requested period — used both for the {@code unavailable} check and to seed
     * {@code byCurrency} so a currency's presence in the response doesn't flicker as the
     * user narrows {@code from}/{@code to} past its only fills. Uses the same "latest
     * snapshot per broker order" shape as {@link #fetchFills} so the two queries agree on
     * what counts as a fill.
     */
    private Set<Currency> distinctCurrenciesEver(UUID userId, UUID connectionId) {
        var currencies = jdbc.query("""
                SELECT DISTINCT es.currency
                  FROM order_intents oi
                  JOIN broker_orders bo ON bo.order_intent_id = oi.id
                  JOIN LATERAL (
                        SELECT es2.filled_quantity, es2.currency
                          FROM execution_snapshots es2
                         WHERE es2.broker_order_id = bo.id
                         ORDER BY es2.captured_at DESC, es2.id DESC
                         LIMIT 1
                       ) es ON true
                 WHERE oi.user_id = ?
                   AND oi.broker_connection_id = ?
                   AND es.filled_quantity > 0
                   AND es.currency IS NOT NULL
                """, (resultSet, rowNum) -> Currency.valueOf(resultSet.getString("currency")),
                userId, connectionId);
        return currencies.isEmpty() ? Set.of() : EnumSet.copyOf(currencies);
    }

    /**
     * One row per broker order = its latest execution snapshot (cumulative fields, so the
     * latest row already is the effective fill), ascending by fill time, bounded by
     * {@code to} so unrealized P&amp;L reflects only what had happened by the cutoff.
     */
    private List<Fill> fetchFills(UUID userId, UUID connectionId, Instant to) {
        return jdbc.query("""
                SELECT oi.symbol, oi.side,
                       es.filled_quantity, es.average_filled_price, es.filled_amount,
                       es.commission, es.tax, es.currency, es.captured_at
                  FROM order_intents oi
                  JOIN broker_orders bo ON bo.order_intent_id = oi.id
                  JOIN LATERAL (
                        SELECT es2.filled_quantity, es2.average_filled_price, es2.filled_amount,
                               es2.commission, es2.tax, es2.currency, es2.captured_at
                          FROM execution_snapshots es2
                         WHERE es2.broker_order_id = bo.id
                         ORDER BY es2.captured_at DESC, es2.id DESC
                         LIMIT 1
                       ) es ON true
                 WHERE oi.user_id = ?
                   AND oi.broker_connection_id = ?
                   AND es.filled_quantity > 0
                   AND es.currency IS NOT NULL
                   AND oi.side IS NOT NULL
                   AND es.captured_at <= ?
                 ORDER BY es.captured_at ASC, bo.id ASC
                """, (resultSet, rowNum) -> new Fill(
                resultSet.getString("symbol"),
                OrderSide.valueOf(resultSet.getString("side")),
                resultSet.getBigDecimal("filled_quantity"),
                resultSet.getBigDecimal("average_filled_price"),
                resultSet.getBigDecimal("filled_amount"),
                resultSet.getBigDecimal("commission"),
                resultSet.getBigDecimal("tax"),
                Currency.valueOf(resultSet.getString("currency")),
                resultSet.getObject("captured_at", OffsetDateTime.class).toInstant()
        ), userId, connectionId, offset(to));
    }

    private PaperPerformanceBreakdown breakdown(
            List<Fill> fills,
            Instant from,
            Instant to,
            int maxPoints
    ) {
        var lotsBySymbol = new HashMap<String, Deque<Lot>>();
        var lastPriceBySymbol = new HashMap<String, BigDecimal>();
        var realizedPnl = BigDecimal.ZERO;
        var totalFees = BigDecimal.ZERO;
        var totalTax = BigDecimal.ZERO;
        var turnover = BigDecimal.ZERO;
        var closedTradeCount = 0;
        var winningTradeCount = 0;
        var equityCurve = new ArrayList<EquityPoint>();
        var cumulative = BigDecimal.ZERO;

        for (var fill : fills) {
            var inPeriod = !fill.capturedAt().isBefore(from) && !fill.capturedAt().isAfter(to);
            if (inPeriod) {
                turnover = turnover.add(fill.amount());
                totalFees = totalFees.add(fill.commission());
                totalTax = totalTax.add(fill.tax());
            }
            lastPriceBySymbol.put(fill.symbol(), fill.avgPrice());

            if (fill.side() == OrderSide.BUY) {
                var feePerUnit = fill.commission().divide(fill.quantity(), 10, RoundingMode.HALF_UP);
                lotsBySymbol.computeIfAbsent(fill.symbol(), key -> new ArrayDeque<>())
                        .addLast(new Lot(fill.quantity(), fill.avgPrice(), feePerUnit));
                continue;
            }

            var lots = lotsBySymbol.getOrDefault(fill.symbol(), new ArrayDeque<>());
            var remainingToMatch = fill.quantity();
            var grossOnMatched = BigDecimal.ZERO;
            while (remainingToMatch.signum() > 0 && !lots.isEmpty()) {
                var lot = lots.peekFirst();
                var matchedQty = remainingToMatch.min(lot.remainingQuantity());
                grossOnMatched = grossOnMatched
                        .add(fill.avgPrice().subtract(lot.unitPrice()).multiply(matchedQty))
                        .subtract(lot.buyFeePerUnit().multiply(matchedQty));
                lot.reduce(matchedQty);
                if (lot.remainingQuantity().signum() == 0) {
                    lots.pollFirst();
                }
                remainingToMatch = remainingToMatch.subtract(matchedQty);
            }
            var matchedQtyTotal = fill.quantity().subtract(remainingToMatch);
            if (matchedQtyTotal.signum() == 0) {
                continue;
            }
            var realizedForThisSell = grossOnMatched.subtract(fill.commission()).subtract(fill.tax());

            if (inPeriod) {
                realizedPnl = realizedPnl.add(realizedForThisSell);
                closedTradeCount++;
                if (realizedForThisSell.signum() > 0) {
                    winningTradeCount++;
                }
                cumulative = cumulative.add(realizedForThisSell);
                equityCurve.add(new EquityPoint(fill.capturedAt(), cumulative));
            }
        }

        var unrealizedPnl = BigDecimal.ZERO;
        for (var entry : lotsBySymbol.entrySet()) {
            var markPrice = lastPriceBySymbol.get(entry.getKey());
            for (var lot : entry.getValue()) {
                unrealizedPnl = unrealizedPnl
                        .add(markPrice.subtract(lot.unitPrice()).multiply(lot.remainingQuantity()))
                        .subtract(lot.buyFeePerUnit().multiply(lot.remainingQuantity()));
            }
        }

        var winRate = closedTradeCount == 0
                ? null
                : BigDecimal.valueOf(winningTradeCount)
                        .divide(BigDecimal.valueOf(closedTradeCount), 4, RoundingMode.HALF_UP);

        var maxDrawdownAmount = maxDrawdown(equityCurve);
        var totalMatched = equityCurve.size();
        var points = downsample(equityCurve, maxPoints);

        return new PaperPerformanceBreakdown(
                realizedPnl, unrealizedPnl, totalFees, totalTax, turnover,
                closedTradeCount, winningTradeCount, winRate, maxDrawdownAmount,
                points.size() < totalMatched, totalMatched, points.size(), points);
    }

    /**
     * Peak-to-trough decline of the period-local cumulative realized P&amp;L curve, reported
     * only as a currency amount — not a percentage. The curve starts at 0 (see class docs),
     * so a percentage-of-peak rate is unbounded (e.g. a curve of [+1, -100] has 100/1 = 10000%
     * "drawdown"), which is not a meaningful number to show a user.
     */
    private static BigDecimal maxDrawdown(List<EquityPoint> equityCurve) {
        var peak = BigDecimal.ZERO;
        var maxDrawdownAmount = BigDecimal.ZERO;
        for (var point : equityCurve) {
            if (point.cumulativeRealizedPnl().compareTo(peak) > 0) {
                peak = point.cumulativeRealizedPnl();
            }
            var currentDrawdown = peak.subtract(point.cumulativeRealizedPnl());
            if (currentDrawdown.compareTo(maxDrawdownAmount) > 0) {
                maxDrawdownAmount = currentDrawdown;
            }
        }
        return maxDrawdownAmount;
    }

    private static List<EquityPoint> downsample(List<EquityPoint> points, int maxPoints) {
        return points.size() <= maxPoints ? points : evenlySpaced(points, maxPoints);
    }

    private static List<EquityPoint> evenlySpaced(List<EquityPoint> points, int maxPoints) {
        var result = new ArrayList<EquityPoint>(maxPoints);
        var lastIndex = points.size() - 1;
        for (var i = 0; i < maxPoints; i++) {
            var index = (int) Math.round(i * (double) lastIndex / (maxPoints - 1));
            result.add(points.get(index));
        }
        return result;
    }

    private static PaperPerformanceView unavailable(String reason) {
        return new PaperPerformanceView(true, reason, null);
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Fill(
            String symbol,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal avgPrice,
            BigDecimal amount,
            BigDecimal commission,
            BigDecimal tax,
            Currency currency,
            Instant capturedAt
    ) {
    }

    private static final class Lot {
        private BigDecimal remainingQuantity;
        private final BigDecimal unitPrice;
        private final BigDecimal buyFeePerUnit;

        private Lot(BigDecimal remainingQuantity, BigDecimal unitPrice, BigDecimal buyFeePerUnit) {
            this.remainingQuantity = remainingQuantity;
            this.unitPrice = unitPrice;
            this.buyFeePerUnit = buyFeePerUnit;
        }

        private BigDecimal remainingQuantity() {
            return remainingQuantity;
        }

        private BigDecimal unitPrice() {
            return unitPrice;
        }

        private BigDecimal buyFeePerUnit() {
            return buyFeePerUnit;
        }

        private void reduce(BigDecimal matchedQty) {
            remainingQuantity = remainingQuantity.subtract(matchedQty);
        }
    }

    public record PaperPerformanceView(
            boolean unavailable,
            String unavailableReason,
            PaperPerformanceData data
    ) {
    }

    public record PaperPerformanceData(
            Instant from,
            Instant to,
            Map<Currency, PaperPerformanceBreakdown> byCurrency
    ) {
    }

    public record PaperPerformanceBreakdown(
            BigDecimal realizedPnl,
            BigDecimal unrealizedPnl,
            BigDecimal totalFees,
            BigDecimal totalTax,
            BigDecimal turnover,
            int closedTradeCount,
            int winningTradeCount,
            BigDecimal winRate,
            BigDecimal maxDrawdown,
            boolean partial,
            int totalMatched,
            int returnedPoints,
            List<EquityPoint> equityCurve
    ) {
    }

    public record EquityPoint(Instant capturedAt, BigDecimal cumulativeRealizedPnl) {
    }
}
