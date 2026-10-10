package com.jmj.trade.notification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runtime readiness of the Telegram thesis approval workflow. Always registered as a bean; the webhook and
 * request creation check {@link #isReady()} at runtime so the bean inventory does not depend on configuration.
 *
 * <p>Ready iff TELEGRAM_ENABLED, TELEGRAM_APPROVAL_ENABLED, a bot token, a <em>numeric</em> chat id
 * ({@code @username} is rejected), a target user id and a numeric TELEGRAM_APPROVER_ID are all present.
 * Invalid ids never fail startup; they simply leave the workflow not ready.
 *
 * <p>Webhook secret: TELEGRAM_WEBHOOK_SECRET when set (must match {@code [A-Za-z0-9_-]{1,256}}, otherwise every
 * webhook call is rejected); else hex(HMAC-SHA256(key=botToken, msg="telegram-webhook-v1")).
 * {@link #toString()} never prints the token or the secret.
 */
public final class TelegramApprovalSettings {

    static final String DERIVATION_MESSAGE = "telegram-webhook-v1";
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{1,256}");
    private static final Pattern NUMERIC_ID = Pattern.compile("-?[0-9]{1,19}");

    private final boolean ready;
    private final Long chatId;
    private final Long approverId;
    private final UUID targetUserId;
    private final byte[] expectedSecret;

    private TelegramApprovalSettings(boolean ready, Long chatId, Long approverId, UUID targetUserId,
                                     byte[] expectedSecret) {
        this.ready = ready;
        this.chatId = chatId;
        this.approverId = approverId;
        this.targetUserId = targetUserId;
        this.expectedSecret = expectedSecret;
    }

    public static TelegramApprovalSettings of(boolean telegramEnabled, boolean approvalEnabled, String botToken,
                                              String chatId, String approverId, UUID targetUserId,
                                              String webhookSecret) {
        var token = Objects.requireNonNullElse(botToken, "").trim();
        var chat = numericId(chatId);
        var approver = numericId(approverId);
        var ready = telegramEnabled && approvalEnabled && !token.isEmpty()
                && chat != null && approver != null && approver > 0 && targetUserId != null;
        var explicit = Objects.requireNonNullElse(webhookSecret, "").trim();
        String expected;
        if (!explicit.isEmpty()) {
            expected = SECRET.matcher(explicit).matches() ? explicit : null;
        } else {
            expected = token.isEmpty() ? null : deriveWebhookSecret(token);
        }
        return new TelegramApprovalSettings(ready, chat, approver, targetUserId,
                expected == null ? null : expected.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isReady() {
        return ready;
    }

    /** False when an explicit secret is malformed or no secret can be derived: every webhook call is rejected. */
    public boolean hasUsableWebhookSecret() {
        return expectedSecret != null;
    }

    /** Constant-time comparison of the X-Telegram-Bot-Api-Secret-Token header value. */
    public boolean webhookSecretMatches(String presented) {
        if (expectedSecret == null || presented == null) return false;
        return MessageDigest.isEqual(expectedSecret, presented.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isAuthorizedActor(long chat, long from) {
        return ready && chatId == chat && approverId == from;
    }

    public boolean isTargetUser(UUID userId) {
        return ready && targetUserId != null && targetUserId.equals(userId);
    }

    public UUID targetUserId() {
        return targetUserId;
    }

    /** hex(HMAC-SHA256(key=botToken, msg="telegram-webhook-v1")): 64 lowercase hex chars. */
    public static String deriveWebhookSecret(String botToken) {
        var token = Objects.requireNonNullElse(botToken, "").trim();
        if (token.isEmpty()) return null;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(DERIVATION_MESSAGE.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
    }

    private static Long numericId(String value) {
        var trimmed = Objects.requireNonNullElse(value, "").trim();
        if (!NUMERIC_ID.matcher(trimmed).matches()) return null;
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "TelegramApprovalSettings[ready=" + ready + ", webhookSecretUsable=" + (expectedSecret != null) + "]";
    }
}
