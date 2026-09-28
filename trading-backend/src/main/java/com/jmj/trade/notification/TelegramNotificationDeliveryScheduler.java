package com.jmj.trade.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

final class TelegramNotificationDeliveryScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramNotificationDeliveryScheduler.class);

    private final TelegramNotificationDeliveryProcessor processor;
    private final int batchSize;

    TelegramNotificationDeliveryScheduler(TelegramNotificationDeliveryProcessor processor, int batchSize) {
        this.processor = Objects.requireNonNull(processor, "processor");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${notification.telegram.interval:PT15S}",
            initialDelayString = "${notification.telegram.initial-delay:PT10S}")
    void sweep() {
        try {
            processor.process(batchSize);
        } catch (RuntimeException exception) {
            LOG.atWarn()
                    .addKeyValue("operation", "telegram_monitoring_delivery_sweep")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("monitoring alert Telegram delivery sweep could not run");
        }
    }
}
