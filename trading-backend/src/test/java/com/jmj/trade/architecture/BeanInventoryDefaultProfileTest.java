package com.jmj.trade.architecture;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 빈 인벤토리 골든 테스트 — 기본 프로파일(broker.credentials.enabled 미설정).
 *
 * <p><b>왜 이 테스트가 존재하는가.</b> {@code CredentialVaultConfiguration} 은 order·prediction·
 * account·broker 의 빈을 조립하는 전역 조립소다. 이 조립소를 모듈별로 해체할 예정인데, 해체가
 * 동작을 바꾸지 않았음을 증명할 오라클이 없다. 기존 테스트는 "빈이 존재한다"만 확인하지 "빈 집합이
 * 동일하다"를 확인하지 않는다. 이 테스트는 컨텍스트의 애플리케이션 빈 집합(빈 이름 + 타입 FQN)을
 * 정렬해 얼려, 해체 전후의 집합 동등성을 검증하는 오라클을 제공한다.
 *
 * <p><b>왜 {@code @Configuration}/{@code @ConfigurationProperties} 를 제외하는가.</b> 조립소를
 * 쪼개면 설정 클래스가 <em>자기 이름(FQN)으로 등록하는 빈</em>의 이름이 <em>반드시</em> 바뀐다
 * (예: 내부 정적 설정 {@code CredentialVaultConfiguration$PredictionIngestionApiKeyCleanupConfiguration}
 * 이 자기 모듈로 이사하면 이름이 달라진다). 이건 의도된 변경이므로 오라클에서 빼야 신호가 유지된다.
 * 반대로 {@code @Bean} 메서드가 만든 빈의 이름·타입은 메서드가 다른 설정 클래스로 이동해도 바뀌지
 * 않으므로, 그 집합이 동일하게 유지되는 것이 곧 "동작 보존"의 정의다.
 *
 * <p>제외 판정은 {@link AnnotatedElementUtils#hasAnnotation}(메타 애노테이션 포함)으로 한다.
 * {@code @Configuration} 을 직접 붙인 클래스뿐 아니라 그것을 메타로 갖는 설정 루트
 * ({@code @SpringBootApplication}, {@code @AutoConfiguration}, {@code @TestConfiguration},
 * {@code @SpringBootConfiguration})까지 모두 제외 대상이다. 이들은 전부 클래스 이동에 민감한
 * 이름으로 자기 자신을 빈 등록하는 <em>composition root</em> 이므로, 그 자체를 인벤토리에서 빼야
 * 조립 빈 집합의 신호가 노이즈에 묻히지 않는다. (예: {@code @AutoConfiguration} 인
 * {@code TossBrokerConfiguration} 이나 테스트 스캐폴딩인 {@code @TestConfiguration} 이 골든에
 * 새는 것을 막는다.)
 *
 * <p><b>기본 프로파일의 의미.</b> {@code broker.credentials.enabled} 가 설정되지 않은 상태는
 * 운영 기본값({@code compose.yaml} 의 {@code false})과 동일하다. 이 상태에서 조립소의 빈들은
 * 생성되지 않아야 한다. credentialed 프로파일과의 집합 차이가 조립소가 조건부로 켜는 빈 집합이다.
 */
@SpringBootTest(classes = TradingBackendApplication.class)
class BeanInventoryDefaultProfileTest extends PostgresIntegrationTest {

    /**
     * 기본 프로파일에서 살아있어야 하는 애플리케이션 빈의 얼린 목록("빈이름 : 타입FQN", 정렬됨).
     * 해체 PR 이 이 목록을 바꾸면 그것은 동작 변경 신호다. 의도된 변경이면 목록을 갱신하고, 아니면
     * 프로덕션 코드를 되돌린다.
     */
    private static final List<String> GOLDEN_BEANS = List.of(
            "accessTokenService : com.jmj.trade.security.AccessTokenService",
            "analysisServiceHealthIndicator : com.jmj.trade.observability.AnalysisServiceHealthIndicator",
            "authController : com.jmj.trade.security.AuthController",
            "brokerConnectionErrorHandler : com.jmj.trade.broker.connection.BrokerConnectionErrorHandler",
            "brokerConnectionRepository : com.jmj.trade.broker.connection.BrokerConnectionRepository",
            "brokerOrderRepository : com.jmj.trade.order.BrokerOrderRepository",
            "cookieAuthorizationRequestRepository : com.jmj.trade.security.CookieAuthorizationRequestRepository",
            "correlationIdFilter : com.jmj.trade.observability.CorrelationIdFilter",
            "dashboardReadModelController : com.jmj.trade.dashboard.DashboardReadModelController",
            "dashboardReadModelService : com.jmj.trade.dashboard.DashboardReadModelService",
            "dashboardRedirects : com.jmj.trade.security.DashboardRedirects",
            "eventIntelligenceController : com.jmj.trade.intelligence.EventIntelligenceController",
            "eventIntelligenceService : com.jmj.trade.intelligence.EventIntelligenceService",
            "eventReviewWorkflowService : com.jmj.trade.intelligence.EventReviewWorkflowService",
            "forecastQualityMonitoringService : com.jmj.trade.prediction.ForecastQualityMonitoringService",
            "inboxLedger : com.jmj.trade.inbox.InboxLedger",
            "internalOidcUserService : com.jmj.trade.security.InternalOidcUserService",
            "liveOrderActivationErrorHandler : com.jmj.trade.order.LiveOrderActivationErrorHandler",
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
            "orderIntentAuditLogRepository : com.jmj.trade.order.OrderIntentAuditLogRepository",
            "orderIntentOutboxEventRepository : com.jmj.trade.order.OrderIntentOutboxEventRepository",
            "orderIntentRepository : com.jmj.trade.order.OrderIntentRepository",
            "orderIntentTransitionService : com.jmj.trade.order.OrderIntentTransitionService",
            "orderSubmissionAuditLogRepository : com.jmj.trade.order.OrderSubmissionAuditLogRepository",
            "orderSubmissionOutboxEventRepository : com.jmj.trade.order.OrderSubmissionOutboxEventRepository",
            "orderSubmissionService : com.jmj.trade.order.OrderSubmissionService",
            "originPolicy : com.jmj.trade.security.OriginPolicy",
            "paperOrderWorkflowErrorHandler : com.jmj.trade.order.PaperOrderWorkflowErrorHandler",
            "paperPerformanceAnalyticsController : com.jmj.trade.order.PaperPerformanceAnalyticsController",
            "paperPerformanceAnalyticsService : com.jmj.trade.order.PaperPerformanceAnalyticsService",
            "paperTradingBroker : com.jmj.trade.order.PaperTradingBroker",
            "portfolioAnalysisWorkflowController : com.jmj.trade.analysis.PortfolioAnalysisWorkflowController",
            "portfolioAnalysisWorkflowService : com.jmj.trade.analysis.PortfolioAnalysisWorkflowService",
            "portfolioHistoryController : com.jmj.trade.account.PortfolioHistoryController",
            "portfolioHistoryService : com.jmj.trade.account.PortfolioHistoryService",
            "portfolioReadService : com.jmj.trade.account.PortfolioReadService",
            "predictionEvaluationMetrics : com.jmj.trade.prediction.PredictionEvaluationMetrics",
            "predictionIngestionApiKeyMetrics : com.jmj.trade.prediction.PredictionIngestionApiKeyMetrics",
            "predictionModelRegistryService : com.jmj.trade.prediction.PredictionModelRegistryService",
            "realOrderCanaryAuditLedger : com.jmj.trade.order.RealOrderCanaryAuditLedger",
            "realOrderCanaryController : com.jmj.trade.order.RealOrderCanaryController",
            "realOrderCanaryErrorHandler : com.jmj.trade.order.RealOrderCanaryErrorHandler",
            "realOrderCanaryService : com.jmj.trade.order.RealOrderCanaryService",
            "reconciliationCheckRepository : com.jmj.trade.order.ReconciliationCheckRepository",
            "refreshTokenService : com.jmj.trade.security.RefreshTokenService",
            "riskPolicyController : com.jmj.trade.risk.RiskPolicyController",
            "riskPolicyService : com.jmj.trade.risk.RiskPolicyService",
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
            "userAnchorRepository : com.jmj.trade.broker.connection.UserAnchorRepository",
            "workflowMetrics : com.jmj.trade.observability.WorkflowMetrics");

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationBeanInventoryMatchesGolden() {
        List<String> actual = actualInventory(context);
        assertThat(actual)
                .as("기본 프로파일 애플리케이션 빈 집합은 얼린 골든과 정확히 일치해야 한다(조립소 해체 동작 보존 오라클)")
                .containsExactlyElementsOf(GOLDEN_BEANS);
    }

    /**
     * 컨텍스트의 모든 빈 중 타입 FQN 이 {@code com.jmj.trade.} 로 시작하고 설정 루트
     * ({@code @Configuration}/{@code @ConfigurationProperties}, 메타 애노테이션 포함)가 아닌 것을
     * "빈이름 : 타입FQN" 으로 만들어 정렬해 반환한다. 타입이 null 인 빈은 건너뛰고, AOP 프록시는
     * {@link ClassUtils#getUserClass} 로 원본 타입을 얻어 프록시 접미사가 새지 않게 한다.
     */
    static List<String> actualInventory(ApplicationContext context) {
        List<String> inventory = new ArrayList<>();
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(beanName);
            if (type == null) {
                continue;
            }
            Class<?> userType = ClassUtils.getUserClass(type);
            String fqn = userType.getName();
            if (!fqn.startsWith("com.jmj.trade.")) {
                continue;
            }
            if (AnnotatedElementUtils.hasAnnotation(userType, Configuration.class)
                    || AnnotatedElementUtils.hasAnnotation(userType, ConfigurationProperties.class)) {
                continue;
            }
            inventory.add(beanName + " : " + fqn);
        }
        Collections.sort(inventory);
        return inventory;
    }
}
