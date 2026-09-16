package com.jmj.trade.sheets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

final class InvestmentOsSheetScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(InvestmentOsSheetScheduler.class);
    private final InvestmentOsSheetSyncService service;

    InvestmentOsSheetScheduler(InvestmentOsSheetSyncService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(
            fixedDelayString = "${investment-os.sheet.interval:PT5M}",
            initialDelayString = "${investment-os.sheet.initial-delay:PT1M}")
    void sync() {
        try {
            service.sync();
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", "investment_os_sheet_sync")
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("investment os sheet scheduler could not run");
        }
    }
}
