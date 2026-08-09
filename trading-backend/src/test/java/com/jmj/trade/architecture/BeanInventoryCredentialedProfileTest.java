package com.jmj.trade.architecture;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 빈 인벤토리 골든 테스트 — credentialed 프로파일(조립소 최대 활성 상태).
 *
 * <p>{@link BeanInventoryDefaultProfileTest} 와 같은 오라클을, 조립소가 켜는 빈이 살아있는 최대
 * <em>부팅 가능</em> 상태에서 얼린다. {@code broker.credentials.enabled=true} 로
 * {@code CredentialVaultConfiguration} 전체를, {@code prediction.evaluation.enabled}·
 * {@code prediction.ingestion-api-key.cleanup.enabled} 로 그 하위 조건부 설정까지, 그리고
 * {@code real-order.enabled=true} 로 실거래 게이트까지 켠다. 이 집합이 해체 전후로 동일해야
 * 동작 보존이 증명된다.
 *
 * <p><b>{@code real-order.enabled=true} 를 켜는 이유(중요).</b> 이 플래그를 켜면
 * {@code TossBrokerConfiguration} 이 {@code tossOrderPort} 빈을 만드는데, 그 구현체
 * {@code TossInvestBrokerAdapter} 는 {@code BrokerOrderPort} 이면서 동시에 {@code BrokerAdapter}
 * 다. 예전에는 {@code tossBrokerAdapter} 와 {@code tossOrderPort} 두 개의 {@code BrokerAdapter}
 * 후보가 생겨, 한정자 없이 단일 {@code BrokerAdapter} 를 주입받는 {@code paperOrderWorkflowService}
 * 지점에서 모호성으로 컨텍스트 부팅이 실패했다. 이제 {@code tossOrderPort} 에
 * {@code @Bean(defaultCandidate = false)} 를 붙여 그 빈을 <em>기본</em> 타입 주입 후보에서만 빼
 * {@code BrokerAdapter} 타입 해석 오염을 제거했으므로({@code @Qualifier("tossOrderPort")} 명시적
 * 주입은 그대로 동작, 빈 이름·반환형·조건은 불변), real-order 를 켠 전체 컨텍스트가 정상 부팅한다.
 * ({@code tossBrokerAdapter} 에 {@code @Primary} 를 붙이는 대안은 통합 테스트가 심는
 * {@code brokerAdapter} 목의 파라미터명 배선을 전역으로 뒤집어 폐기했다 — 상세는
 * {@code 2026-08-07-broker-adapter-ambiguity-delta.md}.) 따라서 최대 부팅 가능 상태는 real-order
 * 게이트 빈까지 포함하고, 조립소의 실거래 빈
 * ({@code liveOrderSafetyLedger}, {@code liveOrderActivationService})·
 * {@code liveOrderActivationController}·{@code tossOrderPort} 가 골든에 함께 얼려진다. 다음
 * 해체 PR 이 {@code liveOrderSafetyLedger}·{@code liveOrderActivationService} 를 다른 설정으로
 * 옮겨도 이 오라클이 이름·타입 보존을 덮는다. {@code tossOrderPort} 이름 계약은 부팅 가능한
 * 슬라이스로 {@link TossOrderPortWiringTest} 가 별도로 못 박는다.
 *
 * <p>제외 규칙과 그 이유는 {@link BeanInventoryDefaultProfileTest} 의 클래스 Javadoc 참고. 두
 * 프로파일 골든의 차이가 곧 조립소가 조건부로 조립하는 빈 집합이다.
 */
@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "prediction.evaluation.enabled=true",
                "prediction.ingestion-api-key.cleanup.enabled=true",
                "real-order.enabled=true"
        })
@Import(BeanInventoryCredentialedProfileTest.RedisInfrastructure.class)
class BeanInventoryCredentialedProfileTest extends PostgresIntegrationTest {

