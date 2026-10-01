package com.jmj.trade.investment;

import com.jmj.trade.account.AccountSyncService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "investment.data.scheduler", name = "enabled", havingValue = "true")
@EnableScheduling
class InvestmentDataSchedulerConfiguration {

    @Bean
    InvestmentDataScheduler investmentDataScheduler(
            JdbcTemplate jdbc,
            InvestmentContextService investment,
            ObjectProvider<AccountSyncService> accountSync,
            org.springframework.core.env.Environment environment
    ) {
        return new InvestmentDataScheduler(jdbc, investment, accountSync,
                environment.getProperty("investment.data.time-zone", "America/New_York"));
    }
}
