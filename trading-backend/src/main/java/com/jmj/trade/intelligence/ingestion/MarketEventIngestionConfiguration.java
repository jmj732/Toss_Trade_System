package com.jmj.trade.intelligence.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketEventIngestionProperties.class)
public class MarketEventIngestionConfiguration {

    @Bean
    MarketEventProviderRegistry marketEventProviderRegistry(
            MarketEventIngestionProperties properties,
            ObjectMapper objectMapper
    ) {
        var providers = new ArrayList<MarketEventProvider>();
        properties.providers().forEach((name, configuration) -> {
            var id = MarketEventProviderId.parse(name);
            if (configuration.enabled()) {
                providers.add(new ConfiguredMarketEventProvider(id, configuration, objectMapper));
            }
        });
        return new MarketEventProviderRegistry(providers);
    }

    @Bean
    MarketEventIngestionLease marketEventIngestionLease(
            JdbcTemplate jdbc,
            MarketEventIngestionProperties properties
    ) {
        return new MarketEventIngestionLease(jdbc, properties.leaseTtl());
    }

    @Bean
    MarketEventIngestionService marketEventIngestionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            MarketEventProviderRegistry providers,
            MarketEventIngestionLease lease,
            com.jmj.trade.intelligence.EventIntelligenceService events,
            MarketEventIngestionProperties properties
    ) {
        return new MarketEventIngestionService(
                jdbc, transactionManager, providers, lease, events, properties);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "market-events.scheduler", name = "enabled",
            havingValue = "true")
    @EnableScheduling
    static class SchedulingConfiguration {

        @Bean
        MarketEventIngestionScheduler marketEventIngestionScheduler(
                MarketEventIngestionService service,
                MarketEventIngestionProperties properties
        ) {
            return new MarketEventIngestionScheduler(service, properties.interval(),
                    properties.initialDelay());
        }
    }
}
