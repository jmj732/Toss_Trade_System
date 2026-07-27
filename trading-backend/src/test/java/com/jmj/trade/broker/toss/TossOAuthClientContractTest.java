package com.jmj.trade.broker.toss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossOAuthClientContractTest {

    private static final UUID CONNECTION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String ACCESS_TOKEN = "test-access-token";

    private WireMockServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void requestsClientCredentialsTokenWithFormBody() {
        startServer();
        server.stubFor(post("/oauth2/token")
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=" + CLIENT_ID))
                .withRequestBody(containing("client_secret=" + CLIENT_SECRET))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"%s","token_type":"Bearer","expires_in":600}
                                """.formatted(ACCESS_TOKEN))));

        var token = client().issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET));

        assertThat(token.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(token.expiresIn()).isEqualTo(Duration.ofSeconds(600));
        server.verify(postRequestedFor(urlEqualTo("/oauth2/token"))
                .withRequestBody(equalTo("grant_type=client_credentials&client_id=test-client-id&client_secret=test-client-secret")));
    }

    @Test
    void malformedSuccessIsContractErrorWithoutRawBodyOrSecrets() {
        startServer();
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"","expires_in":0,"raw":"body"}
                                """)));

        assertThatThrownBy(() -> client().issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.httpStatus()).contains(200);
                    assertThat(exception.isRetriable()).isFalse();
                    assertNoSensitiveData(exception);
                });
    }

    @Test
    void malformedJsonSuccessIsContractErrorWithoutRawBodyOrSecrets() {
        startServer();
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"test-access-token","expires_in":
                                """)));

        assertThatThrownBy(() -> client().issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.httpStatus()).contains(200);
                    assertThat(exception.isRetriable()).isFalse();
                    assertNoSensitiveData(exception);
                });
    }

    @Test
    void missingOrBlankSuccessBodyIsContractErrorWithoutRawBodyOrSecrets() {
        assertInvalidSuccessBody(null);
        assertInvalidSuccessBody("   ");
    }

    @Test
    void wrongTypeExpiresInSuccessIsContractErrorWithoutRawBodyOrSecrets() {
        startServer();
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"test-access-token","expires_in":"not-a-number","raw":"body"}
                                """)));

        assertThatThrownBy(() -> client().issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.httpStatus()).contains(200);
                    assertThat(exception.isRetriable()).isFalse();
                    assertNoSensitiveData(exception);
                });
    }

    @Test
    void missingOrNonBearerTokenTypeIsContractErrorWithoutRawBodyOrSecrets() {
        assertInvalidTokenType("""
                {"access_token":"test-access-token","expires_in":600}
                """);
        assertInvalidTokenType("""
                {"access_token":"test-access-token","token_type":"bearer","expires_in":600}
                """);
        assertInvalidTokenType("""
                {"access_token":"test-access-token","token_type":"MAC","expires_in":600}
                """);
    }

    @Test
    void mapsOAuthErrorsToSafeBrokerExceptions() {
        assertOAuthError(400, "invalid_request", BrokerErrorCategory.INVALID_REQUEST, false);
        assertOAuthError(400, "unsupported_grant_type", BrokerErrorCategory.INVALID_REQUEST, false);
        assertOAuthError(401, "invalid_client", BrokerErrorCategory.AUTHENTICATION, false);
        assertOAuthError(403, "access_denied", BrokerErrorCategory.AUTHORIZATION, false);
        assertOAuthError(500, "server_error", BrokerErrorCategory.TEMPORARY, true);
    }

    @Test
    void mapsRateLimitWithRetryAfter() {
        startServer();
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "7")
                        .withBody("""
                                {"error":"rate_limited","error_description":"too many requests"}
                                """)));

        assertThatThrownBy(() -> client().issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.RATE_LIMITED);
                    assertThat(exception.httpStatus()).contains(429);
                    assertThat(exception.brokerErrorCode()).contains("rate_limited");
                    assertThat(exception.retryAfter()).contains(Duration.ofSeconds(7));
                    assertThat(exception.isRetriable()).isTrue();
                    assertNoSensitiveData(exception);
                });
    }

    @Test
    void mapsConnectionFailuresToRetriableNetworkError() {
        var properties = new TossApiProperties(
                java.net.URI.create("http://127.0.0.1:1"),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(300),
                Duration.ZERO);
        var client = new TossOAuthClient(properties);

        assertThatThrownBy(() -> client.issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.NETWORK);
                    assertThat(exception.isRetriable()).isTrue();
                    assertNoSensitiveData(exception);
                });
    }

    @Test
    void delayedSuccessBeyondTokenRequestTimeoutIsNetworkError() {
        startServer();
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withFixedDelay(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"%s","token_type":"Bearer","expires_in":600}
                                """.formatted(ACCESS_TOKEN))));
        var properties = new TossApiProperties(
                java.net.URI.create(server.baseUrl()),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(300),
                Duration.ZERO);
        var client = new TossOAuthClient(properties);

        assertThatThrownBy(() -> client.issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.NETWORK);
                    assertThat(exception.isRetriable()).isTrue();
                    assertNoSensitiveData(exception);
                });
    }

    private void assertOAuthError(
            int status,
            String code,
            BrokerErrorCategory category,
            boolean retriable) {
        startServer();
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":"%s","error_description":"contains test-client-secret and test-access-token"}
                                """.formatted(code))));

        assertThatThrownBy(() -> client().issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(category);
                    assertThat(exception.httpStatus()).contains(status);
                    assertThat(exception.brokerErrorCode()).contains(code);
                    assertThat(exception.isRetriable()).isEqualTo(retriable);
                    assertNoSensitiveData(exception);
                });
        stopServer();
    }

    private void assertInvalidSuccessBody(String body) {
        startServer();
        var response = aResponse()
                .withHeader("Content-Type", "application/json");
        if (body != null) {
            response.withBody(body);
        }
        server.stubFor(post("/oauth2/token").willReturn(response));

        assertThatThrownBy(() -> client().issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.httpStatus()).contains(200);
                    assertThat(exception.isRetriable()).isFalse();
                    assertNoSensitiveData(exception);
                });
        stopServer();
    }

    private void assertInvalidTokenType(String body) {
        startServer();
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        assertThatThrownBy(() -> client().issueToken(CONNECTION_ID, new TossCredentials(CLIENT_ID, CLIENT_SECRET)))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.CONTRACT);
                    assertThat(exception.httpStatus()).contains(200);
                    assertThat(exception.isRetriable()).isFalse();
                    assertNoSensitiveData(exception);
                });
        stopServer();
    }

    private TossOAuthClient client() {
        return new TossOAuthClient(properties());
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

    private void startServer() {
        stopServer();
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    private static void assertNoSensitiveData(BrokerException exception) {
        assertThat(exception.getMessage())
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain("raw")
                .doesNotContain("body");
        assertThat(exception.toString())
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain("raw")
                .doesNotContain("body");
    }
}
