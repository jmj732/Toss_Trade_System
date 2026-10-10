package com.jmj.trade.notification;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Interactive Telegram Bot API calls used by the thesis approval workflow (inline keyboards and callback answers).
 * Messages are always sent to the configured chat without {@code parse_mode}. Failures surface as
 * {@link TelegramInteractiveException} whose {@code reason()} is a sanitized code safe to log.
 */
public interface TelegramInteractiveClient {

    /** Telegram's sendMessage/editMessageText text limit. */
    int MAX_TEXT_LENGTH = 4096;
    /** Telegram's answerCallbackQuery text limit. */
    int MAX_CALLBACK_ANSWER_LENGTH = 200;
    /** Telegram's callback_data limit in bytes. */
    int MAX_CALLBACK_DATA_BYTES = 64;

    /**
     * Sends {@code text} to the configured chat with an optional inline keyboard (empty list = no keyboard).
     *
     * @return the Telegram message_id of the sent message
     */
    long sendMessage(String text, List<List<Button>> keyboard);

    /** Answers a callback query so the client stops its loading indicator; {@code text} may be blank. */
    void answerCallbackQuery(String callbackQueryId, String text);

    /** Replaces a sent message's text; omitting reply_markup removes its inline keyboard. */
    void editMessageText(long messageId, String text);

    record Button(String text, String callbackData) {
        public Button {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(callbackData, "callbackData");
            var size = callbackData.getBytes(StandardCharsets.UTF_8).length;
            if (size < 1 || size > MAX_CALLBACK_DATA_BYTES) {
                throw new IllegalArgumentException("callback_data must be 1-64 bytes");
            }
        }
    }

    /** Truncates to {@code limit} UTF-16 units without splitting a surrogate pair. */
    static String truncate(String text, int limit) {
        var value = Objects.requireNonNullElse(text, "");
        if (value.length() <= limit) return value;
        var end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end);
    }
}
