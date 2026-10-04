package com.jmj.trade.marketdata;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(StockAnalysisProviderProperties.class)
public class StockAnalysisProviderConfiguration {

    @Bean
    AlphaVantageDailyRequestCache alphaVantageDailyRequestCache(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        return new AlphaVantageDailyRequestCache(
                jdbcTemplate, transactionManager, objectMapper, Clock.systemUTC(), 25);
    }

    @Bean
    StockDataProviderRegistry stockDataProviderRegistry(
            StockAnalysisProviderProperties properties,
            ObjectMapper objectMapper,
            AlphaVantageDailyRequestCache alphaVantageDailyRequestCache,
            @Value("${ALPHA_VANTAGE_ADDITIONAL_API_KEYS:}") String additionalApiKeys
    ) {
        var providers = new ArrayList<StockDataProvider>();
        properties.providers().forEach((name, configuration) -> {
            var id = StockDataProviderId.parse(name);
            if (configuration.enabled()) {
                if (id == StockDataProviderId.ALPHA_VANTAGE) {
                    providers.add(new AlphaVantageEarningsEstimatesProvider(
                            configuration, objectMapper, Clock.systemUTC(), alphaVantageDailyRequestCache,
                            additionalApiKeys));
                } else if (id == StockDataProviderId.SEC && configuration.fields().isEmpty()
                        && configuration.endpoints().isEmpty()) {
                    providers.add(new SecCompanyFactsProvider(configuration, objectMapper));
                } else {
                    providers.add(new ConfiguredStockDataProvider(id, configuration, objectMapper));
                }
            }
        });
        return new StockDataProviderRegistry(providers);
    }

    StockDataProviderRegistry stockDataProviderRegistry(
            StockAnalysisProviderProperties properties,
            ObjectMapper objectMapper,
            AlphaVantageDailyRequestCache alphaVantageDailyRequestCache
    ) {
        return stockDataProviderRegistry(properties, objectMapper, alphaVantageDailyRequestCache, "");
    }
}
