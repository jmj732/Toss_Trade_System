package com.jmj.trade.monitoring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "monitoring.scheduler", name = "enabled", havingValue = "true")
@EnableScheduling
class MonitoringSchedulerConfiguration {

    @Bean
    MonitoringScheduler monitoringScheduler(MonitoringCoordinator coordinator) {
        return new MonitoringScheduler(coordinator);
    }
}
