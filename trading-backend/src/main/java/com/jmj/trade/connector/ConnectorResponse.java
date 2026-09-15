package com.jmj.trade.connector;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConnectorResponse {

    private ConnectorResponse() {
    }

    public record Portfolio(
            Instant completedAt,
            boolean stale,
            String staleReason,
            boolean partial,
            List<String> missingSections,
            List<String> unknownFields,
            Account account,
            List<Position> positions,
            Map<String, BuyingPower> buyingPower
    ) {
        Portfolio(Instant completedAt, boolean stale, boolean partial,
                  List<String> missingSections, List<String> unknownFields,
                  Map<String, BigDecimal> buyingPower, List<Position> positions) {
            this(completedAt, stale, null, partial, missingSections, unknownFields,
                    null, positions, buyingPower.entrySet().stream().collect(
                            java.util.stream.Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey,
                                    entry -> new BuyingPower(entry.getValue(), null))));
        }
    }

    public record Account(
            String accountType,
            String displayAccountNumber,
            Map<String, BigDecimal> totalPurchaseAmounts,
            Map<String, BigDecimal> marketValueAmounts,
            Map<String, BigDecimal> marketValueAfterCostAmounts,
            Map<String, BigDecimal> profitLossAmounts,
            Map<String, BigDecimal> profitLossAfterCostAmounts,
            Map<String, BigDecimal> dailyProfitLossAmounts,
            BigDecimal profitLossRate,
            BigDecimal profitLossRateAfterCost,
            BigDecimal dailyProfitLossRate,
            Instant observedAt
    ) {
    }

    public record Position(
            String symbol,
            String name,
            String marketCountry,
            BigDecimal quantity,
            String currency,
            BigDecimal averagePrice,
            BigDecimal lastPrice,
            BigDecimal purchaseAmount,
            BigDecimal marketValueAmount,
            BigDecimal marketValueAfterCost,
            BigDecimal profitLossAmount,
            BigDecimal profitLossAfterCost,
            BigDecimal profitLossRate,
            BigDecimal profitLossRateAfterCost,
            BigDecimal dailyProfitLossAmount,
            BigDecimal dailyProfitLossRate,
            BigDecimal commission,
            BigDecimal tax,
            BigDecimal sellableQuantity,
            Instant observedAt
    ) {
    }

    public record BuyingPower(BigDecimal cashBuyingPower, Instant observedAt) {
    }

    public record PortfolioState(
            Instant asOf,
            String currency,
            StateAccount account,
            List<StatePosition> positions,
            List<Order> openOrders,
            Risk risk,
            boolean stale,
            String staleReason,
            boolean partial,
            List<String> missingSections,
            List<String> unknownFields
    ) {
    }

    public record StateAccount(
            BigDecimal totalValue,
            BigDecimal cash,
            BigDecimal cashPct
    ) {
    }

    public record StatePosition(
            String symbol,
            BigDecimal quantity,
            BigDecimal avgPrice,
            BigDecimal currentPrice,
            BigDecimal marketValue,
            BigDecimal weightPct,
            BigDecimal unrealizedPnlPct,
            String currency
    ) {
    }

    public record Risk(
            BigDecimal largestPositionPct,
            BigDecimal investedPct,
            BigDecimal cashPct
    ) {
    }

    public record Order(
            String brokerOrderId,
            BrokerOrderSide side,
            BrokerOrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal filledQuantity,
            BigDecimal limitPrice,
            String currency,
            BrokerOrderLifecycle status,
            BrokerOrderGroup group,
            Instant filledAt,
            BigDecimal averageFilledPrice,
            BigDecimal commission,
            BigDecimal tax
    ) {
    }

    public record Fill(
            String brokerOrderId,
            String symbol,
            BrokerOrderSide side,
            String currency,
            BigDecimal quantity,
            BigDecimal averagePrice,
            BigDecimal commission,
            BigDecimal tax,
            Instant filledAt,
            Instant observedAt
    ) {
    }

    public enum BrokerOrderSide { BUY, SELL }

    public enum BrokerOrderType { MARKET, LIMIT }

    public enum BrokerOrderLifecycle {
        PENDING, PARTIALLY_FILLED, CANCELING, REPLACING, FILLED, CANCELED,
        REJECTED, CANCEL_REJECTED, REPLACE_REJECTED, REPLACED
    }

    public enum BrokerOrderGroup { OPEN, CLOSED }
}
