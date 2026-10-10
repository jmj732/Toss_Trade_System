package com.jmj.trade.notification;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TelegramBotApiClient implements TelegramMessageSender, TelegramInteractiveClient {

    private final RestClient client;
    private final String botToken;
    private final String chatId;

    TelegramBotApiClient(String botToken, String chatId) {
        this(URI.create("https://api.telegram.org"), botToken, chatId);
    }

    TelegramBotApiClient(URI apiBaseUrl, String botToken, String chatId) {
        var endpoint = requireEndpoint(apiBaseUrl);
        this.botToken = Objects.requireNonNullElse(botToken, "").trim();
        this.chatId = Objects.requireNonNullElse(chatId, "").trim();
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(endpoint.toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public void send(String message) {
        if (botToken.isBlank() || chatId.isBlank()) {
            throw new TelegramDeliveryException("CONFIGURATION");
        }
        try {
            var response = client.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", Objects.requireNonNull(message, "message")))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean(false)) {
                throw new TelegramDeliveryException("API_REJECTED");
            }
        } catch (TelegramDeliveryException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new TelegramDeliveryException("HTTP_" + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new TelegramDeliveryException("NETWORK");
        }
    }

    @Override
    public Long sendWithKeyboard(String message, List<List<Map.Entry<String, String>>> keyboard) {
        if (botToken.isBlank() || chatId.isBlank()) {
            throw new TelegramInteractiveException("CONFIGURATION");
        }
        if (message == null) {
            message = "";
        }
        if (message.length() > 4096) {
            message = message.substring(0, 4096);
        }
        try {
            var payload = Map.of(
                    "chat_id", chatId,
                    "text", message,
                    "reply_markup", buildKeyboard(keyboard)
            );
            var response = client.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean(false)) {
                throw new TelegramInteractiveException("API_REJECTED");
            }
            var messageId = response.path("result").path("message_id").asLong();
            if (messageId == 0) {
                throw new TelegramInteractiveException("NO_MESSAGE_ID");
            }
            return messageId;
        } catch (TelegramInteractiveException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new TelegramInteractiveException("HTTP_" + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new TelegramInteractiveException("NETWORK");
        }
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId, String text, boolean alert) {
        if (botToken.isBlank() || callbackQueryId == null || callbackQueryId.isBlank()) {
            throw new TelegramInteractiveException("INVALID_INPUT");
        }
        try {
            var payload = Map.of(
                    "callback_query_id", callbackQueryId,
                    "text", Objects.requireNonNullElse(text, ""),
                    "show_alert", alert
            );
            var response = client.post()
                    .uri("/bot{token}/answerCallbackQuery", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean(false)) {
                throw new TelegramInteractiveException("API_REJECTED");
            }
        } catch (TelegramInteractiveException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new TelegramInteractiveException("HTTP_" + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new TelegramInteractiveException("NETWORK");
        }
    }

    @Override
    public void editMessageText(Long messageId, String newText) {
        if (botToken.isBlank() || chatId.isBlank() || messageId == null) {
            throw new TelegramInteractiveException("INVALID_INPUT");
        }
        if (newText == null) {
            newText = "";
        }
        if (newText.length() > 4096) {
            newText = newText.substring(0, 4096);
        }
        try {
            var payload = Map.of(
                    "chat_id", chatId,
                    "message_id", messageId,
                    "text", newText
            );
            var response = client.post()
                    .uri("/bot{token}/editMessageText", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("ok").asBoolean(false)) {
                throw new TelegramInteractiveException("API_REJECTED");
            }
        } catch (TelegramInteractiveException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new TelegramInteractiveException("HTTP_" + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new TelegramInteractiveException("NETWORK");
        }
    }

    private static Object buildKeyboard(List<List<Map.Entry<String, String>>> rows) {
        var result = Map.of("inline_keyboard", rows.stream()
                .map(row -> row.stream()
                        .map(btn -> Map.of("text", btn.getKey(), "callback_data", btn.getValue()))
                        .toList())
                .toList());
        return result;
    }

    private static URI requireEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "apiBaseUrl");
        var host = endpoint.getHost();
        var local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (host == null || endpoint.getUserInfo() != null
                || !("https".equalsIgnoreCase(endpoint.getScheme())
                || ("http".equalsIgnoreCase(endpoint.getScheme()) && local))) {
            throw new IllegalArgumentException("Telegram API endpoint must be HTTPS");
        }
        return endpoint;
    }
}

final class TelegramDeliveryException extends RuntimeException {

    private final String reason;

    TelegramDeliveryException(String reason) {
        super("Telegram delivery failed: " + safeReason(reason));
        this.reason = safeReason(reason);
    }

    String reason() {
        return reason;
    }

    private static String safeReason(String reason) {
        if (reason == null || !reason.matches("[A-Z0-9_]{1,60}")) {
            return "DELIVERY";
        }
        return reason;
    }
}
