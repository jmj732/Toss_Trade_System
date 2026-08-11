package com.jmj.trade.broker;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only market data contract. Values are provider values; no synthesis. */
public interface MarketDataAdapter {

    BrokerResponse<OrderBook> getOrderBook(BrokerConnectionRef connection, String symbol);

    BrokerResponse<CandleSeries> getCandles(
            BrokerConnectionRef connection,
            String symbol,
            String interval,
            int count,
            String before,
            boolean adjusted);

    BrokerResponse<ExchangeRate> getExchangeRate(
            BrokerConnectionRef connection,
            Currency baseCurrency,
            Currency quoteCurrency);

    BrokerResponse<MarketCalendar> getMarketCalendar(
            BrokerConnectionRef connection,
            String market,
            LocalDate date);

    BrokerResponse<Ranking> getRanking(
            BrokerConnectionRef connection,
            String type,
            String marketCountry,
            String duration,
            int count);

    record OrderBook(
            String symbol,
            Instant timestamp,
            Currency currency,
            List<Level> asks,
            List<Level> bids) {
        public OrderBook {
            asks = List.copyOf(asks == null ? List.of() : asks);
            bids = List.copyOf(bids == null ? List.of() : bids);
        }
    }

    record Level(BigDecimal price, BigDecimal volume) {
    }

    record CandleSeries(
            String symbol,
            String interval,
            boolean adjusted,
            List<Candle> candles,
            Instant nextBefore) {
        public CandleSeries {
            candles = List.copyOf(candles == null ? List.of() : candles);
        }
    }

    record Candle(
            Instant timestamp,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal volume,
            Currency currency) {
    }

    record ExchangeRate(
            Currency baseCurrency,
            Currency quoteCurrency,
            BigDecimal rate,
            BigDecimal midRate,
            BigDecimal basisPoint,
            String rateChangeType,
            Instant validFrom,
            Instant validUntil) {
    }

    /** Calendar payload remains typed at the endpoint boundary and preserves provider session fields. */
    record MarketCalendar(String market, JsonNode payload) {
    }

    record Ranking(
            String type,
            String marketCountry,
            String duration,
            Instant rankedAt,
            List<RankingItem> items) {
        public Ranking {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    record RankingItem(
            int rank,
            String symbol,
            Currency currency,
            BigDecimal lastPrice,
            BigDecimal basePrice,
            BigDecimal changeRate,
            BigDecimal tradingVolume,
            BigDecimal tradingAmount,
            BigDecimal marketCap) {
    }
}
