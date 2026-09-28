package com.jmj.trade.notification;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramDeliverySettingsTest {

    @Test
    void requiresExplicitEnablementAndAllowlistedUser() {
        var target = UUID.randomUUID();

        assertThat(new TelegramDeliverySettings(true, "token", "chat", null).isReady()).isFalse();
        assertThat(new TelegramDeliverySettings(false, "token", "chat", target).isReady()).isFalse();
        assertThat(new TelegramDeliverySettings(true, "", "chat", target).isReady()).isFalse();
        assertThat(new TelegramDeliverySettings(true, "token", "", target).isReady()).isFalse();
        var ready = new TelegramDeliverySettings(true, "token", "chat", target);
        assertThat(ready.isConfiguredFor(target)).isTrue();
        assertThat(ready.isConfiguredFor(UUID.randomUUID())).isFalse();
    }
}
