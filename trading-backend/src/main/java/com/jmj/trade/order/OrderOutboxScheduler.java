package com.jmj.trade.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Objects;

/**
 * Drives every configured {@link OrderOutboxProcessor} (one per order outbox table) on a fixed
 * delay. A failure sweeping one processor is logged and does not stop the others.
 */
final class OrderOutboxScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OrderOutboxScheduler.class);

    private final List<OrderOutboxProcessor> processors;
    private final int batchSize;

    OrderOutboxScheduler(List<OrderOutboxProcessor> processors, int batchSize) {
        this.processors = List.copyOf(Objects.requireNonNull(processors, "processors"));
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${order.outbox.interval:PT2S}",
            initialDelayString = "${order.outbox.initial-delay:PT2S}")
    void sweep() {
        for (var processor : processors) {
            try {
                processor.process(batchSize);
            } catch (RuntimeException exception) {
                LOG.atWarn()
                        .addKeyValue("operation", "order_outbox_sweep")
                        .addKeyValue("outcome", "failure")
                        .addKeyValue("error_type", exception.getClass().getSimpleName())
                        .log("order outbox sweep could not run");
            }
        }
    }
}
