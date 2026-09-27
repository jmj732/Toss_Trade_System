package com.jmj.trade.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

final class MonitoringScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(MonitoringScheduler.class);
    private final MonitoringCoordinator coordinator;

    MonitoringScheduler(MonitoringCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Scheduled(fixedDelayString = "${monitoring.interval:PT15M}",
            initialDelayString = "${monitoring.initial-delay:PT1M}")
    void run() {
        try {
            coordinator.runCycle();
        } catch (RuntimeException exception) {
            LOG.atWarn()
                    .addKeyValue("operation", "monitoring_scheduler")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("monitoring cycle did not complete");
        }
    }
}
