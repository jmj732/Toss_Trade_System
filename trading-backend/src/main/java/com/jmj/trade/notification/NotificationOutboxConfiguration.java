package com.jmj.trade.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class NotificationOutboxConfiguration {

    @Bean
    NotificationOutboxProcessor notificationOutboxProcessor(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        return new NotificationOutboxProcessor(jdbcTemplate, transactionManager, objectMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "notification.outbox", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class SchedulingConfiguration {

        @Bean
        NotificationOutboxScheduler notificationOutboxScheduler(
                NotificationOutboxProcessor notificationOutboxProcessor,
                @Value("${notification.outbox.batch-size:50}") int batchSize
        ) {
            return new NotificationOutboxScheduler(notificationOutboxProcessor, batchSize);
        }
    }
}
