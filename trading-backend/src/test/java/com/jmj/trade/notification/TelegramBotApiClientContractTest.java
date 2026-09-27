package com.jmj.trade.notification;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramBotApiClientContractTest {

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void sendsTextToConfiguredChatThroughBotApi() {
        server.stubFor(post(urlEqualTo("/botTEST_TOKEN/sendMessage"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));
        var client = new TelegramBotApiClient(
                URI.create(server.baseUrl()), "TEST_TOKEN", "123456");

        client.send("[MARKET] RISK_TRANSITION");

        server.verify(postRequestedFor(urlEqualTo("/botTEST_TOKEN/sendMessage"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"chat_id":"123456","text":"[MARKET] RISK_TRANSITION"}
                        """)));
    }

    @Test
    void hidesBotTokenFromApiFailure() {
        server.stubFor(post(urlEqualTo("/botVERY_SECRET_TOKEN/sendMessage"))
                .willReturn(aResponse().withStatus(500)));
        var client = new TelegramBotApiClient(
                URI.create(server.baseUrl()), "VERY_SECRET_TOKEN", "123456");

        assertThatThrownBy(() -> client.send("alert"))
                .isInstanceOf(TelegramDeliveryException.class)
                .hasMessage("Telegram delivery failed: HTTP_500")
                .hasMessageNotContaining("VERY_SECRET_TOKEN");
    }
}
