package com.jmj.trade.broker.connection;

import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.account.AccountSyncTransactions;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import com.jmj.trade.notification.NotificationOutboxWriter;
import com.jmj.trade.order.OrderApprovalStepUpService;
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
import com.jmj.trade.prediction.PredictionIngestionApiKeyCleanup;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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
    com.jmj.trade.order.KillSwitchLedger killSwitchLedger(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            OrderApprovalStepUpService orderApprovalStepUpService
    ) {
        return new com.jmj.trade.order.KillSwitchLedger(
                jdbcTemplate,
                new TransactionTemplate(transactionManager),
                orderApprovalStepUpService,
                Clock.systemUTC());
    }

    @Bean
    com.jmj.trade.order.ReconciliationBrokerProbe reconciliationBrokerProbe(
            PaperTradingBroker paperTradingBroker,
            @Qualifier("tossOrderPort") ObjectProvider<BrokerOrderPort> liveOrderPort
    ) {
        // 프로덕션 프로브는 주문 전송 포트(B4)를 통해 OPEN/CLOSED 를 조회한다. E6 실거래 어댑터로
        // 포트 구현이 바뀌어도 이 프로브 배선은 그대로다.
        var port = liveOrderPort.getIfAvailable();
        return new com.jmj.trade.order.BrokerOrderPortReconciliationProbe(
                port == null ? paperTradingBroker : port);
    }

    @Bean
    com.jmj.trade.order.UnknownAttemptReconciler unknownAttemptReconciler(
            com.jmj.trade.order.SubmissionAttemptRepository submissionAttemptRepository,
            com.jmj.trade.order.ReconciliationCheckRepository reconciliationCheckRepository,
            com.jmj.trade.order.OrderSubmissionService orderSubmissionService,
            com.jmj.trade.order.ReconciliationBrokerProbe reconciliationBrokerProbe,
            com.jmj.trade.order.KillSwitchLedger killSwitchLedger,
            NotificationOutboxWriter notificationOutboxWriter,
            JdbcTemplate jdbcTemplate
    ) {
        return new com.jmj.trade.order.UnknownAttemptReconciler(
                submissionAttemptRepository,
                reconciliationCheckRepository,
                orderSubmissionService,
                reconciliationBrokerProbe,
                killSwitchLedger,
                notificationOutboxWriter,
                jdbcTemplate,
                Clock.systemUTC());
    }

    @Bean
    PreTradeRiskEngine preTradeRiskEngine(
            PortfolioReadService portfolioReadService,
            PaperTradingBroker paperTradingBroker,
            OrderIntentRepository orderIntentRepository,
            OrderIntentTransitionService transitionService,
            JdbcTemplate jdbcTemplate,
            RiskPolicyService riskPolicyService,
            com.jmj.trade.order.KillSwitchLedger killSwitchLedger
    ) {
        // 제출 직전 재검증 관문(플랜 원장 B3). 순서대로 실행되며, 새 항목(2=E1, 4=B1, 11=F1,
        // 12=C6)은 이 목록에 체크를 추가하는 것만으로 확장된다. 10=E4(kill switch)는 아래에 추가됨.
        var preSubmitChecks = java.util.List.<com.jmj.trade.order.PreSubmitRevalidationCheck>of(
                new com.jmj.trade.order.AccountOwnershipRevalidationCheck(),
                new com.jmj.trade.order.SellableQuantityRevalidationCheck(),
                new com.jmj.trade.order.SameSymbolOpenOrderRevalidationCheck(),
                new com.jmj.trade.order.KillSwitchRevalidationCheck(killSwitchLedger));
        return new PreTradeRiskEngine(
                portfolioReadService,
                paperTradingBroker,
                orderIntentRepository,
                transitionService,
                jdbcTemplate,
                riskPolicyService,
                preSubmitChecks);
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
            PredictionIngestionApiKeyMetrics metrics,
            ObjectMapper objectMapper
    ) {
        return new PredictionIngestionApiKeyAuthenticationFilter(
                apiKeys, rateLimiter, metrics, objectMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "prediction.ingestion-api-key.cleanup",
            name = "enabled",
            havingValue = "true")
    @EnableScheduling
    static class PredictionIngestionApiKeyCleanupConfiguration {

        @Bean
        PredictionIngestionApiKeyCleanup predictionIngestionApiKeyCleanup(
                JdbcTemplate jdbcTemplate
        ) {
            return new PredictionIngestionApiKeyCleanup(jdbcTemplate);
        }
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
    OrderApprovalStepUpService orderApprovalStepUpService(
            JdbcTemplate jdbcTemplate,
            SecureRandom credentialSecureRandom,
            @Value("${paper-order.step-up.reauth-freshness:PT5M}") Duration reauthFreshness,
            @Value("${paper-order.step-up.token-ttl:PT2M}") Duration tokenTtl
    ) {
        return new OrderApprovalStepUpService(
                jdbcTemplate, credentialSecureRandom, Clock.systemUTC(), reauthFreshness, tokenTtl);
    }

    @Bean
    PaperOrderWorkflowService paperOrderWorkflowService(
            BrokerAdapter brokerAdapter,
            OrderIntentRepository orderIntentRepository,
            OrderIntentTransitionService transitionService,
            PreTradeRiskEngine preTradeRiskEngine,
            OrderApprovalStepUpService orderApprovalStepUpService,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${order.proposal.ttl:PT15M}") Duration proposalTtl
    ) {
        return new PaperOrderWorkflowService(
                brokerAdapter,
                orderIntentRepository,
                transitionService,
                preTradeRiskEngine,
                orderApprovalStepUpService,
                jdbcTemplate,
                transactionManager,
                proposalTtl);
    }

    @Bean
    @ConditionalOnProperty(prefix = "real-order", name = "enabled", havingValue = "true")
    com.jmj.trade.order.LiveOrderSafetyLedger liveOrderSafetyLedger(JdbcTemplate jdbcTemplate) {
        return new com.jmj.trade.order.LiveOrderSafetyLedger(jdbcTemplate, java.time.ZoneId.of("Asia/Seoul"));
    }

    @Bean
    @ConditionalOnProperty(prefix = "real-order", name = "enabled", havingValue = "true")
    com.jmj.trade.order.LiveOrderActivationService liveOrderActivationService(
            BrokerAdapter brokerAdapter,
            @Qualifier("tossOrderPort") BrokerOrderPort orderPort,
            OrderIntentRepository orderIntentRepository,
            com.jmj.trade.order.BrokerOrderRepository brokerOrderRepository,
            com.jmj.trade.order.SubmissionAttemptRepository submissionAttemptRepository,
            com.jmj.trade.order.OrderSubmissionService orderSubmissionService,
            OrderIntentTransitionService transitionService,
            PreTradeRiskEngine preTradeRiskEngine,
            OrderApprovalStepUpService orderApprovalStepUpService,
            com.jmj.trade.order.LiveOrderSafetyLedger safetyLedger,
            com.jmj.trade.order.KillSwitchStateReader killSwitchStateReader,
            PlatformTransactionManager transactionManager,
            @Value("${order.proposal.ttl:PT15M}") Duration proposalTtl
    ) {
        return new com.jmj.trade.order.LiveOrderActivationService(
                brokerAdapter, orderPort, orderIntentRepository, brokerOrderRepository, submissionAttemptRepository,
                orderSubmissionService, transitionService, preTradeRiskEngine,
                orderApprovalStepUpService, safetyLedger, killSwitchStateReader,
                new TransactionTemplate(transactionManager),
                proposalTtl);
    }
}
