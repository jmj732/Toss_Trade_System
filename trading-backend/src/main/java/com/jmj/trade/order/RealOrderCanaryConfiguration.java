package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealOrderCanaryProperties.class)
public class RealOrderCanaryConfiguration {

    @Bean
    RealOrderCanaryAuditLedger realOrderCanaryAuditLedger(JdbcTemplate jdbc) {
        return new RealOrderCanaryAuditLedger(jdbc);
    }

    @Bean
    RealOrderCanaryService realOrderCanaryService(
            ObjectProvider<BrokerAdapter> quotes,
            ObjectProvider<com.jmj.trade.broker.toss.TossCredentialProvider> credentials,
            ObjectProvider<LiveOrderActivationService> activation,
            ObjectProvider<LiveOrderSafetyLedger> safety,
            ObjectProvider<KillSwitchStateReader> killSwitches,
            @Qualifier("tossOrderPort") ObjectProvider<com.jmj.trade.broker.BrokerOrderPort> orders,
            ObjectProvider<OrderApprovalStepUpService> stepUps,
            ObjectProvider<OrderSubmissionService> submissions,
            ObjectProvider<BrokerOrderRepository> brokerOrderRepository,
            ObjectProvider<UnknownAttemptReconciler> reconciler,
            JdbcTemplate jdbc,
            RealOrderCanaryAuditLedger audit,
            RealOrderCanaryProperties properties
    ) {
        return new RealOrderCanaryService(
                quotes, credentials, activation, safety, killSwitches, orders, stepUps,
                submissions, brokerOrderRepository, reconciler, jdbc, audit, properties,
                Clock.systemUTC());
    }
}
