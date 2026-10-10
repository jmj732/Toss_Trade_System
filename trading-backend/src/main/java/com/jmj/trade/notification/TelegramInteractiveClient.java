package com.jmj.trade.notification;

/**
 * Interactive Telegram client for approval workflows with keyboard buttons and callbacks.
 * Provides methods for sending messages with inline keyboards, answering callback queries,
 * and editing messages.
 */
public interface TelegramInteractiveClient {
    /**
     * Sends a message with an inline keyboard to the configured chat.
     *
     * @param message The message text (truncated to 4096 chars). No parse_mode set.
     * @param keyboard List of keyboard rows, each row is a list of (buttonText, callbackData) pairs.
     *                 callbackData must be ≤64 bytes total per button.
     * @return The Telegram message ID for later editing/deletion
     * @throws TelegramInteractiveException if send fails
     */
    Long sendWithKeyboard(String message, java.util.List<java.util.List<java.util.Map.Entry<String, String>>> keyboard);

    /**
     * Answers a callback query (user tap on inline button).
     *
     * @param callbackQueryId The callback_query.id from the Telegram update
     * @param text Optional notification text to show to the user
     * @param alert If true, shows an alert dialog instead of a toast
     * @throws TelegramInteractiveException if answer fails
     */
    void answerCallbackQuery(String callbackQueryId, String text, boolean alert);

    /**
     * Edits an existing message's text (e.g., to remove keyboard after decision).
     *
     * @param messageId The message ID to edit
     * @param newText The new message text (≤4096 chars)
     * @throws TelegramInteractiveException if edit fails
     */
    void editMessageText(Long messageId, String newText);
}
