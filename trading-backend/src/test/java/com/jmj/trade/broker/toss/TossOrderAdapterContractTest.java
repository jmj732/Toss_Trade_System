package com.jmj.trade.broker.toss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderModification;
import com.jmj.trade.broker.BrokerOrderRequest;
import com.jmj.trade.broker.BrokerOrderSide;
import com.jmj.trade.broker.BrokerOrderType;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TossOrderAdapterContractTest {

    private static final UUID CONNECTION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final String TOKEN = "access-token";
    private static final String ACCOUNT = "01";
    private static final String CLIENT = "live-order-001";

    private WireMockServer server;
    private TossTokenManager tokenManager;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void createsOrderWithOfficialHeadersAndQuantityContract() {
        start();
        token();
        server.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("X-Tossinvest-Account", equalTo(ACCOUNT))
                .withRequestBody(matchingJsonPath("$.clientOrderId", equalTo(CLIENT)))
                .withRequestBody(matchingJsonPath("$.orderType", equalTo("LIMIT")))
                .withRequestBody(matchingJsonPath("$.quantity", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.price", equalTo("180.5")))
                .willReturn(json("""
                        {"result":{"orderId":"toss-order-1","clientOrderId":"live-order-001"}}
                        """)));

        var response = adapter().placeOrder(account(),
                new BrokerOrderRequest(BrokerOrderSide.BUY, BrokerOrderType.LIMIT,
                        "AAPL", new java.math.BigDecimal("2"), new java.math.BigDecimal("180.5"), Currency.USD),
                CLIENT);

        assertThat(response.value().isAccepted())
                .as("create ack: %s", response.value())
                .isTrue();
        assertThat(response.value().brokerOrderId()).isEqualTo("toss-order-1");
        assertThat(response.value().idempotencyKey()).isEqualTo(CLIENT);
        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
    }

    @Test
    void clientDecodesOfficialCreateResponse() {
        start();
        token();
        server.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(json("{\"result\":{\"orderId\":\"toss-order-1\",\"clientOrderId\":\"live-order-001\"}}")));

        var response = new TossApiClient(properties(), tokenManager).createOrder(account(),
                new BrokerOrderRequest(BrokerOrderSide.BUY, BrokerOrderType.LIMIT,
                        "AAPL", new java.math.BigDecimal("2"), new java.math.BigDecimal("180.5"), Currency.USD),
                CLIENT);

        assertThat(response.value().orderId()).isEqualTo("toss-order-1");
        assertThat(response.value().clientOrderId()).isEqualTo(CLIENT);
    }

    @Test
    void closedOrdersFollowEveryCursorPageAndDetailDoesNotInventClientId() {
        start();
        token();
        server.stubFor(get(urlEqualTo("/api/v1/orders?status=CLOSED&limit=100"))
                .withHeader("X-Tossinvest-Account", equalTo(ACCOUNT))
                .willReturn(json("""
                        {"result":{"orders":[{"orderId":"o-1","symbol":"AAPL","side":"BUY","orderType":"LIMIT","timeInForce":"DAY","status":"FILLED","quantity":"2","price":"180.5","orderAmount":null,"currency":"USD","orderedAt":"2026-03-29T10:00:00+09:00","canceledAt":null,"execution":{"filledQuantity":"2","averageFilledPrice":"180.5","filledAmount":"361","commission":"1","tax":"0","filledAt":"2026-03-29T10:00:01+09:00","settlementDate":null}}],"nextCursor":"next","hasNext":true}}
                        """)));
        server.stubFor(get(urlEqualTo("/api/v1/orders?status=CLOSED&cursor=next&limit=100"))
                .withHeader("X-Tossinvest-Account", equalTo(ACCOUNT))
                .willReturn(json("""
                        {"result":{"orders":[],"nextCursor":null,"hasNext":false}}
                        """)));

        var response = adapter().getOrders(account(), BrokerOrderGroup.CLOSED);

        assertThat(response.value()).singleElement().satisfies(order -> {
            assertThat(order.brokerOrderId()).isEqualTo("o-1");
            assertThat(order.filledQuantity()).isEqualByComparingTo("2");
            assertThat(order.idempotencyKey()).isNull();
        });
        server.verify(1, getRequestedFor(urlEqualTo("/api/v1/orders?status=CLOSED&limit=100")));
        server.verify(1, getRequestedFor(urlEqualTo("/api/v1/orders?status=CLOSED&cursor=next&limit=100")));
    }

    @Test
    void modifyAndCancelUseOfficialOperationRoutesAndReturnedOrderId() {
        start();
        token();
        server.stubFor(post(urlEqualTo("/api/v1/orders/o-1/modify"))
                .withRequestBody(matchingJsonPath("$.orderType", equalTo("LIMIT")))
                .willReturn(json("{\"result\":{\"orderId\":\"o-2\"}}")));
        server.stubFor(post(urlEqualTo("/api/v1/orders/o-1/cancel"))
                .willReturn(json("{\"result\":{\"orderId\":\"o-3\"}}")));

        var modified = adapter().modifyOrder(account(), BrokerOrderModification.reprice("o-1", new java.math.BigDecimal("181")));
        var canceled = adapter().cancelOrder(account(), "o-1");

        assertThat(modified.value().brokerOrderId())
                .as("modify ack: %s", modified.value())
                .isEqualTo("o-2");
        assertThat(canceled.value().brokerOrderId()).isEqualTo("o-3");
        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders/o-1/modify")));
        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders/o-1/cancel")));
    }

    @Test
    void write401IsUnknownWithoutTokenRefreshOrSecondPost() {
        start();
        token();
        server.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"requestId\":\"r\",\"code\":\"expired-token\",\"message\":\"expired\"}}")));

        var response = adapter().placeOrder(account(),
                new BrokerOrderRequest(BrokerOrderSide.BUY, BrokerOrderType.MARKET,
                        "AAPL", new java.math.BigDecimal("1"), null, Currency.USD), CLIENT);

        assertThat(response.value().isUnknown()).isTrue();
        verify(tokenManager).getAccessToken(CONNECTION_ID);
        verify(tokenManager, never()).invalidateIfCurrent(CONNECTION_ID, 1, TOKEN);
        server.verify(1, postRequestedFor(urlEqualTo("/api/v1/orders")));
    }

    private TossInvestBrokerAdapter adapter() {
        return new TossInvestBrokerAdapter(new TossApiClient(properties(), tokenManager), new TossResponseMapper());
    }

    private BrokerAccountRef account() {
        return new BrokerAccountRef(CONNECTION_ID, ACCOUNT, "GENERAL", "****0001");
    }

    private TossApiProperties properties() {
        return new TossApiProperties(java.net.URI.create("http://127.0.0.1:" + server.port()),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ZERO);
    }

    private void token() {
        tokenManager = mock(TossTokenManager.class);
        when(tokenManager.getAccessToken(CONNECTION_ID)).thenReturn(new TossAccessToken(TOKEN, 1));
    }

    private void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }
}
