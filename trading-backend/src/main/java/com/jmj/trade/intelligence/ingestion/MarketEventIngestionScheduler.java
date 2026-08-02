package com.jmj.trade.intelligence.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Objects;

final class MarketEventIngestionScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(MarketEventIngestionScheduler.class);

    private final MarketEventIngestionService service;

    MarketEventIngestionScheduler(
            MarketEventIngestionService service,
            Duration interval,
            Duration initialDelay
    ) {
        this.service = Objects.requireNonNull(service, "service");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(initialDelay, "initialDelay");
    }

    @Scheduled(
            fixedDelayString = "${market-events.interval:PT15M}",
            initialDelayString = "${market-events.initial-delay:PT1M}")
    void collect() {
        try {
            service.collect();
        } catch (RuntimeException exception) {
            LOG.atWarn()
                    .addKeyValue("operation", "market_event_ingestion")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("market event ingestion sweep could not run");
        }
    }
}
