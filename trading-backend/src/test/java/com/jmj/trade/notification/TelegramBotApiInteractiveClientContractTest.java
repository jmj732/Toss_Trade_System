package com.jmj.trade.notification;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.notification.TelegramInteractiveClient.Button;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramBotApiInteractiveClientContractTest {

    private WireMockServer server;
    private TelegramBotApiInteractiveClient client;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = new TelegramBotApiInteractiveClient(URI.create(server.baseUrl()), "TEST_TOKEN", "-100123");
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void sendsInlineKeyboardWithoutParseModeAndReturnsMessageId() {
        server.stubFor(post(urlEqualTo("/botTEST_TOKEN/sendMessage")).willReturn(json(
                "{\"ok\":true,\"result\":{\"message_id\":77}}")));

        var id = client.sendMessage("요청", List.of(List.of(new Button("승인", "A:token"))));

        assertThat(id).isEqualTo(77L);
        server.verify(postRequestedFor(urlEqualTo("/botTEST_TOKEN/sendMessage")).withRequestBody(equalToJson("""
                {"chat_id":"-100123","text":"요청",
                 "reply_markup":{"inline_keyboard":[[{"text":"승인","callback_data":"A:token"}]]}}
                """)));
    }

    @Test
    void plainMessageHasNoReplyMarkupAndLongTextIsTruncated() {
        server.stubFor(post(urlEqualTo("/botTEST_TOKEN/sendMessage")).willReturn(json(
                "{\"ok\":true,\"result\":{\"message_id\":5}}")));

        client.sendMessage("x".repeat(5000), List.of());

        server.verify(postRequestedFor(urlEqualTo("/botTEST_TOKEN/sendMessage")).withRequestBody(equalToJson(
                "{\"chat_id\":\"-100123\",\"text\":\"" + "x".repeat(4096) + "\"}")));
    }

    @Test
    void answersCallbackAndEditsMessageWithoutKeyboard() {
        server.stubFor(post(urlEqualTo("/botTEST_TOKEN/answerCallbackQuery")).willReturn(json("{\"ok\":true}")));
        server.stubFor(post(urlEqualTo("/botTEST_TOKEN/editMessageText")).willReturn(json("{\"ok\":true}")));

        client.answerCallbackQuery("cbq-1", "완료");
        client.editMessageText(77L, "최종 승인 완료.");

        server.verify(postRequestedFor(urlEqualTo("/botTEST_TOKEN/answerCallbackQuery")).withRequestBody(
                equalToJson("{\"callback_query_id\":\"cbq-1\",\"text\":\"완료\"}")));
        server.verify(postRequestedFor(urlEqualTo("/botTEST_TOKEN/editMessageText")).withRequestBody(
                equalToJson("{\"chat_id\":\"-100123\",\"message_id\":77,\"text\":\"최종 승인 완료.\"}")));
    }

    @Test
    void failuresExposeOnlySanitizedReasons() {
        server.stubFor(post(urlEqualTo("/botTEST_TOKEN/sendMessage")).willReturn(aResponse().withStatus(502)));
        server.stubFor(post(urlEqualTo("/botTEST_TOKEN/editMessageText")).willReturn(json("{\"ok\":false}")));

        assertThatThrownBy(() -> client.sendMessage("x", List.of()))
                .isInstanceOf(TelegramInteractiveException.class)
                .hasMessageNotContaining("TEST_TOKEN")
                .extracting(failure -> ((TelegramInteractiveException) failure).reason()).isEqualTo("HTTP_502");
        assertThatThrownBy(() -> client.editMessageText(1L, "x"))
                .extracting(failure -> ((TelegramInteractiveException) failure).reason()).isEqualTo("API_REJECTED");
    }

    @Test
    void callbackDataAbove64BytesIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> new Button("승인", "A:" + "x".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Button("승인", "A:" + "x".repeat(62)).callbackData()).hasSize(64);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }
}
