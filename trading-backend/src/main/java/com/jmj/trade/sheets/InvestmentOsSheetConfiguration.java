package com.jmj.trade.sheets;

import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.connector.ConnectorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class InvestmentOsSheetConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "investment-os.sheet", name = "enabled", havingValue = "true")
    @EnableConfigurationProperties(InvestmentOsSheetProperties.class)
    static class PropertiesConfiguration {
    }

    @Bean
    @ConditionalOnProperty(prefix = "investment-os.sheet", name = "enabled", havingValue = "true")
    GoogleSheetsClient googleSheetsClient(
            @Value("${investment-os.sheet.service-account-json:}") String serviceAccountJson
    ) {
        return new GoogleSheetsClient(serviceAccountJson);
    }

    @Bean
    @ConditionalOnProperty(prefix = "investment-os.sheet", name = "enabled", havingValue = "true")
    InvestmentOsSheetLease investmentOsSheetLease(
            JdbcTemplate jdbcTemplate, InvestmentOsSheetProperties properties
    ) {
        return new InvestmentOsSheetLease(jdbcTemplate, properties.lockTtl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "investment-os.sheet", name = "enabled", havingValue = "true")
    InvestmentOsSheetSyncService investmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            AccountSyncService accountSyncService,
            ConnectorService connectorService,
            GoogleSheetsClient googleSheetsClient
    ) {
        return new InvestmentOsSheetSyncService(properties, lease, accountSyncService, connectorService,
                googleSheetsClient, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "investment-os.sheet", name = "enabled", havingValue = "true")
    InvestmentOsSheetController investmentOsSheetController(InvestmentOsSheetSyncService service) {
        return new InvestmentOsSheetController(service);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "investment-os.sheet", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class SchedulingConfiguration {
        @Bean
        InvestmentOsSheetScheduler investmentOsSheetScheduler(InvestmentOsSheetSyncService service) {
            return new InvestmentOsSheetScheduler(service);
        }
    }
}
