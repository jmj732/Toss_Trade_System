package com.jmj.trade.notification;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramApprovalSettingsTest {

    private static final UUID USER = UUID.fromString("11990000-0000-7000-8000-0000000000e1");
    private static final String TOKEN = "123456:TEST_ONLY_TOKEN";

    @Test
    void readyOnlyWhenEveryFlagAndNumericIdIsPresent() {
        assertThat(settings(true, true, TOKEN, "-100123", "42", USER, "").isReady()).isTrue();
        assertThat(settings(false, true, TOKEN, "-100123", "42", USER, "").isReady()).isFalse();
        assertThat(settings(true, false, TOKEN, "-100123", "42", USER, "").isReady()).isFalse();
        assertThat(settings(true, true, "", "-100123", "42", USER, "").isReady()).isFalse();
        assertThat(settings(true, true, TOKEN, "@channel_name", "42", USER, "").isReady()).isFalse();
        assertThat(settings(true, true, TOKEN, "-100123", "abc", USER, "").isReady()).isFalse();
        assertThat(settings(true, true, TOKEN, "-100123", "-42", USER, "").isReady()).isFalse();
        assertThat(settings(true, true, TOKEN, "-100123", "99999999999999999999", USER, "").isReady()).isFalse();
        assertThat(settings(true, true, TOKEN, "-100123", "42", null, "").isReady()).isFalse();
    }

    @Test
    void derivedSecretIsHmacOfBotTokenAndExplicitSecretWins() throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var expected = HexFormat.of().formatHex(mac.doFinal("telegram-webhook-v1".getBytes(StandardCharsets.UTF_8)));

        var derived = settings(true, true, TOKEN, "1", "42", USER, "");
        assertThat(TelegramApprovalSettings.deriveWebhookSecret(TOKEN)).isEqualTo(expected).hasSize(64);
        assertThat(derived.webhookSecretMatches(expected)).isTrue();
        assertThat(derived.webhookSecretMatches(expected.substring(1))).isFalse();

        var explicit = settings(true, true, TOKEN, "1", "42", USER, "explicit_Secret-1");
        assertThat(explicit.webhookSecretMatches("explicit_Secret-1")).isTrue();
        assertThat(explicit.webhookSecretMatches(expected)).isFalse();
    }

    @Test
    void malformedExplicitSecretOrMissingTokenRejectsEverything() {
        var malformed = settings(true, true, TOKEN, "1", "42", USER, "bad secret!");
        assertThat(malformed.hasUsableWebhookSecret()).isFalse();
        assertThat(malformed.webhookSecretMatches("bad secret!")).isFalse();
        var noToken = settings(true, true, "", "1", "42", USER, "");
        assertThat(noToken.hasUsableWebhookSecret()).isFalse();
        assertThat(noToken.webhookSecretMatches("")).isFalse();
    }

    @Test
    void authorizesOnlyConfiguredChatAndApproverAndNeverPrintsSecrets() {
        var settings = settings(true, true, TOKEN, "-100123", "42", USER, "explicit_secret");
        assertThat(settings.isAuthorizedActor(-100123L, 42L)).isTrue();
        assertThat(settings.isAuthorizedActor(-100123L, 43L)).isFalse();
        assertThat(settings.isAuthorizedActor(100123L, 42L)).isFalse();
        assertThat(settings.isTargetUser(USER)).isTrue();
        assertThat(settings.isTargetUser(UUID.randomUUID())).isFalse();
        assertThat(settings.toString()).doesNotContain(TOKEN).doesNotContain("explicit_secret");
    }

    private static TelegramApprovalSettings settings(boolean telegram, boolean approval, String token, String chat,
                                                     String approver, UUID user, String secret) {
        return TelegramApprovalSettings.of(telegram, approval, token, chat, approver, user, secret);
    }
}
