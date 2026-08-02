package com.jmj.trade.order;

import com.jmj.trade.inbox.InboxLedger;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

/**
 * Wires the order outbox relay. Every bean lives under the opt-in condition
 * {@code order.outbox.enabled=true} (no {@code matchIfMissing}), mirroring the repository's other
 * scheduled features, so a plain deployment neither registers the relay nor schedules it. The
 * relay covers both order outbox tables: the intent stream fans out terminal transitions to
 * notifications, while the submission stream currently has no subscriber and is simply published.
 */
@Configuration(proxyBeanMethods = false)
public class OrderOutboxConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "order.outbox", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class RelayConfiguration {

        @Bean
        OrderIntentNotificationHandler orderIntentNotificationHandler(
                JdbcTemplate jdbcTemplate,
                NotificationOutboxWriter notificationOutboxWriter
        ) {
            return new OrderIntentNotificationHandler(jdbcTemplate, notificationOutboxWriter);
        }

        @Bean
        OrderOutboxProcessor orderIntentOutboxProcessor(
                JdbcTemplate jdbcTemplate,
                PlatformTransactionManager transactionManager,
                InboxLedger inboxLedger,
                OrderIntentNotificationHandler orderIntentNotificationHandler,
                @Value("${order.outbox.max-attempts:5}") int maxAttempts
        ) {
            return new OrderOutboxProcessor(
                    jdbcTemplate,
                    transactionManager,
                    inboxLedger,
                    "order_intent_outbox_events",
                    Map.of("OrderIntentStatusChanged", List.of(orderIntentNotificationHandler)),
                    maxAttempts);
        }

        @Bean
        OrderOutboxProcessor orderSubmissionOutboxProcessor(
                JdbcTemplate jdbcTemplate,
                PlatformTransactionManager transactionManager,
                InboxLedger inboxLedger,
                @Value("${order.outbox.max-attempts:5}") int maxAttempts
        ) {
            return new OrderOutboxProcessor(
                    jdbcTemplate,
                    transactionManager,
                    inboxLedger,
                    "order_submission_outbox_events",
                    Map.of(),
                    maxAttempts);
        }

        @Bean
        OrderOutboxScheduler orderOutboxScheduler(
                List<OrderOutboxProcessor> processors,
                @Value("${order.outbox.batch-size:50}") int batchSize
        ) {
            return new OrderOutboxScheduler(processors, batchSize);
        }
    }
}
