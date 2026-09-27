package com.jmj.trade.notification;

import java.util.Objects;
import java.util.UUID;

record TelegramDeliverySettings(boolean enabled, String botToken, String chatId, UUID targetUserId) {

    TelegramDeliverySettings {
        botToken = Objects.requireNonNullElse(botToken, "").trim();
        chatId = Objects.requireNonNullElse(chatId, "").trim();
    }

    boolean isConfiguredFor(UUID userId) {
        return isReady() && targetUserId.equals(userId);
    }

    boolean isReady() {
        return enabled && targetUserId != null && !botToken.isBlank() && !chatId.isBlank();
    }
}
