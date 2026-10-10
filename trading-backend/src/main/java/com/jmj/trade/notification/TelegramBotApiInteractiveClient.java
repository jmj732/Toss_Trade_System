package com.jmj.trade.notification;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bot API implementation of {@link TelegramInteractiveClient}. Deliberately a separate class from
 * {@link TelegramBotApiClient} so it never matches {@link TelegramMessageSender} by runtime type.
 */
final class TelegramBotApiInteractiveClient implements TelegramInteractiveClient {

    private final RestClient client;
    private final String botToken;
    private final String chatId;

    TelegramBotApiInteractiveClient(String botToken, String chatId) {
        this(URI.create("https://api.telegram.org"), botToken, chatId);
    }

    TelegramBotApiInteractiveClient(URI apiBaseUrl, String botToken, String chatId) {
        this.botToken = Objects.requireNonNullElse(botToken, "").trim();
        this.chatId = Objects.requireNonNullElse(chatId, "").trim();
        this.client = TelegramBotApiClient.restClient(apiBaseUrl);
    }

    @Override
    public long sendMessage(String text, List<List<Button>> keyboard) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("chat_id", chatId);
        payload.put("text", TelegramInteractiveClient.truncate(text, MAX_TEXT_LENGTH));
        if (keyboard != null && !keyboard.isEmpty()) {
            payload.put("reply_markup", Map.of("inline_keyboard", keyboard.stream()
                    .map(row -> row.stream()
                            .map(button -> Map.of("text", button.text(), "callback_data", button.callbackData()))
                            .toList())
                    .toList()));
        }
        var response = call("sendMessage", payload);
        var messageId = response.path("result").path("message_id");
        if (!messageId.isIntegralNumber() || !messageId.canConvertToLong()) {
            throw new TelegramInteractiveException("NO_MESSAGE_ID");
        }
        return messageId.longValue();
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId, String text) {
        if (callbackQueryId == null || callbackQueryId.isBlank()) {
            throw new TelegramInteractiveException("INVALID_INPUT");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("callback_query_id", callbackQueryId);
        payload.put("text", TelegramInteractiveClient.truncate(text, MAX_CALLBACK_ANSWER_LENGTH));
        call("answerCallbackQuery", payload);
    }

    @Override
    public void editMessageText(long messageId, String text) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("chat_id", chatId);
        payload.put("message_id", messageId);
        payload.put("text", TelegramInteractiveClient.truncate(text, MAX_TEXT_LENGTH));
        call("editMessageText", payload);
    }

    private JsonNode call(String method, Map<String, Object> payload) {
        if (botToken.isBlank() || chatId.isBlank()) {
            throw new TelegramInteractiveException("CONFIGURATION");
        }
        return translate(() -> {
            var response = client.post()
                    .uri("/bot{token}/" + method, botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean(false)) {
                throw new TelegramInteractiveException("API_REJECTED");
            }
            return response;
        });
    }

    private static JsonNode translate(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (TelegramInteractiveException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new TelegramInteractiveException("HTTP_" + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new TelegramInteractiveException("NETWORK");
        }
    }
}
