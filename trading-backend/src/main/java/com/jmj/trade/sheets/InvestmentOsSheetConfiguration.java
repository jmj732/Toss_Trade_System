package com.jmj.trade.sheets;

import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.connector.ConnectorService;
import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

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
    InvestmentOsResearchSheetSync investmentOsResearchSheetSync(
            InvestmentOsSheetProperties properties,
            GoogleSheetsClient googleSheetsClient,
            InvestmentContextService investmentContextService,
            RiskPolicyService riskPolicyService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        return new InvestmentOsResearchSheetSync(properties, googleSheetsClient, investmentContextService,
                riskPolicyService, jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "investment-os.sheet", name = "enabled", havingValue = "true")
    InvestmentOsSheetSyncService investmentOsSheetSyncService(
            InvestmentOsSheetProperties properties,
            InvestmentOsSheetLease lease,
            ConnectorService connectorService,
            BrokerSurfaceService brokerSurfaceService,
            GoogleSheetsClient googleSheetsClient,
            InvestmentOsResearchSheetSync investmentOsResearchSheetSync
    ) {
        return new InvestmentOsSheetSyncService(properties, lease, connectorService,
                brokerSurfaceService, googleSheetsClient, Clock.systemUTC(), investmentOsResearchSheetSync);
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
