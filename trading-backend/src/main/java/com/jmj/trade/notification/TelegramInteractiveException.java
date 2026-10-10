package com.jmj.trade.notification;

/**
 * Exception thrown by TelegramInteractiveClient when a Telegram API call fails.
 * The reason() method returns a machine-readable error code suitable for logging and decision logic.
 */
public class TelegramInteractiveException extends RuntimeException {

    private final String reason;

    public TelegramInteractiveException(String reason) {
        super("Telegram interactive call failed: " + safeReason(reason));
        this.reason = safeReason(reason);
    }

    /**
     * Returns a sanitized, machine-readable reason code.
     */
    public String reason() {
        return reason;
    }

    private static String safeReason(String reason) {
        if (reason == null || !reason.matches("[A-Z0-9_]{1,60}")) {
            return "DELIVERY";
        }
        return reason;
    }
}
