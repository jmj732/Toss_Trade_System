package com.jmj.trade.broker.toss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.CashBalanceStatus;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.SellableQuantitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossInvestBrokerAdapterContractTest {

    private static final UUID CONNECTION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final BrokerConnectionRef CONNECTION = new BrokerConnectionRef(CONNECTION_ID);
    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(CONNECTION_ID, "9876543210", "UNKNOWN_RAW", "******3210");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private WireMockServer server;
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        redisConnectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnectionFactory);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        stubToken();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @Test
    void mapsAccountsWithMaskedNumberAndPreservesUnknownType() {
        server.stubFor(get("/api/v1/accounts").willReturn(json("""
                {"result":[{"accountNo":"9876543210","accountSeq":9876543210,"accountType":"UNKNOWN_RAW"}]}
                """)));

        var response = adapter().getAccounts(CONNECTION);

        assertThat(response.value()).singleElement().satisfies(account -> {
            assertThat(account.account().brokerConnectionId()).isEqualTo(CONNECTION_ID);
            assertThat(account.account().brokerAccountId()).isEqualTo("9876543210");
            assertThat(account.account().accountType()).isEqualTo("UNKNOWN_RAW");
            assertThat(account.account().displayAccountNumber()).isEqualTo("******3210");
            assertThat(account.displayName()).isEqualTo("Toss UNKNOWN_RAW ******3210");
        });
        assertThat(response.metadata().requestId()).isEqualTo("req-success");
        assertThat(response.metadata().rateLimit()).hasValueSatisfying(rateLimit ->
                assertThat(rateLimit.remaining()).contains(77));
    }

    @Test
    void mapsHoldingsSummaryWithoutCashOrFxInference() {
        stubHoldings();

        var response = adapter().getAccount(ACCOUNT);

        assertThat(response.value().cashBalanceStatus()).isEqualTo(CashBalanceStatus.UNKNOWN);
        assertThat(response.value().totalPurchaseAmount().amounts())
                .containsEntry(Currency.KRW, new BigDecimal("1000"))
                .containsEntry(Currency.USD, new BigDecimal("10.25"));
        assertThat(response.value().marketValueAmount().amounts())
                .containsEntry(Currency.KRW, new BigDecimal("1300"))
                .containsEntry(Currency.USD, new BigDecimal("13.25"));
        assertThat(response.value().profitLossAmount().amounts())
                .containsEntry(Currency.KRW, new BigDecimal("-120"))
                .containsEntry(Currency.USD, new BigDecimal("-1.20"));
    }

    @Test
    void mapsOnlyUsPositionsWithNullableTaxAndRequiredCommission() {
        stubHoldings();

        var response = adapter().getPositions(ACCOUNT);

        assertThat(response.value()).singleElement().satisfies(position -> {
            assertThat(position.symbol()).isEqualTo("AAPL");
            assertThat(position.marketCountry()).isEqualTo("US");
            assertThat(position.currency()).isEqualTo(Currency.USD);
            assertThat(position.quantity()).isEqualByComparingTo("2");
            assertThat(position.averagePrice()).isEqualByComparingTo("180.50");
            assertThat(position.lastPrice()).isEqualByComparingTo("190.00");
            assertThat(position.purchaseAmount()).isEqualByComparingTo("361.00");
            assertThat(position.marketValueAmount()).isEqualByComparingTo("380.00");
            assertThat(position.commission()).isEqualByComparingTo("0.75");
            assertThat(position.tax()).isNull();
        });
    }

    @Test
    void normalizesQuoteSymbolAndRequiresExactSingleResult() {
        server.stubFor(get(urlEqualTo("/api/v1/prices?symbols=AAPL")).willReturn(json("""
                {"result":[{"symbol":"AAPL","timestamp":null,"lastPrice":"190.12","currency":"USD"}]}
                """)));

        var response = adapter().getQuote(CONNECTION, "aapl");

        assertThat(response.value().symbol()).isEqualTo("AAPL");
        assertThat(response.value().lastPrice()).isEqualByComparingTo("190.12");
        assertThat(response.value().brokerTimestamp()).isNull();
        assertThat(response.value().bidPrice()).isNull();
        assertThat(response.value().askPrice()).isNull();
    }

    @Test
    void mapsOrderbookLevelsWithoutDroppingProviderCurrencyOrTimestamp() {
        server.stubFor(get(urlEqualTo("/api/v1/orderbook?symbol=AAPL")).willReturn(json("""
                {"result":{"timestamp":"2026-08-09T00:00:00.123+09:00","currency":"USD",
                "asks":[{"price":"210.10","volume":"120"}],"bids":[{"price":"209.90","volume":"140"}]}}
                """)));

        var response = adapter().getOrderBook(CONNECTION, "aapl");

        assertThat(response.value()).satisfies(orderBook -> {
            assertThat(orderBook.symbol()).isEqualTo("AAPL");
            assertThat(orderBook.timestamp()).isEqualTo(Instant.parse("2026-08-08T15:00:00.123Z"));
            assertThat(orderBook.currency()).isEqualTo(Currency.USD);
            assertThat(orderBook.asks()).singleElement().satisfies(level -> {
                assertThat(level.price()).isEqualByComparingTo("210.10");
                assertThat(level.volume()).isEqualByComparingTo("120");
            });
        });
    }

    @Test
    void preservesPartialOrderbookLevelsForDegradeHandling() {
        server.stubFor(get(urlEqualTo("/api/v1/orderbook?symbol=AAPL")).willReturn(json("""
                {"result":{"timestamp":"2026-08-09T00:00:00+09:00","currency":"USD",
                "asks":[{"price":"210.10"}],"bids":[]}}
                """)));

        var response = adapter().getOrderBook(CONNECTION, "AAPL");

        assertThat(response.value().asks()).singleElement().satisfies(level -> {
            assertThat(level.price()).isEqualByComparingTo("210.10");
            assertThat(level.volume()).isNull();
        });
        assertThat(response.value().bids()).isEmpty();
    }

    @Test
    void mapsCandlesWithOfficialIntervalAndAdjustedFlag() {
        server.stubFor(get(urlEqualTo("/api/v1/candles?symbol=AAPL&interval=1d&count=2&adjusted=true"))
                .willReturn(json("""
                        {"result":{"candles":[{"timestamp":"2026-08-08T00:00:00+09:00","openPrice":"205","highPrice":"212","lowPrice":"204","closePrice":"210","volume":"1200000","currency":"USD"}],"nextBefore":"2026-08-07T00:00:00+09:00"}}
                        """)));

        var response = adapter().getCandles(CONNECTION, "AAPL", "1d", 2, null, true);

        assertThat(response.value().interval()).isEqualTo("1d");
        assertThat(response.value().adjusted()).isTrue();
        assertThat(response.value().candles()).singleElement().satisfies(candle -> {
            assertThat(candle.closePrice()).isEqualByComparingTo("210");
            assertThat(candle.volume()).isEqualByComparingTo("1200000");
            assertThat(candle.currency()).isEqualTo(Currency.USD);
        });
        assertThat(response.value().nextBefore()).isEqualTo(Instant.parse("2026-08-06T15:00:00Z"));
    }

    @Test
    void mapsExchangeRateValidityWindowWithoutConvertingCurrencies() {
        server.stubFor(get(urlEqualTo("/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW"))
                .willReturn(json("""
                        {"result":{"baseCurrency":"USD","quoteCurrency":"KRW","rate":"1380.25","midRate":"1380.10","basisPoint":"1.5","rateChangeType":"UP","validFrom":"2026-08-09T00:00:00+09:00","validUntil":"2026-08-09T00:01:00+09:00"}}
                        """)));

        var response = adapter().getExchangeRate(CONNECTION, Currency.USD, Currency.KRW);

        assertThat(response.value().rate()).isEqualByComparingTo("1380.25");
        assertThat(response.value().midRate()).isEqualByComparingTo("1380.10");
        assertThat(response.value().baseCurrency()).isEqualTo(Currency.USD);
        assertThat(response.value().quoteCurrency()).isEqualTo(Currency.KRW);
        assertThat(response.value().validUntil()).isEqualTo(Instant.parse("2026-08-08T15:01:00Z"));
    }

    @Test
    void mapsCalendarPayloadAndRankingValuesAsProviderData() {
        server.stubFor(get(urlEqualTo("/api/v1/market-calendar/US?date=2026-08-09"))
                .willReturn(json("""
                        {"result":{"today":{"date":"2026-08-09","regularMarket":null},"previousBusinessDay":{"date":"2026-08-08"},"nextBusinessDay":{"date":"2026-08-10"}}}
                        """)));
        server.stubFor(get(urlEqualTo("/api/v1/rankings?type=MARKET_TRADING_VOLUME&marketCountry=US&duration=realtime&count=2"))
                .willReturn(json("""
                        {"result":{"rankedAt":"2026-08-09T00:00:00+09:00","rankings":[{"rank":"1","symbol":"AAPL","currency":"USD","price":{"lastPrice":"210","basePrice":"208","changeRate":"0.0096"},"tradingVolume":"1200000","tradingAmount":"252000000"}]}}
                        """)));

        var calendar = adapter().getMarketCalendar(CONNECTION, "US", LocalDate.parse("2026-08-09"));
        var ranking = adapter().getRanking(CONNECTION, "MARKET_TRADING_VOLUME", "US", "realtime", 2);

        assertThat(calendar.value().payload().path("today").path("date").asText()).isEqualTo("2026-08-09");
        assertThat(ranking.value().items()).singleElement().satisfies(item -> {
            assertThat(item.rank()).isEqualTo(1);
            assertThat(item.tradingVolume()).isEqualByComparingTo("1200000");
            assertThat(item.lastPrice()).isEqualByComparingTo("210");
            assertThat(item.marketCap()).isNull();
        });
    }

    @Test
    void preservesMissingRankingIdentityFieldsForSurfaceDegradation() {
        server.stubFor(get(urlEqualTo("/api/v1/rankings?type=MARKET_TRADING_VOLUME&marketCountry=US&duration=realtime&count=2"))
                .willReturn(json("""
                        {"result":{"rankedAt":"2026-08-09T00:00:00+09:00","rankings":[{"currency":"USD","price":{"lastPrice":"210","basePrice":""}}]}}
                        """)));

        var response = adapter().getRanking(CONNECTION, "MARKET_TRADING_VOLUME", "US", "realtime", 2);

        assertThat(response.value().items()).singleElement().satisfies(item -> {
            assertThat(item.rank()).isNull();
            assertThat(item.symbol()).isNull();
            assertThat(item.lastPrice()).isEqualByComparingTo("210");
            assertThat(item.basePrice()).isNull();
        });
    }

    @Test
    void normalizesRateLimitAsRetriableProviderError() {
        server.stubFor(get(urlEqualTo("/api/v1/orderbook?symbol=AAPL"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"rate-limit-exceeded\"}}")));

        assertThatThrownBy(() -> adapter().getOrderBook(CONNECTION, "AAPL"))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.RATE_LIMITED);
                    assertThat(exception.isRetriable()).isTrue();
                    assertThat(exception.retryAfter()).hasValue(Duration.ofSeconds(2));
                });
    }

    @Test
    void keepsBuyingPowerSeparateFromAccountCash() {
        server.stubFor(get(urlEqualTo("/api/v1/buying-power?currency=USD")).willReturn(json("""
                {"result":{"currency":"USD","cashBuyingPower":"250.25"}}
                """)));

        var response = adapter().getAccountCapacity(ACCOUNT, Currency.USD);

        assertThat(response.value().cashBuyingPower()).isEqualByComparingTo("250.25");
        assertThat(response.value().currency()).isEqualTo(Currency.USD);
    }

    @Test
    void mapsFractionalUsSellableQuantityFromOfficialEndpoint() {
        server.stubFor(get(urlEqualTo("/api/v1/sellable-quantity?symbol=AAPL"))
                .withHeader("Authorization", equalTo("Bearer access-token"))
                .withHeader("X-Tossinvest-Account", equalTo("9876543210"))
                .willReturn(json("""
                        {"result":{"sellableQuantity":"1.5"}}
                        """)));

        var response = adapter().getSellableQuantity(ACCOUNT, "aapl");

        assertThat(response.value().availability()).isEqualTo(SellableQuantitySnapshot.Availability.KNOWN);
        assertThat(response.value().symbol()).isEqualTo("AAPL");
        assertThat(response.value().quantity()).isEqualByComparingTo("1.5");
        assertThat(response.metadata().requestId()).isEqualTo("req-success");
    }

    @Test
    void sellableQuantityReturnsUnknownWhenRouteIsUnsupported() {
        server.stubFor(get(urlEqualTo("/api/v1/sellable-quantity?symbol=AAPL"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"not-found\"}}")));

        var response = adapter().getSellableQuantity(ACCOUNT, "aapl");

        assertThat(response.value().availability()).isEqualTo(SellableQuantitySnapshot.Availability.UNKNOWN);
        assertThat(response.value().quantity()).isNull();
        assertThat(response.value().symbol()).isEqualTo("AAPL");
    }

    @Test
    void sellableQuantityReturnsUnknownWhenReadTimesOut() {
        server.stubFor(get(urlEqualTo("/api/v1/sellable-quantity?symbol=AAPL"))
                .willReturn(aResponse().withFixedDelay(1_500).withHeader("Content-Type", "application/json")
                        .withBody("{\"result\":{\"sellableQuantity\":\"1\"}}")));

        var response = adapter().getSellableQuantity(ACCOUNT, "aapl");

        assertThat(response.value().availability()).isEqualTo(SellableQuantitySnapshot.Availability.UNKNOWN);
        assertThat(response.value().quantity()).isNull();
    }

    @Test
    void sellableQuantityReturnsUnknownWhenPayloadIsMalformed() {
        server.stubFor(get(urlEqualTo("/api/v1/sellable-quantity?symbol=AAPL"))
                .willReturn(json("""
                        {"result":{"sellableQuantity":"not-a-number"}}
                        """)));

        var response = adapter().getSellableQuantity(ACCOUNT, "aapl");

        assertThat(response.value().availability()).isEqualTo(SellableQuantitySnapshot.Availability.UNKNOWN);
        assertThat(response.value().quantity()).isNull();
    }

    @Test
    void rejectsInvalidRequiredFieldsAsContractErrors() {
        server.stubFor(get("/api/v1/holdings").willReturn(json("""
                {"result":{"totalPurchaseAmount":{"krw":"1"},"marketValue":{"amount":{"krw":"1"},"amountAfterCost":{"krw":"1"}},"profitLoss":{"amount":{"krw":"0"},"amountAfterCost":{"krw":"0"},"rate":"0","rateAfterCost":"0"},"dailyProfitLoss":{"amount":{"krw":"0"},"rate":"0"},"items":[{"symbol":"AAPL","name":"Apple","marketCountry":"US","currency":"USD","quantity":"1","lastPrice":"bad","averagePurchasePrice":"1","marketValue":{"purchaseAmount":"1","amount":"1","amountAfterCost":"1"},"profitLoss":{"amount":"0","amountAfterCost":"0","rate":"0","rateAfterCost":"0"},"dailyProfitLoss":{"amount":"0","rate":"0"},"cost":{"commission":"0","tax":null}}]}}
                """)));

        assertThatThrownBy(() -> adapter().getPositions(ACCOUNT))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.isRetriable()).isFalse();
                });
    }

    @Test
    void accountSnapshotRejectsMissingRequiredItemsEvenThoughItDoesNotMapThem() {
        server.stubFor(get("/api/v1/holdings").willReturn(json("""
                {"result":{"totalPurchaseAmount":{"krw":"1"},"marketValue":{"amount":{"krw":"1"},"amountAfterCost":{"krw":"1"}},"profitLoss":{"amount":{"krw":"0"},"amountAfterCost":{"krw":"0"},"rate":"0","rateAfterCost":"0"},"dailyProfitLoss":{"amount":{"krw":"0"},"rate":"0"}}}
                """)));

        assertThatThrownBy(() -> adapter().getAccount(ACCOUNT))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.isRetriable()).isFalse();
                });
    }

    @Test
    void accountSnapshotRejectsMalformedItemsEvenThoughItDoesNotMapThem() {
        assertInvalidAccountItem("""
                {"result":{"totalPurchaseAmount":{"krw":"1"},"marketValue":{"amount":{"krw":"1"},"amountAfterCost":{"krw":"1"}},"profitLoss":{"amount":{"krw":"0"},"amountAfterCost":{"krw":"0"},"rate":"0","rateAfterCost":"0"},"dailyProfitLoss":{"amount":{"krw":"0"},"rate":"0"},"items":[null]}}
                """);
        assertInvalidAccountItem("""
                {"result":{"totalPurchaseAmount":{"krw":"1"},"marketValue":{"amount":{"krw":"1"},"amountAfterCost":{"krw":"1"}},"profitLoss":{"amount":{"krw":"0"},"amountAfterCost":{"krw":"0"},"rate":"0","rateAfterCost":"0"},"dailyProfitLoss":{"amount":{"krw":"0"},"rate":"0"},"items":[{"symbol":"005930","name":"Samsung Electronics","marketCountry":"KR","currency":"KRW","quantity":"1","lastPrice":"80000","averagePurchasePrice":"70000","marketValue":{"purchaseAmount":"70000","amount":"80000","amountAfterCost":"79900"},"profitLoss":{"amount":"10000","amountAfterCost":"9900","rate":"bad","rateAfterCost":"0.1414"},"dailyProfitLoss":{"amount":"500","rate":"0.006"},"cost":{"commission":"100","tax":"0"}}]}}
                """);
    }

    @Test
    void positionsRejectMalformedItemsBeforeFilteringByCountry() {
        assertInvalidPositionsItem("""
                {"result":{"totalPurchaseAmount":{"krw":"1"},"marketValue":{"amount":{"krw":"1"},"amountAfterCost":{"krw":"1"}},"profitLoss":{"amount":{"krw":"0"},"amountAfterCost":{"krw":"0"},"rate":"0","rateAfterCost":"0"},"dailyProfitLoss":{"amount":{"krw":"0"},"rate":"0"},"items":[null]}}
                """);
        assertInvalidPositionsItem("""
                {"result":{"totalPurchaseAmount":{"krw":"1"},"marketValue":{"amount":{"krw":"1"},"amountAfterCost":{"krw":"1"}},"profitLoss":{"amount":{"krw":"0"},"amountAfterCost":{"krw":"0"},"rate":"0","rateAfterCost":"0"},"dailyProfitLoss":{"amount":{"krw":"0"},"rate":"0"},"items":[{"symbol":"005930","name":"Samsung Electronics","currency":"KRW","quantity":"1","lastPrice":"80000","averagePurchasePrice":"70000","marketValue":{"purchaseAmount":"70000","amount":"80000","amountAfterCost":"79900"},"profitLoss":{"amount":"10000","amountAfterCost":"9900","rate":"0.1428","rateAfterCost":"0.1414"},"dailyProfitLoss":{"amount":"500","rate":"0.006"},"cost":{"commission":"100","tax":"0"}}]}}
                """);
        assertInvalidPositionsItem("""
                {"result":{"totalPurchaseAmount":{"krw":"1"},"marketValue":{"amount":{"krw":"1"},"amountAfterCost":{"krw":"1"}},"profitLoss":{"amount":{"krw":"0"},"amountAfterCost":{"krw":"0"},"rate":"0","rateAfterCost":"0"},"dailyProfitLoss":{"amount":{"krw":"0"},"rate":"0"},"items":[{"symbol":"005930","name":"Samsung Electronics","marketCountry":"KR","currency":"KRW","quantity":"1","lastPrice":"80000","averagePurchasePrice":"70000","marketValue":{"purchaseAmount":"70000","amount":"80000","amountAfterCost":"79900"},"profitLoss":{"amount":"10000","amountAfterCost":"9900","rate":"bad","rateAfterCost":"0.1414"},"dailyProfitLoss":{"amount":"500","rate":"0.006"},"cost":{"commission":"100","tax":"0"}}]}}
                """);
    }

    @Test
    void rejectsUnsafeAccountNumberWithoutOrderCalls() {
        server.stubFor(get("/api/v1/accounts").willReturn(json("""
                {"result":[{"accountNo":"123","accountSeq":1,"accountType":"GENERAL"}]}
                """)));

        assertThatThrownBy(() -> adapter().getQuote(CONNECTION, " "))
                .isInstanceOfSatisfying(BrokerException.class, exception ->
                        assertThat(exception.category()).isEqualTo(BrokerErrorCategory.INVALID_REQUEST));
        assertThatThrownBy(() -> adapter().getAccounts(CONNECTION))
                .isInstanceOfSatisfying(BrokerException.class, exception ->
                        assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT));
        server.verify(0, anyRequestedFor(urlMatching(".*/orders.*")));
    }

    @Test
    void rejectsInvalidTossAccountSeqBeforeHttpCall() {
        assertInvalidAccountSeq("abc");
        assertInvalidAccountSeq("+1");
        assertInvalidAccountSeq("-1");
        assertInvalidAccountSeq(" 1");
        assertInvalidAccountSeq("9223372036854775808");
        server.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    private TossInvestBrokerAdapter adapter() {
        var properties = new TossApiProperties(
                java.net.URI.create(server.baseUrl()),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ZERO);
        var tokenManager = new TossTokenManager(
                redis,
                provider(),
                new TossOAuthClient(properties),
                properties);
        return new TossInvestBrokerAdapter(new TossApiClient(properties, tokenManager), new TossResponseMapper());
    }

    private TossCredentialProvider provider() {
        return new TossCredentialProvider() {
            @Override
            public TossCredentialMetadata current(UUID brokerConnectionId) {
                return new TossCredentialMetadata(1);
            }

            @Override
            public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
                return new TossCredentials("client-id", "client-secret");
            }
        };
    }

    private void stubToken() {
        server.stubFor(post("/oauth2/token").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"access_token":"access-token","token_type":"Bearer","expires_in":600}
                        """)));
    }

    private void stubHoldings() {
        server.stubFor(get("/api/v1/holdings").willReturn(json("""
                {"result":{
                  "totalPurchaseAmount":{"krw":"1000","usd":"10.25"},
                  "marketValue":{"amount":{"krw":"1300","usd":"13.25"},"amountAfterCost":{"krw":"1290","usd":"13.15"}},
                  "profitLoss":{"amount":{"krw":"-120","usd":"-1.20"},"amountAfterCost":{"krw":"-130","usd":"-1.30"},"rate":"-0.10","rateAfterCost":"-0.11"},
                  "dailyProfitLoss":{"amount":{"krw":"15","usd":"0.15"},"rate":"0.02"},
                  "items":[
                    {"symbol":"AAPL","name":"Apple Inc.","marketCountry":"US","currency":"USD","quantity":"2","lastPrice":"190.00","averagePurchasePrice":"180.50","marketValue":{"purchaseAmount":"361.00","amount":"380.00","amountAfterCost":"379.25"},"profitLoss":{"amount":"19.00","amountAfterCost":"18.25","rate":"0.0526","rateAfterCost":"0.0505"},"dailyProfitLoss":{"amount":"4.00","rate":"0.0106"},"cost":{"commission":"0.75","tax":null}},
                    {"symbol":"005930","name":"Samsung Electronics","marketCountry":"KR","currency":"KRW","quantity":"1","lastPrice":"80000","averagePurchasePrice":"70000","marketValue":{"purchaseAmount":"70000","amount":"80000","amountAfterCost":"79900"},"profitLoss":{"amount":"10000","amountAfterCost":"9900","rate":"0.1428","rateAfterCost":"0.1414"},"dailyProfitLoss":{"amount":"500","rate":"0.006"},"cost":{"commission":"100","tax":"0"}}
                  ]}}
                """)));
    }

    private void assertInvalidPositionsItem(String body) {
        server.stubFor(get("/api/v1/holdings").willReturn(json(body)));

        assertThatThrownBy(() -> adapter().getPositions(ACCOUNT))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.isRetriable()).isFalse();
                });
        server.resetRequests();
    }

    private void assertInvalidAccountItem(String body) {
        server.stubFor(get("/api/v1/holdings").willReturn(json(body)));

        assertThatThrownBy(() -> adapter().getAccount(ACCOUNT))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.isRetriable()).isFalse();
                });
        server.resetRequests();
    }

    private void assertInvalidAccountSeq(String brokerAccountId) {
        var account = new BrokerAccountRef(CONNECTION_ID, brokerAccountId, "GENERAL", "******3210");

        assertThatThrownBy(() -> adapter().getAccount(account))
                .isInstanceOfSatisfying(BrokerException.class, exception ->
                        assertThat(exception.category()).isEqualTo(BrokerErrorCategory.INVALID_REQUEST));
        assertThatThrownBy(() -> adapter().getPositions(account))
                .isInstanceOfSatisfying(BrokerException.class, exception ->
                        assertThat(exception.category()).isEqualTo(BrokerErrorCategory.INVALID_REQUEST));
        assertThatThrownBy(() -> adapter().getAccountCapacity(account, Currency.USD))
                .isInstanceOfSatisfying(BrokerException.class, exception ->
                        assertThat(exception.category()).isEqualTo(BrokerErrorCategory.INVALID_REQUEST));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse()
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Request-Id", "req-success")
                .withHeader("X-RateLimit-Limit", "100")
                .withHeader("X-RateLimit-Remaining", "77")
                .withBody(body);
    }
}
