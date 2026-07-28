package com.jmj.trade.refresh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

final class ScheduledPortfolioRefreshScheduler {

    private static final Logger LOG =
            LoggerFactory.getLogger(ScheduledPortfolioRefreshScheduler.class);

    private final ScheduledPortfolioRefreshService service;

    ScheduledPortfolioRefreshScheduler(ScheduledPortfolioRefreshService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(
            fixedDelayString = "${portfolio.refresh.interval:PT15M}",
            initialDelayString = "${portfolio.refresh.initial-delay:PT1M}")
    void refresh() {
        try {
            service.refresh();
        } catch (RuntimeException exception) {
            LOG.atWarn()
                    .addKeyValue("operation", "scheduled_refresh")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("scheduled refresh sweep could not run");
        }
    }
}
