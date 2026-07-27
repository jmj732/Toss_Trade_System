package com.jmj.trade.broker.toss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TossApiClientContractTest {

    private static final UUID CONNECTION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final String OLD_TOKEN = "old-access-token";
    private static final String NEW_TOKEN = "new-access-token";
    private static final String ACCOUNT_SEQ = "01";

    private WireMockServer server;
    private TossTokenManager tokenManager;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void getAccountsSendsBearerAndReturnsSuccessMetadata() {
        startServer();
        withToken(OLD_TOKEN);
        server.stubFor(get("/api/v1/accounts")
                .withHeader("Authorization", equalTo("Bearer " + OLD_TOKEN))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Request-Id", "req-accounts")
                        .withHeader("X-RateLimit-Limit", "100")
                        .withHeader("X-RateLimit-Remaining", "99")
                        .withHeader("X-RateLimit-Reset", "7")
                        .withBody("""
                                {"result":[{"accountNo":"1234567890","accountSeq":"01","accountType":"GENERAL","extra":"ignored"}]}
                                """)));

        var response = client().getAccounts(CONNECTION_ID);

        assertThat(response.value()).hasSize(1);
        assertThat(response.value().getFirst().accountNo()).isEqualTo("1234567890");
        assertThat(response.value().getFirst().accountSeq()).isEqualTo("01");
        assertThat(response.value().getFirst().accountType()).isEqualTo("GENERAL");
        assertThat(response.metadata().requestId()).isEqualTo("req-accounts");
        assertThat(response.metadata().rateLimit()).hasValueSatisfying(rateLimit -> {
            assertThat(rateLimit.limit()).contains(100);
            assertThat(rateLimit.remaining()).contains(99);
            assertThat(rateLimit.resetAt()).hasValueSatisfying(reset ->
                    assertThat(Duration.between(response.metadata().observedAt(), reset)).isEqualTo(Duration.ofSeconds(7)));
            assertThat(rateLimit.retryAfter()).isEmpty();
        });
    }

    @Test
    void readEndpointsUseOfficialRoutesHeadersAndQueries() {
        startServer();
        withToken(OLD_TOKEN);
        server.stubFor(get("/api/v1/holdings")
                .withHeader("Authorization", equalTo("Bearer " + OLD_TOKEN))
                .withHeader("X-Tossinvest-Account", equalTo(ACCOUNT_SEQ))
                .willReturn(json("""
                        {"result":{"summary":{"totalValue":{"currency":"USD","amount":"100.00"}},"items":[]}}
                        """)));
        server.stubFor(get(urlEqualTo("/api/v1/prices?symbols=AAPL"))
                .withHeader("Authorization", equalTo("Bearer " + OLD_TOKEN))
                .willReturn(json("""
                        {"result":[{"symbol":"AAPL","price":"180.12","timestamp":"2026-07-27T01:02:03Z"}]}
                        """)));
        server.stubFor(get(urlEqualTo("/api/v1/buying-power?currency=USD"))
                .withHeader("Authorization", equalTo("Bearer " + OLD_TOKEN))
                .withHeader("X-Tossinvest-Account", equalTo(ACCOUNT_SEQ))
                .willReturn(json("""
                        {"result":{"currency":"USD","cashBuyingPower":"250.25"}}
                        """)));

        assertThat(client().getHoldings(CONNECTION_ID, ACCOUNT_SEQ).value().items()).isEmpty();
        assertThat(client().getPrices(CONNECTION_ID, "AAPL").value()).hasSize(1);
        assertThat(client().getBuyingPower(CONNECTION_ID, ACCOUNT_SEQ, "USD").value().cashBuyingPower()).isEqualTo("250.25");

        server.verify(getRequestedFor(urlEqualTo("/api/v1/holdings")));
        server.verify(getRequestedFor(urlEqualTo("/api/v1/prices?symbols=AAPL")));
        server.verify(getRequestedFor(urlEqualTo("/api/v1/buying-power?currency=USD")));
    }

    @Test
    void missingRateLimitHeadersLeaveMetadataEmpty() {
        startServer();
        withToken(OLD_TOKEN);
        server.stubFor(get("/api/v1/accounts")
                .willReturn(json("""
                        {"result":[]}
                        """)));

        var response = client().getAccounts(CONNECTION_ID);

        assertThat(response.metadata().rateLimit()).isEmpty();
    }

    @Test
    void malformedSuccessOrRateHeadersAreContractErrorsWithoutRawBodyOrSecrets() {
        assertContractError("""
                {"notResult":"raw old-access-token"}
                """);
        assertContractError("""
                {"result":[]}
                """, "X-RateLimit-Limit", "-1");
        assertContractError("""
                {"result":[]}
                """, "X-RateLimit-Reset", String.valueOf(Long.MAX_VALUE));
    }

    @Test
    void mapsRegularErrorsToBrokerExceptions() {
        assertHttpError(400, "bad-request", BrokerErrorCategory.INVALID_REQUEST, false);
        assertHttpError(401, "other-auth", BrokerErrorCategory.AUTHENTICATION, false);
        assertHttpError(403, "forbidden", BrokerErrorCategory.AUTHORIZATION, false);
        assertHttpError(404, "missing", BrokerErrorCategory.NOT_FOUND, false);
        assertHttpError(429, "rate-limit", BrokerErrorCategory.RATE_LIMITED, true);
        assertHttpError(500, "server-error", BrokerErrorCategory.TEMPORARY, true);
    }

    @Test
    void rateLimitErrorKeepsRetryAfterAndDoesNotRetry() {
        startServer();
        withToken(OLD_TOKEN);
        server.stubFor(get("/api/v1/accounts")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "11")
                        .withBody(errorBody("req-429", "new-code"))));

        assertThatThrownBy(() -> client().getAccounts(CONNECTION_ID))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.RATE_LIMITED);
                    assertThat(exception.requestId()).contains("req-429");
                    assertThat(exception.brokerErrorCode()).contains("new-code");
                    assertThat(exception.retryAfter()).contains(Duration.ofSeconds(11));
                    assertThat(exception.isRetriable()).isTrue();
                    assertNoSensitiveData(exception);
                });
        server.verify(1, getRequestedFor(urlEqualTo("/api/v1/accounts")));
    }

    @Test
    void refreshesOnceForInvalidOrExpiredTokenOnly() {
        assertRefreshesOnce("invalid-token");
        assertRefreshesOnce("expired-token");
    }

    @Test
    void secondUnauthorizedAfterRefreshFailsWithoutThirdAttempt() {
        startServer();
        when(tokenManager().getAccessToken(CONNECTION_ID)).thenReturn(OLD_TOKEN, NEW_TOKEN);
        server.stubFor(get("/api/v1/accounts")
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(errorBody("req-401", "expired-token"))));

        assertThatThrownBy(() -> client().getAccounts(CONNECTION_ID))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.AUTHENTICATION);
                    assertThat(exception.isRetriable()).isFalse();
                });

        verify(tokenManager).invalidateIfCurrent(CONNECTION_ID, OLD_TOKEN);
        server.verify(2, getRequestedFor(urlEqualTo("/api/v1/accounts")));
    }

    @Test
    void otherUnauthorizedDoesNotRefresh() {
        startServer();
        withToken(OLD_TOKEN);
        server.stubFor(get("/api/v1/accounts")
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(errorBody("req-401", "other-auth"))));

        assertThatThrownBy(() -> client().getAccounts(CONNECTION_ID))
                .isInstanceOf(BrokerException.class);
        verify(tokenManager, never()).invalidateIfCurrent(CONNECTION_ID, OLD_TOKEN);
        server.verify(1, getRequestedFor(urlEqualTo("/api/v1/accounts")));
    }

    @Test
    void mapsConnectionFailuresToNetworkWithoutRawBodyOrSecrets() {
        withToken(OLD_TOKEN);
        var properties = new TossApiProperties(
                java.net.URI.create("http://127.0.0.1:1"),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(300),
                Duration.ofMillis(400),
                Duration.ZERO);

        assertThatThrownBy(() -> new TossApiClient(properties, tokenManager).getAccounts(CONNECTION_ID))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.NETWORK);
                    assertThat(exception.isRetriable()).isTrue();
                    assertNoSensitiveData(exception);
                });
    }

    private void assertRefreshesOnce(String code) {
        startServer();
        when(tokenManager().getAccessToken(CONNECTION_ID)).thenReturn(OLD_TOKEN, NEW_TOKEN);
        server.stubFor(get("/api/v1/accounts")
                .withHeader("Authorization", equalTo("Bearer " + OLD_TOKEN))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(errorBody("req-old", code))));
        server.stubFor(get("/api/v1/accounts")
                .withHeader("Authorization", equalTo("Bearer " + NEW_TOKEN))
                .willReturn(json("""
                        {"result":[]}
                        """)));

        var response = client().getAccounts(CONNECTION_ID);

        assertThat(response.value()).isEmpty();
        verify(tokenManager).invalidateIfCurrent(CONNECTION_ID, OLD_TOKEN);
        server.verify(2, getRequestedFor(urlEqualTo("/api/v1/accounts")));
        stopServer();
        tokenManager = null;
    }

    private void assertHttpError(
            int status,
            String code,
            BrokerErrorCategory category,
            boolean retriable) {
        startServer();
        withToken(OLD_TOKEN);
        server.stubFor(get("/api/v1/accounts")
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(errorBody("req-" + status, code))));

        assertThatThrownBy(() -> client().getAccounts(CONNECTION_ID))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(category);
                    assertThat(exception.httpStatus()).contains(status);
                    assertThat(exception.requestId()).contains("req-" + status);
                    assertThat(exception.brokerErrorCode()).contains(code);
                    assertThat(exception.isRetriable()).isEqualTo(retriable);
                    assertNoSensitiveData(exception);
                });
        server.verify(1, getRequestedFor(urlEqualTo("/api/v1/accounts")));
        stopServer();
        tokenManager = null;
    }

    private void assertContractError(String body) {
        assertContractError(body, null, null);
    }

    private void assertContractError(String body, String headerName, String headerValue) {
        startServer();
        withToken(OLD_TOKEN);
        var response = aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(body);
        if (headerName != null) {
            response.withHeader(headerName, headerValue);
        }
        server.stubFor(get("/api/v1/accounts").willReturn(response));

        assertThatThrownBy(() -> client().getAccounts(CONNECTION_ID))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.httpStatus()).contains(200);
                    assertThat(exception.isRetriable()).isFalse();
                    assertNoSensitiveData(exception);
                });
        stopServer();
        tokenManager = null;
    }

    private TossApiClient client() {
        return new TossApiClient(properties(), tokenManager());
    }

    private TossApiProperties properties() {
        return new TossApiProperties(
                java.net.URI.create(server.baseUrl()),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ZERO);
    }

    private TossTokenManager tokenManager() {
        if (tokenManager == null) {
            tokenManager = mock(TossTokenManager.class);
        }
        return tokenManager;
    }

    private void withToken(String token) {
        when(tokenManager().getAccessToken(CONNECTION_ID)).thenReturn(token);
    }

    private void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static String errorBody(String requestId, String code) {
        return """
                {"error":{"requestId":"%s","code":"%s","message":"contains old-access-token and client-secret"}}
                """.formatted(requestId, code);
    }

    private static void assertNoSensitiveData(BrokerException exception) {
        assertThat(exception.getMessage())
                .doesNotContain("old-access-token")
                .doesNotContain("new-access-token")
                .doesNotContain("client-secret")
                .doesNotContain("raw");
    }
}
