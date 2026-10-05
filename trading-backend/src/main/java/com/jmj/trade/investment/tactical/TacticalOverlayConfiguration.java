package com.jmj.trade.investment.tactical;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TacticalOverlayProperties.class)
public class TacticalOverlayConfiguration {

    @Bean
    TacticalOverlayCalculator tacticalOverlayCalculator(TacticalOverlayProperties properties) {
        return new TacticalOverlayCalculator(properties);
    }

    @Bean
    TacticalOverlayAggregationCalculator tacticalOverlayAggregationCalculator() {
        return new TacticalOverlayAggregationCalculator();
    }
}
