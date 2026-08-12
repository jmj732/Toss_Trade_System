package com.jmj.trade.broker.connection;

import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.MarketDataAdapter;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrokerSurfaceServiceTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    @Test
    void unsupportedSurfaceRequiresOwnedActiveConnection() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), mock(BrokerAdapter.class));

        var response = service.unsupported(USER, CONNECTION);

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.UNAVAILABLE);
        assertThat(response.unavailableReason()).isEqualTo("PROVIDER_UNSUPPORTED");
    }

    @Test
    void pricesExposeProviderFieldsWithoutInventingBidAsk() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class);
        var observedAt = Instant.parse("2026-08-09T00:00:00Z");
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(broker.getQuote(any(BrokerConnectionRef.class), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(new BrokerConnectionRef(CONNECTION), "AAPL", Currency.USD,
                        new BigDecimal("100"), null, null, null, observedAt),
                BrokerOrderPort.localMetadata()));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.prices(USER, CONNECTION, "AAPL");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.DEGRADED);
        assertThat(response.unknownFields()).containsExactly("AAPL.bidPrice", "AAPL.askPrice");
        assertThat(response.data()).singleElement().satisfies(price -> {
            assertThat(price.lastPrice()).isEqualByComparingTo("100");
            assertThat(price.bidPrice()).isNull();
            assertThat(price.askPrice()).isNull();
        });
    }

    @Test
    void pricesRejectProviderQuoteFromAnotherConnectionOrSymbol() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(broker.getQuote(any(BrokerConnectionRef.class), eq("AAPL"))).thenReturn(new BrokerResponse<>(
                new Quote(new BrokerConnectionRef(UUID.randomUUID()), "MSFT", Currency.USD,
                        new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("101"), null, Instant.now()),
                BrokerOrderPort.localMetadata()));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.prices(USER, CONNECTION, "AAPL");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.DEGRADED);
        assertThat(response.unknownFields()).containsExactly("AAPL.quote");
        assertThat(response.data()).isEmpty();
    }

    @Test
    void pricesExposeProviderRateLimitInsteadOfCallingItPartial() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(broker.getQuote(any(BrokerConnectionRef.class), eq("AAPL")))
                .thenThrow(new BrokerException(BrokerErrorCategory.RATE_LIMITED, 429, null, null,
                        java.time.Duration.ofSeconds(2), true, "rate limited"));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.prices(USER, CONNECTION, "AAPL");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.UNAVAILABLE);
        assertThat(response.unavailableReason()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(response.provenance()).singleElement()
                .extracting(BrokerSurfaceResponse.ProviderProvenance::endpoint)
                .isEqualTo("/api/v1/prices");
    }

    @Test
    void pricesExposeMalformedProviderResponseInsteadOfCallingItPartial() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(broker.getQuote(any(BrokerConnectionRef.class), eq("AAPL")))
                .thenThrow(new IllegalStateException("malformed response"));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.prices(USER, CONNECTION, "AAPL");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.UNAVAILABLE);
        assertThat(response.unavailableReason()).isEqualTo("PROVIDER_MALFORMED");
    }

    @Test
    void candlesKeepProviderPartialFieldsAndMarkSurfaceDegraded() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(MarketDataAdapter.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(BrokerAdapter.class));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        var timestamp = Instant.parse("2026-08-09T00:00:00Z");
        when(broker.getCandles(any(BrokerConnectionRef.class), eq("AAPL"), eq("1d"), eq(2), eq(null), eq(true)))
                .thenReturn(new BrokerResponse<>(new MarketDataAdapter.CandleSeries(
                        "AAPL", "1d", true,
                        List.of(new MarketDataAdapter.Candle(timestamp, new BigDecimal("200"), null,
                                new BigDecimal("198"), new BigDecimal("199"), null, Currency.USD)), null),
                        BrokerOrderPort.localMetadata()));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), (BrokerAdapter) broker);

        var response = service.candles(USER, CONNECTION, "AAPL", "1d", 2, null, true);

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.DEGRADED);
        assertThat(response.unknownFields()).containsExactly("candles[0].highPrice", "candles[0].volume");
        assertThat(response.data().candles()).singleElement().satisfies(candle -> {
            assertThat(candle.highPrice()).isNull();
            assertThat(candle.volume()).isNull();
        });
    }

    @Test
    void expiredProviderValidityKeepsFxValueAndMarksItStale() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(MarketDataAdapter.class));
        var marketData = (MarketDataAdapter) broker;
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(marketData.getExchangeRate(any(BrokerConnectionRef.class), eq(Currency.USD), eq(Currency.KRW)))
                .thenReturn(new BrokerResponse<>(new MarketDataAdapter.ExchangeRate(
                        Currency.USD, Currency.KRW, new BigDecimal("1380"), new BigDecimal("1379"),
                        new BigDecimal("1"), "UP", Instant.parse("2026-08-08T00:00:00Z"),
                        Instant.parse("2026-08-08T00:01:00Z")), BrokerOrderPort.localMetadata()));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.exchangeRate(USER, CONNECTION, "USD", "KRW");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.DEGRADED);
        assertThat(response.stale()).isTrue();
        assertThat(response.unknownFields()).containsExactly("validUntil");
        assertThat(response.data().rate()).isEqualByComparingTo("1380");
        assertThat(response.provenance()).singleElement().satisfies(item -> {
            assertThat(item.provider()).isEqualTo("TOSS");
            assertThat(item.endpoint()).isEqualTo("/api/v1/exchange-rate");
            assertThat(item.currency()).isEqualTo("USD/KRW");
            assertThat(item.asOf()).isEqualTo(Instant.parse("2026-08-08T00:00:00Z"));
        });
    }

    @Test
    void providerFailureKeepsFailureScopeAndAttemptProvenance() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(MarketDataAdapter.class));
        var marketData = (MarketDataAdapter) broker;
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(marketData.getOrderBook(any(BrokerConnectionRef.class), eq("AAPL")))
                .thenThrow(new BrokerException(BrokerErrorCategory.RATE_LIMITED, 429, null, null,
                        java.time.Duration.ofSeconds(2), true, "rate limited"));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.orderBook(USER, CONNECTION, "AAPL");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.UNAVAILABLE);
        assertThat(response.unavailableReason()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(response.provenance()).singleElement().satisfies(item -> {
            assertThat(item.provider()).isEqualTo("TOSS");
            assertThat(item.endpoint()).isEqualTo("/api/v1/orderbook");
            assertThat(item.asOf()).isNull();
            assertThat(item.currency()).isNull();
            assertThat(item.observedAt()).isNotNull();
        });
    }

    @Test
    void rankingsKeepIncompleteIdentityFieldsAndMarkOnlyThoseFieldsUnknown() {
        var jdbc = mock(JdbcTemplate.class);
        var broker = mock(BrokerAdapter.class, org.mockito.Mockito.withSettings()
                .extraInterfaces(MarketDataAdapter.class));
        var marketData = (MarketDataAdapter) broker;
        when(jdbc.queryForList(anyString(), eq(String.class), eq(CONNECTION), eq(USER)))
                .thenReturn(List.of("ACTIVE"));
        when(marketData.getRanking(any(BrokerConnectionRef.class), eq("MARKET_TRADING_VOLUME"), eq("US"), eq("realtime"), eq(2)))
                .thenReturn(new BrokerResponse<>(new MarketDataAdapter.Ranking(
                        "MARKET_TRADING_VOLUME", "US", "realtime", Instant.parse("2026-08-09T00:00:00Z"),
                        List.of(new MarketDataAdapter.RankingItem(null, null, Currency.USD,
                                new BigDecimal("210"), null, null, null, null, null))),
                        BrokerOrderPort.localMetadata()));
        var service = new BrokerSurfaceService(jdbc, mock(FreshPortfolioReadService.class), broker);

        var response = service.ranking(USER, CONNECTION, "VOLUME", "US", "realtime", 2);

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.DEGRADED);
        assertThat(response.unknownFields()).containsExactly(
                "items[0].rank", "items[0].symbol", "items[0].basePrice", "items[0].changeRate",
                "items[0].tradingVolume", "items[0].tradingAmount");
        assertThat(response.data().items()).singleElement().satisfies(item -> {
            assertThat(item.rank()).isNull();
            assertThat(item.symbol()).isNull();
            assertThat(item.lastPrice()).isEqualByComparingTo("210");
        });
    }
}
