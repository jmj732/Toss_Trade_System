package com.jmj.trade.order;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.notification.NotificationOutboxWriter;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Owns the credentialed order-stack bean wiring — kill switch, reconciliation, pre-trade risk,
 * step-up, paper workflow, and the real-order gated beans. Now that this wiring lives in the
 * {@code order} package the previously inlined {@code com.jmj.trade.order.*} FQNs collapse to plain
 * type names. It shares the credential vault's activation property so the bean set stays
 * byte-identical to when this wiring lived in {@code CredentialVaultConfiguration}; a follow-up
 * delta can drop the class-level condition once the order stack no longer keys off credential
 * availability.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class CredentialedOrderStackConfiguration {

    @Bean
    KillSwitchLedger killSwitchLedger(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            OrderApprovalStepUpService orderApprovalStepUpService
    ) {
        return new KillSwitchLedger(
                jdbcTemplate,
                new TransactionTemplate(transactionManager),
                orderApprovalStepUpService,
                Clock.systemUTC());
    }

    @Bean
    ReconciliationBrokerProbe reconciliationBrokerProbe(
            PaperTradingBroker paperTradingBroker,
            @Qualifier("tossOrderPort") ObjectProvider<BrokerOrderPort> liveOrderPort
    ) {
        // 프로덕션 프로브는 주문 전송 포트(B4)를 통해 OPEN/CLOSED 를 조회한다. E6 실거래 어댑터로
        // 포트 구현이 바뀌어도 이 프로브 배선은 그대로다.
        var port = liveOrderPort.getIfAvailable();
        return new BrokerOrderPortReconciliationProbe(
                port == null ? paperTradingBroker : port);
    }

    @Bean
    UnknownAttemptReconciler unknownAttemptReconciler(
            SubmissionAttemptRepository submissionAttemptRepository,
            ReconciliationCheckRepository reconciliationCheckRepository,
            OrderSubmissionService orderSubmissionService,
            ReconciliationBrokerProbe reconciliationBrokerProbe,
            KillSwitchLedger killSwitchLedger,
            NotificationOutboxWriter notificationOutboxWriter,
            JdbcTemplate jdbcTemplate
    ) {
        return new UnknownAttemptReconciler(
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
            KillSwitchLedger killSwitchLedger
    ) {
        // 제출 직전 재검증 관문(플랜 원장 B3). 순서대로 실행되며, 새 항목(2=E1, 4=B1, 11=F1,
        // 12=C6)은 이 목록에 체크를 추가하는 것만으로 확장된다. 10=E4(kill switch)는 아래에 추가됨.
        var preSubmitChecks = List.<PreSubmitRevalidationCheck>of(
                new AccountOwnershipRevalidationCheck(),
                new SellableQuantityRevalidationCheck(),
                new SameSymbolOpenOrderRevalidationCheck(),
                new KillSwitchRevalidationCheck(killSwitchLedger));
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
    LiveOrderSafetyLedger liveOrderSafetyLedger(JdbcTemplate jdbcTemplate) {
        return new LiveOrderSafetyLedger(jdbcTemplate, java.time.ZoneId.of("Asia/Seoul"));
    }

    @Bean
    @ConditionalOnProperty(prefix = "real-order", name = "enabled", havingValue = "true")
    LiveOrderActivationService liveOrderActivationService(
            BrokerAdapter brokerAdapter,
            @Qualifier("tossOrderPort") BrokerOrderPort orderPort,
            OrderIntentRepository orderIntentRepository,
            BrokerOrderRepository brokerOrderRepository,
            SubmissionAttemptRepository submissionAttemptRepository,
            OrderSubmissionService orderSubmissionService,
            OrderIntentTransitionService transitionService,
            PreTradeRiskEngine preTradeRiskEngine,
            OrderApprovalStepUpService orderApprovalStepUpService,
            LiveOrderSafetyLedger safetyLedger,
            KillSwitchStateReader killSwitchStateReader,
            PlatformTransactionManager transactionManager,
            @Value("${order.proposal.ttl:PT15M}") Duration proposalTtl
    ) {
        return new LiveOrderActivationService(
                brokerAdapter, orderPort, orderIntentRepository, brokerOrderRepository, submissionAttemptRepository,
                orderSubmissionService, transitionService, preTradeRiskEngine,
                orderApprovalStepUpService, safetyLedger, killSwitchStateReader,
                new TransactionTemplate(transactionManager),
                proposalTtl);
    }
}
