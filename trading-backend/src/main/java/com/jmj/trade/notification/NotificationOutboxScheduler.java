package com.jmj.trade.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

final class NotificationOutboxScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationOutboxScheduler.class);

    private final NotificationOutboxProcessor processor;
    private final int batchSize;

    NotificationOutboxScheduler(NotificationOutboxProcessor processor, int batchSize) {
        this.processor = Objects.requireNonNull(processor, "processor");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${notification.outbox.interval:PT2S}",
            initialDelayString = "${notification.outbox.initial-delay:PT2S}")
    void sweep() {
        try {
            processor.process(batchSize);
        } catch (RuntimeException exception) {
            LOG.atWarn()
                    .addKeyValue("operation", "notification_outbox_sweep")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("notification outbox sweep could not run");
        }
    }
}
