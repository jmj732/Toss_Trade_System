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
import com.jmj.trade.prediction.PredictionEvaluationLease;
import com.jmj.trade.prediction.PredictionEvaluationMetrics;
import com.jmj.trade.prediction.PredictionEvaluationScheduler;
import com.jmj.trade.prediction.PredictionIngestionApiKeyAuthenticationFilter;
import com.jmj.trade.prediction.PredictionIngestionApiKeyMetrics;
import com.jmj.trade.prediction.PredictionIngestionApiKeyRateLimiter;
import com.jmj.trade.prediction.PredictionIngestionApiKeyService;
import com.jmj.trade.prediction.PredictionModelRegistryService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
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
    PredictionModelRegistryService predictionModelRegistryService(JdbcTemplate jdbcTemplate) {
        return new PredictionModelRegistryService(jdbcTemplate);
    }

    @Bean
    PredictionIngestionApiKeyService predictionIngestionApiKeyService(
            JdbcTemplate jdbcTemplate,
            PredictionModelRegistryService registry,
            PlatformTransactionManager transactionManager,
            SecureRandom credentialSecureRandom
    ) {
        return new PredictionIngestionApiKeyService(
                jdbcTemplate, registry, new TransactionTemplate(transactionManager),
                credentialSecureRandom, Clock.systemUTC());
    }

    @Bean
    PredictionIngestionApiKeyRateLimiter predictionIngestionApiKeyRateLimiter(
            StringRedisTemplate redis,
            @Value("${prediction.ingestion-api-key.rate-limit.limit:60}") int limit,
            @Value("${prediction.ingestion-api-key.rate-limit.window:PT1M}") Duration window
    ) {
        return new PredictionIngestionApiKeyRateLimiter(redis, limit, window);
    }

    @Bean
    PredictionIngestionApiKeyAuthenticationFilter predictionIngestionApiKeyAuthenticationFilter(
            PredictionIngestionApiKeyService apiKeys,
            PredictionIngestionApiKeyRateLimiter rateLimiter,
            PredictionIngestionApiKeyMetrics metrics
    ) {
        return new PredictionIngestionApiKeyAuthenticationFilter(apiKeys, rateLimiter, metrics);
    }

    @Bean
    AnalysisPredictionService analysisPredictionService(
            JdbcTemplate jdbcTemplate,
            BrokerAdapter brokerAdapter,
            PredictionModelRegistryService registry,
            PlatformTransactionManager transactionManager
    ) {
        return new AnalysisPredictionService(
                jdbcTemplate, brokerAdapter, registry, new TransactionTemplate(transactionManager));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "prediction.evaluation", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class PredictionEvaluationSchedulingConfiguration {

        @Bean
        PredictionEvaluationLease predictionEvaluationLease(
                JdbcTemplate jdbcTemplate,
                @Value("${prediction.evaluation.lock-ttl:PT10M}") Duration lockTtl
        ) {
            return new PredictionEvaluationLease(jdbcTemplate, lockTtl);
        }

        @Bean
        PredictionEvaluationScheduler predictionEvaluationScheduler(
                PredictionEvaluationLease lease,
                AnalysisPredictionService predictions,
                PredictionEvaluationMetrics metrics,
                @Value("${prediction.evaluation.batch-size:100}") int batchSize,
                @Value("${prediction.evaluation.max-per-tick:1000}") int maxPerTick,
                @Value("${prediction.evaluation.max-runtime:PT5M}") Duration maxRuntime
        ) {
            return new PredictionEvaluationScheduler(
                    lease, predictions, metrics, batchSize, maxPerTick, maxRuntime);
        }
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
