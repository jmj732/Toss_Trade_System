package com.jmj.trade.refresh;

import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.analysis.PortfolioAnalysisWorkflowService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

/**
 * Scheduled refresh depends on the credential vault, which owns {@link AccountSyncService}, so it
 * shares the same activation property.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ScheduledRefreshProperties.class)
public class ScheduledPortfolioRefreshConfiguration {

    @Bean
    ScheduledRefreshLease scheduledRefreshLease(
            JdbcTemplate jdbcTemplate,
            ScheduledRefreshProperties properties
    ) {
        return new ScheduledRefreshLease(jdbcTemplate, properties.lockTtl());
    }

    @Bean
    ScheduledPortfolioRefreshService scheduledPortfolioRefreshService(
            JdbcTemplate jdbcTemplate,
            ScheduledRefreshLease scheduledRefreshLease,
            AccountSyncService accountSyncService,
            PortfolioAnalysisWorkflowService portfolioAnalysisWorkflowService,
            MeterRegistry meterRegistry,
            ScheduledRefreshProperties properties,
            @Value("${portfolio.sync.stale-after:PT15M}") Duration syncStaleAfter
    ) {
        return new ScheduledPortfolioRefreshService(
                jdbcTemplate,
                scheduledRefreshLease,
                accountSyncService,
                portfolioAnalysisWorkflowService,
                meterRegistry,
                properties,
                syncStaleAfter);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "portfolio.refresh", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class SchedulingConfiguration {

        @Bean
        ScheduledPortfolioRefreshScheduler scheduledPortfolioRefreshScheduler(
                ScheduledPortfolioRefreshService scheduledPortfolioRefreshService
        ) {
            return new ScheduledPortfolioRefreshScheduler(scheduledPortfolioRefreshService);
        }
    }
}
