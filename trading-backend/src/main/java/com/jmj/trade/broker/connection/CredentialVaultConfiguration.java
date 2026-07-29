package com.jmj.trade.broker.connection;

import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.account.AccountSyncTransactions;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import com.jmj.trade.notification.NotificationOutboxWriter;
import com.jmj.trade.order.OrderIntentRepository;
import com.jmj.trade.order.OrderIntentTransitionService;
import com.jmj.trade.order.PaperOrderWorkflowService;
import com.jmj.trade.order.PaperTradingBroker;
import com.jmj.trade.order.PreTradeRiskEngine;
import com.jmj.trade.prediction.AnalysisPredictionService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CredentialVaultProperties.class)
public class CredentialVaultConfiguration {

    @Bean
    SecureRandom credentialSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    CredentialKeyring credentialKeyring(CredentialVaultProperties properties) {
        return new CredentialKeyring(properties);
    }

    @Bean
    CredentialCipher credentialCipher(CredentialKeyring keyring, SecureRandom credentialSecureRandom) {
        return new CredentialCipher(keyring, credentialSecureRandom);
    }

    @Bean
    BrokerConnectionService brokerConnectionService(
            UserAnchorRepository userAnchorRepository,
            BrokerConnectionRepository connectionRepository,
            CredentialCipher credentialCipher
    ) {
        return new BrokerConnectionService(userAnchorRepository, connectionRepository, credentialCipher);
    }

    @Bean
    BrokerConnectionTransactions brokerConnectionTransactions(BrokerConnectionRepository repository) {
        return new BrokerConnectionTransactions(repository);
    }

    @Bean
    TossCredentialProvider tossCredentialProvider(BrokerConnectionRepository repository, CredentialCipher cipher) {
        return new DatabaseTossCredentialProvider(repository, cipher);
    }

    @Bean
    BrokerConnectionValidationService brokerConnectionValidationService(
            BrokerConnectionTransactions transactions,
            BrokerAdapter brokerAdapter
    ) {
        return new BrokerConnectionValidationService(transactions, brokerAdapter);
    }

    @Bean
    AccountSyncTransactions accountSyncTransactions(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${portfolio.sync.stale-after:PT15M}") Duration staleAfter,
            NotificationOutboxWriter notificationOutboxWriter
    ) {
        return new AccountSyncTransactions(
                jdbcTemplate,
                new TransactionTemplate(transactionManager),
                staleAfter,
                notificationOutboxWriter);
    }

    @Bean
    AccountSyncService accountSyncService(
            AccountSyncTransactions transactions,
            BrokerAdapter brokerAdapter
    ) {
        return new AccountSyncService(transactions, brokerAdapter);
    }

    @Bean
    PreTradeRiskEngine preTradeRiskEngine(
            PortfolioReadService portfolioReadService,
            PaperTradingBroker paperTradingBroker,
            OrderIntentRepository orderIntentRepository,
            OrderIntentTransitionService transitionService,
            JdbcTemplate jdbcTemplate,
            RiskPolicyService riskPolicyService
    ) {
        return new PreTradeRiskEngine(
                portfolioReadService,
                paperTradingBroker,
                orderIntentRepository,
                transitionService,
                jdbcTemplate,
                riskPolicyService);
    }

    @Bean
    AnalysisPredictionService analysisPredictionService(JdbcTemplate jdbcTemplate, BrokerAdapter brokerAdapter) {
        return new AnalysisPredictionService(jdbcTemplate, brokerAdapter);
    }

    @Bean
    PaperOrderWorkflowService paperOrderWorkflowService(
            BrokerAdapter brokerAdapter,
            OrderIntentRepository orderIntentRepository,
            OrderIntentTransitionService transitionService,
            PreTradeRiskEngine preTradeRiskEngine,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new PaperOrderWorkflowService(
                brokerAdapter,
                orderIntentRepository,
                transitionService,
                preTradeRiskEngine,
                jdbcTemplate,
                transactionManager);
    }
}
