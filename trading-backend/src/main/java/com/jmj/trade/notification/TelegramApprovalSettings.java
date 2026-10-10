package com.jmj.trade.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * Settings for the Telegram thesis approval workflow.
 * Includes validation of numeric chat IDs and approver user IDs, plus webhook secret management.
 */
public record TelegramApprovalSettings(
    boolean enabled,
    Long chatId,
    Long approverId,
    UUID targetUserId,
    String webhookSecret
) {

    public TelegramApprovalSettings {
        // Normalize empty strings to null for secret
        if (webhookSecret != null && webhookSecret.isBlank()) {
            webhookSecret = null;
        }
    }

    /**
     * Returns true if approval is fully enabled and all required fields are configured.
     */
    public boolean isReady() {
        return enabled && chatId != null && approverId != null && targetUserId != null;
    }

    /**
     * Returns true if the webhook secret is properly configured.
     * Either an explicit secret is set, or both botToken and approval enabled.
     */
    public boolean isWebhookSecretConfigured(String botToken) {
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            return webhookSecret.matches("^[A-Za-z0-9_-]{1,256}$");
        }
        // Derived secret via HMAC
        return botToken != null && !botToken.isBlank() && enabled;
    }

    /**
     * Derives the webhook secret from the bot token using HMAC-SHA256.
     * Called when no explicit secret is configured.
     * Formula: hex(HMAC-SHA256(key=botToken, msg="telegram-webhook-v1"))
     */
    public static String deriveWebhookSecret(String botToken) {
        if (botToken == null || botToken.isBlank()) {
            return null;
        }
        try {
            var key = botToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var msg = "telegram-webhook-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, 0, key.length, "HmacSHA256"));
            var result = mac.doFinal(msg);
            var hex = new StringBuilder();
            for (byte b : result) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive webhook secret", e);
        }
    }
}
