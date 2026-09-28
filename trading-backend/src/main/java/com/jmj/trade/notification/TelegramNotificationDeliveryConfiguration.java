package com.jmj.trade.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
class TelegramNotificationDeliveryConfiguration {

    @Bean
    TelegramDeliverySettings telegramDeliverySettings(
            @Value("${notification.telegram.enabled:false}") boolean enabled,
            @Value("${notification.telegram.bot-token:}") String botToken,
            @Value("${notification.telegram.chat-id:}") String chatId,
            @Value("${notification.telegram.user-id:}") String userId
    ) {
        var targetUserId = userId == null || userId.isBlank()
                ? null : UUID.fromString(userId.trim());
        return new TelegramDeliverySettings(enabled, botToken, chatId, targetUserId);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "notification.telegram", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class SchedulingConfiguration {

        @Bean
        TelegramMessageSender telegramMessageSender(TelegramDeliverySettings settings) {
            return new TelegramBotApiClient(settings.botToken(), settings.chatId());
        }

        @Bean
        TelegramNotificationDeliveryProcessor telegramNotificationDeliveryProcessor(
                JdbcTemplate jdbcTemplate,
                PlatformTransactionManager transactionManager,
                TelegramDeliverySettings settings,
                TelegramMessageSender sender
        ) {
            return new TelegramNotificationDeliveryProcessor(
                    jdbcTemplate, transactionManager, settings, sender);
        }

        @Bean
        TelegramNotificationDeliveryScheduler telegramNotificationDeliveryScheduler(
                TelegramNotificationDeliveryProcessor processor,
                @Value("${notification.telegram.batch-size:1}") int batchSize
        ) {
            return new TelegramNotificationDeliveryScheduler(processor, batchSize);
        }
    }
}
