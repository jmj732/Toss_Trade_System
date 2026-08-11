package com.jmj.trade.broker.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
final class TossApiDtos {

    private TossApiDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OAuthTokenResponse(String access_token, String token_type, Long expires_in) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OAuthErrorResponse(String error, String error_description) {
    }

    record OAuthToken(String accessToken, java.time.Duration expiresIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegularErrorEnvelope(RegularError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegularError(String requestId, String code, String message, Object data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountsEnvelope(List<Account> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Account(String accountNo, Long accountSeq, String accountType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingsEnvelope(Holdings result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Holdings(
            PriceAmount totalPurchaseAmount,
            HoldingsMarketValue marketValue,
            HoldingsProfitLoss profitLoss,
            HoldingsDailyProfitLoss dailyProfitLoss,
            List<HoldingItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingItem(
            String symbol,
            String name,
            String marketCountry,
            String currency,
            String quantity,
            String lastPrice,
            String averagePurchasePrice,
            HoldingMarketValue marketValue,
            HoldingProfitLoss profitLoss,
            HoldingDailyProfitLoss dailyProfitLoss,
            Cost cost) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cost(String commission, String tax) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricesEnvelope(List<Price> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Price(String symbol, String timestamp, String lastPrice, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderBookEnvelope(OrderBook result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderBook(String timestamp, String currency, List<Level> asks, List<Level> bids) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Level(String price, String volume) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CandleSeriesEnvelope(CandleSeries result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CandleSeries(List<Candle> candles, String nextBefore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candle(
            String timestamp,
            String openPrice,
            String highPrice,
            String lowPrice,
            String closePrice,
            String volume,
            String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExchangeRateEnvelope(ExchangeRate result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExchangeRate(
            String baseCurrency,
            String quoteCurrency,
            String rate,
            String midRate,
            String basisPoint,
            String rateChangeType,
            String validFrom,
            String validUntil) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarketCalendarEnvelope(JsonNode result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RankingsEnvelope(Rankings result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Rankings(
            String rankedAt,
            List<RankingItem> rankings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RankingItem(
            String rank,
            String symbol,
            String currency,
            RankingPrice price,
            String tradingVolume,
            String tradingAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RankingPrice(
            String lastPrice,
            String basePrice,
            String changeRate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BuyingPowerEnvelope(BuyingPower result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BuyingPower(String currency, String cashBuyingPower) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SellableQuantityEnvelope(SellableQuantity result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SellableQuantity(String sellableQuantity) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OrderCreateRequest(
            String clientOrderId,
            String symbol,
            String side,
            String orderType,
            String timeInForce,
            String quantity,
            String price,
            Boolean confirmHighValueOrder) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OrderModifyRequest(
            String orderType,
            String quantity,
            String price,
            Boolean confirmHighValueOrder) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderResponse(String orderId, String clientOrderId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderOperationResponse(String orderId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderResponseEnvelope(OrderResponse result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderOperationResponseEnvelope(OrderOperationResponse result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaginatedOrderResponseEnvelope(PaginatedOrderResponse result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderEnvelope(Order result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaginatedOrderResponse(List<Order> orders, String nextCursor, Boolean hasNext) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Order(
            String orderId,
            String symbol,
            String side,
            String orderType,
            String timeInForce,
            String status,
            String quantity,
            String price,
            String orderAmount,
            String currency,
            String orderedAt,
            String canceledAt,
            OrderExecution execution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderExecution(
            String filledQuantity,
            String averageFilledPrice,
            String filledAmount,
            String commission,
            String tax,
            String filledAt,
            String settlementDate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceAmount(String krw, String usd) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingsMarketValue(PriceAmount amount, PriceAmount amountAfterCost) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingsProfitLoss(PriceAmount amount, PriceAmount amountAfterCost, String rate, String rateAfterCost) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingsDailyProfitLoss(PriceAmount amount, String rate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingMarketValue(String purchaseAmount, String amount, String amountAfterCost) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingProfitLoss(String amount, String amountAfterCost, String rate, String rateAfterCost) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingDailyProfitLoss(String amount, String rate) {
    }
}
