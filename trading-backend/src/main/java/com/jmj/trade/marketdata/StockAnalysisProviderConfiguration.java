package com.jmj.trade.marketdata;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;

@Configuration
@EnableConfigurationProperties(StockAnalysisProviderProperties.class)
public class StockAnalysisProviderConfiguration {

    @Bean
    StockDataProviderRegistry stockDataProviderRegistry(
            StockAnalysisProviderProperties properties,
            ObjectMapper objectMapper
    ) {
        var providers = new ArrayList<StockDataProvider>();
        properties.providers().forEach((name, configuration) -> {
            var id = StockDataProviderId.parse(name);
            if (configuration.enabled()) {
                providers.add(new ConfiguredStockDataProvider(id, configuration, objectMapper));
            }
        });
        return new StockDataProviderRegistry(providers);
    }
}