    /**
     * credentialed 프로파일에서 살아있어야 하는 애플리케이션 빈의 얼린 목록("빈이름 : 타입FQN",
     * 정렬됨). 해체 PR 이 이 목록을 바꾸면 그것은 동작 변경 신호다.
     */
    private static final List<String> GOLDEN_BEANS = List.of(
            "accessTokenService : com.jmj.trade.security.AccessTokenService",
            "accountSyncService : com.jmj.trade.account.AccountSyncService",
            "accountSyncTransactions : com.jmj.trade.account.AccountSyncTransactions",
            "analysisPredictionController : com.jmj.trade.prediction.AnalysisPredictionController",
            "analysisPredictionService : com.jmj.trade.prediction.AnalysisPredictionService",
            "analysisServiceHealthIndicator : com.jmj.trade.observability.AnalysisServiceHealthIndicator",
            "authController : com.jmj.trade.security.AuthController",
            "brokerConnectionController : com.jmj.trade.broker.connection.BrokerConnectionController",
            "brokerConnectionErrorHandler : com.jmj.trade.broker.connection.BrokerConnectionErrorHandler",
            "brokerConnectionRepository : com.jmj.trade.broker.connection.BrokerConnectionRepository",
            "brokerConnectionService : com.jmj.trade.broker.connection.BrokerConnectionService",
            "brokerConnectionTransactions : com.jmj.trade.broker.connection.BrokerConnectionTransactions",
            "brokerConnectionValidationService : com.jmj.trade.broker.connection.BrokerConnectionValidationService",
            "brokerOrderRepository : com.jmj.trade.order.BrokerOrderRepository",
            "brokerSurfaceController : com.jmj.trade.account.BrokerSurfaceController",
            "brokerSurfaceService : com.jmj.trade.account.BrokerSurfaceService",
            "cookieAuthorizationRequestRepository : com.jmj.trade.security.CookieAuthorizationRequestRepository",
            "correlationIdFilter : com.jmj.trade.observability.CorrelationIdFilter",
            "credentialCipher : com.jmj.trade.broker.connection.CredentialCipher",
            "credentialKeyring : com.jmj.trade.broker.connection.CredentialKeyring",
            "dashboardReadModelController : com.jmj.trade.dashboard.DashboardReadModelController",
            "dashboardReadModelService : com.jmj.trade.dashboard.DashboardReadModelService",
            "dashboardRedirects : com.jmj.trade.security.DashboardRedirects",
            "eventIntelligenceController : com.jmj.trade.intelligence.EventIntelligenceController",
            "eventIntelligenceService : com.jmj.trade.intelligence.EventIntelligenceService",
            "eventReviewWorkflowService : com.jmj.trade.intelligence.EventReviewWorkflowService",
            "forecastQualityMonitoringService : com.jmj.trade.prediction.ForecastQualityMonitoringService",
            "freshPortfolioReadService : com.jmj.trade.account.FreshPortfolioReadService",
            "inboxLedger : com.jmj.trade.inbox.InboxLedger",
            "internalOidcUserService : com.jmj.trade.security.InternalOidcUserService",
            "killSwitchController : com.jmj.trade.order.KillSwitchController",
            "killSwitchLedger : com.jmj.trade.order.KillSwitchLedger",
            "liveOrderActivationController : com.jmj.trade.order.LiveOrderActivationController",
            "liveOrderActivationErrorHandler : com.jmj.trade.order.LiveOrderActivationErrorHandler",
            "liveOrderActivationService : com.jmj.trade.order.LiveOrderActivationService",
            "liveOrderSafetyLedger : com.jmj.trade.order.LiveOrderSafetyLedger",
            "loginRedirectController : com.jmj.trade.security.LoginRedirectController",
            "marketEventIngestionLease : com.jmj.trade.intelligence.ingestion.MarketEventIngestionLease",
            "marketEventIngestionService : com.jmj.trade.intelligence.ingestion.MarketEventIngestionService",
            "marketEventProviderRegistry : com.jmj.trade.intelligence.ingestion.MarketEventProviderRegistry",
            "notificationController : com.jmj.trade.notification.NotificationController",
            "notificationOutboxProcessor : com.jmj.trade.notification.NotificationOutboxProcessor",
            "notificationOutboxWriter : com.jmj.trade.notification.NotificationOutboxWriter",
            "notificationService : com.jmj.trade.notification.NotificationService",
            "oidcIdentityService : com.jmj.trade.security.OidcIdentityService",
            "operationalReadinessController : com.jmj.trade.observability.OperationalReadinessController",
            "operationalReadinessService : com.jmj.trade.observability.OperationalReadinessService",
            "orderApprovalStepUpService : com.jmj.trade.order.OrderApprovalStepUpService",
            "orderIntentAuditLogRepository : com.jmj.trade.order.OrderIntentAuditLogRepository",
            "orderIntentOutboxEventRepository : com.jmj.trade.order.OrderIntentOutboxEventRepository",
            "orderIntentRepository : com.jmj.trade.order.OrderIntentRepository",
            "orderIntentTransitionService : com.jmj.trade.order.OrderIntentTransitionService",
            "orderReconciliationController : com.jmj.trade.order.OrderReconciliationController",
            "orderSubmissionAuditLogRepository : com.jmj.trade.order.OrderSubmissionAuditLogRepository",
            "orderSubmissionOutboxEventRepository : com.jmj.trade.order.OrderSubmissionOutboxEventRepository",
            "orderSubmissionService : com.jmj.trade.order.OrderSubmissionService",
            "originPolicy : com.jmj.trade.security.OriginPolicy",
            "paperOrderWorkflowController : com.jmj.trade.order.PaperOrderWorkflowController",
            "paperOrderWorkflowErrorHandler : com.jmj.trade.order.PaperOrderWorkflowErrorHandler",
            "paperOrderWorkflowService : com.jmj.trade.order.PaperOrderWorkflowService",
            "paperPerformanceAnalyticsController : com.jmj.trade.order.PaperPerformanceAnalyticsController",
            "paperPerformanceAnalyticsService : com.jmj.trade.order.PaperPerformanceAnalyticsService",
            "paperTradingBroker : com.jmj.trade.order.PaperTradingBroker",
            "portfolioAnalysisWorkflowController : com.jmj.trade.analysis.PortfolioAnalysisWorkflowController",
            "portfolioAnalysisWorkflowService : com.jmj.trade.analysis.PortfolioAnalysisWorkflowService",
            "portfolioHistoryController : com.jmj.trade.account.PortfolioHistoryController",
            "portfolioHistoryService : com.jmj.trade.account.PortfolioHistoryService",
            "portfolioReadService : com.jmj.trade.account.PortfolioReadService",
            "preTradeRiskEngine : com.jmj.trade.order.PreTradeRiskEngine",
            "predictionEvaluationLease : com.jmj.trade.prediction.PredictionEvaluationLease",
            "predictionEvaluationMetrics : com.jmj.trade.prediction.PredictionEvaluationMetrics",
            "predictionEvaluationScheduler : com.jmj.trade.prediction.PredictionEvaluationScheduler",
            "predictionIngestionApiKeyAuthenticationFilter : com.jmj.trade.prediction.PredictionIngestionApiKeyAuthenticationFilter",
            "predictionIngestionApiKeyCleanup : com.jmj.trade.prediction.PredictionIngestionApiKeyCleanup",
            "predictionIngestionApiKeyController : com.jmj.trade.prediction.PredictionIngestionApiKeyController",
            "predictionIngestionApiKeyMetrics : com.jmj.trade.prediction.PredictionIngestionApiKeyMetrics",
            "predictionIngestionApiKeyRateLimiter : com.jmj.trade.prediction.PredictionIngestionApiKeyRateLimiter",
            "predictionIngestionApiKeyService : com.jmj.trade.prediction.PredictionIngestionApiKeyService",
            "predictionModelRegistryController : com.jmj.trade.prediction.PredictionModelRegistryController",
            "predictionModelRegistryService : com.jmj.trade.prediction.PredictionModelRegistryService",
            "predictionOperationsController : com.jmj.trade.prediction.PredictionOperationsController",
            "realOrderCanaryAuditLedger : com.jmj.trade.order.RealOrderCanaryAuditLedger",
            "realOrderCanaryController : com.jmj.trade.order.RealOrderCanaryController",
            "realOrderCanaryErrorHandler : com.jmj.trade.order.RealOrderCanaryErrorHandler",
            "realOrderCanaryService : com.jmj.trade.order.RealOrderCanaryService",
            "reconciliationBrokerProbe : com.jmj.trade.order.BrokerOrderPortReconciliationProbe",
            "reconciliationCheckRepository : com.jmj.trade.order.ReconciliationCheckRepository",
            "refreshTokenService : com.jmj.trade.security.RefreshTokenService",
            "riskPolicyController : com.jmj.trade.risk.RiskPolicyController",
            "riskPolicyService : com.jmj.trade.risk.RiskPolicyService",
            "scheduledPortfolioRefreshService : com.jmj.trade.refresh.ScheduledPortfolioRefreshService",
            "scheduledRefreshLease : com.jmj.trade.refresh.ScheduledRefreshLease",
            "sessionController : com.jmj.trade.security.SessionController",
            "stockAnalysisGeminiExplainController : com.jmj.trade.prediction.StockAnalysisGeminiExplainController",
            "stockAnalysisGeminiExplainService : com.jmj.trade.prediction.StockAnalysisGeminiExplainService",
            "stockAnalysisWorkflowController : com.jmj.trade.analysis.StockAnalysisWorkflowController",
            "stockAnalysisWorkflowService : com.jmj.trade.analysis.StockAnalysisWorkflowService",
            "stockDataProviderRegistry : com.jmj.trade.marketdata.StockDataProviderRegistry",
            "stockForecastController : com.jmj.trade.prediction.StockForecastController",
            "stockForecastService : com.jmj.trade.prediction.StockForecastService",
            "submissionAttemptRepository : com.jmj.trade.order.SubmissionAttemptRepository",
            "submissionIdempotencyKeyRepository : com.jmj.trade.order.SubmissionIdempotencyKeyRepository",
            "tossApiClient : com.jmj.trade.broker.toss.TossApiClient",
            "tossBrokerAdapter : com.jmj.trade.broker.toss.TossInvestBrokerAdapter",
            "tossCredentialProvider : com.jmj.trade.broker.connection.DatabaseTossCredentialProvider",
            "tossOAuthClient : com.jmj.trade.broker.toss.TossOAuthClient",
            "tossOrderPort : com.jmj.trade.broker.toss.TossInvestBrokerAdapter",
            "tossResponseMapper : com.jmj.trade.broker.toss.TossResponseMapper",
            "tossTokenManager : com.jmj.trade.broker.toss.TossTokenManager",
            "unknownAttemptReconciler : com.jmj.trade.order.UnknownAttemptReconciler",
            "userAnchorRepository : com.jmj.trade.broker.connection.UserAnchorRepository",
            "workflowMetrics : com.jmj.trade.observability.WorkflowMetrics");

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationBeanInventoryMatchesGolden() {
        List<String> actual = BeanInventoryDefaultProfileTest.actualInventory(context);
        assertThat(actual)
                .as("credentialed 프로파일 애플리케이션 빈 집합은 얼린 골든과 정확히 일치해야 한다(조립소 해체 동작 보존 오라클)")
                .containsExactlyElementsOf(GOLDEN_BEANS);
    }

    @TestConfiguration
    static class RedisInfrastructure {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
