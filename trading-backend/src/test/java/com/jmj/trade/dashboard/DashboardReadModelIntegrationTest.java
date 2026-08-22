package com.jmj.trade.dashboard;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.analysis.PortfolioAnalysisWorkflowService;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TradingBackendApplication.class)
class DashboardReadModelIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime TIME =
            OffsetDateTime.of(2026, 7, 28, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private com.jmj.trade.risk.RiskPolicyService riskPolicies;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE broker_connections, users CASCADE");
    }

    @Test
    void readsLatestLedgersWithPerSectionQualityAndSetCollections() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var analyzedSnapshot = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysis(connectionId, USER_ID, analyzedSnapshot, TIME.plusSeconds(2));
        var latestSnapshot = insertPortfolio(
                connectionId, USER_ID, TIME.plusMinutes(1), false);
        insertFailedRun(connectionId, USER_ID, TIME.plusMinutes(2));

        var pendingEventA = insertEvent(connectionId, USER_ID, "event-a", TIME);
        var pendingEventB = insertEvent(
                connectionId, USER_ID, "event-b", TIME.plusSeconds(1));
        var handledEvent = insertEvent(
                connectionId, USER_ID, "event-c", TIME.plusSeconds(2));
        jdbc.update("""
                INSERT INTO event_reviews (
                    event_id, user_id, broker_connection_id, status, version, reviewed_at
                ) VALUES (?, ?, ?, 'CONFIRMED', 1, ?)
                """, handledEvent, USER_ID, connectionId, TIME.plusSeconds(3));

        insertOrder(connectionId, USER_ID, "AAPL", "PROPOSED");
        insertOrder(connectionId, USER_ID, "MSFT", "PROPOSED");
        insertOrder(connectionId, USER_ID, "NVDA", "APPROVED");

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolio.data.syncRunId")
                        .value(latestSnapshot.toString()))
                .andExpect(jsonPath("$.portfolio.stale").value(true))
                .andExpect(jsonPath("$.portfolio.unknown").value(false))
                .andExpect(jsonPath("$.portfolio.unknownFields").isEmpty())
                .andExpect(jsonPath("$.portfolio.unavailable").value(false))
                .andExpect(jsonPath("$.analysis.data.inputSnapshotId")
                        .value(analyzedSnapshot.toString()))
                .andExpect(jsonPath("$.analysis.stale").value(true))
                .andExpect(jsonPath("$.analysis.unknown").value(false))
                .andExpect(jsonPath("$.analysis.unavailable").value(false))
                .andExpect(jsonPath("$.pendingEvents.data[*].id", containsInAnyOrder(
                        pendingEventA.toString(), pendingEventB.toString())))
                .andExpect(jsonPath("$.pendingEvents.unavailable").value(false))
                .andExpect(jsonPath("$.pendingOrderProposals.data[*].symbol",
                        containsInAnyOrder("AAPL", "MSFT", "NVDA")))
                .andExpect(jsonPath("$.pendingOrderProposals.unavailable").value(false));
    }

    @Test
    void marksMissingSnapshotAndAnalysisUnavailableWithoutWritingState() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var eventsBefore = count("intelligence_events");
        var ordersBefore = count("order_intents");

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolio.unavailable").value(true))
                .andExpect(jsonPath("$.portfolio.unavailableReason")
                        .value("PORTFOLIO_SNAPSHOT_NOT_FOUND"))
                .andExpect(jsonPath("$.analysis.unavailable").value(true))
                .andExpect(jsonPath("$.analysis.unavailableReason")
                        .value("ANALYSIS_RESULT_NOT_FOUND"))
                .andExpect(jsonPath("$.pendingEvents.data").isEmpty())
                .andExpect(jsonPath("$.pendingOrderProposals.data").isEmpty());

        org.assertj.core.api.Assertions.assertThat(count("intelligence_events"))
                .isEqualTo(eventsBefore);
        org.assertj.core.api.Assertions.assertThat(count("order_intents"))
                .isEqualTo(ordersBefore);
    }

    @Test
    void hidesAnotherUsersDashboard() throws Exception {
        var connectionId = insertConnection(USER_ID);

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
    }

    @Test
    void defaultProposalFilterReturnsOpenStatusesAndExcludesClosedOnes() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertOrderWithStatus(connectionId, USER_ID, "OPEN1", "MANUAL_REVIEW_REQUIRED", TIME);
        insertOrderWithStatus(connectionId, USER_ID, "OPEN2", "BLOCKED", TIME.plusSeconds(1));
        insertOrderWithStatus(connectionId, USER_ID, "OPEN3", "ACTIVE", TIME.plusSeconds(2));
        insertOrderWithStatus(connectionId, USER_ID, "CLOSED1", "COMPLETED", TIME);
        insertOrderWithStatus(connectionId, USER_ID, "CLOSED2", "CANCELED", TIME);
        insertOrderWithStatus(connectionId, USER_ID, "CLOSED3", "REJECTED", TIME);
        insertOrderWithStatus(connectionId, USER_ID, "CLOSED4", "EXPIRED", TIME);

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrderProposals.data[*].symbol",
                        containsInAnyOrder("OPEN1", "OPEN2", "OPEN3")));
    }

    @Test
    void explicitOrderStatusFilterReturnsMatchingClosedRows() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertOrderWithStatus(connectionId, USER_ID, "DONE", "COMPLETED", TIME);
        insertOrderWithStatus(connectionId, USER_ID, "OPEN", "PROPOSED", TIME);

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .param("orderStatus", "COMPLETED")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrderProposals.data[*].symbol",
                        containsInAnyOrder("DONE")));
    }

    @Test
    void unknownOrderStatusIsRejectedAsClientError() throws Exception {
        var connectionId = insertConnection(USER_ID);

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .param("orderStatus", "NOT_A_STATUS")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void orderStatusCraftedAsSqlFragmentDoesNotAlterQuery() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertOrderWithStatus(connectionId, USER_ID, "OPEN", "PROPOSED", TIME);

        // 상태 이름이 SQL 로 삽입됐다면 이 조각이 필터를 무력화했을 것이다. 열거형 화이트리스트가
        // 바인딩 이전에 400 으로 막으므로 쿼리는 결코 바뀌지 않는다.
        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .param("orderStatus", "PROPOSED') OR ('1'='1")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    void pendingProposalSerializesCreatedAtAndExpiresAt() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertOrderWithStatus(connectionId, USER_ID, "SYM", "PROPOSED", TIME);

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrderProposals.data[0].createdAt").exists())
                .andExpect(jsonPath("$.pendingOrderProposals.data[0].expiresAt").exists());
    }

    @Test
    void queryCountDoesNotGrowWithEventOrProposalRows() {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysis(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2));
        insertEvent(connectionId, USER_ID, "event-0", TIME);
        insertOrder(connectionId, USER_ID, "SYM0", "PROPOSED");
        var countingJdbc = new CountingJdbcTemplate(dataSource);
        var service = newService(countingJdbc);

        service.read(USER_ID, connectionId);
        var oneRowQueries = countingJdbc.count();

        for (var index = 1; index < 20; index++) {
            insertEvent(connectionId, USER_ID, "event-" + index, TIME.plusSeconds(index));
            insertOrder(connectionId, USER_ID, "SYM" + index, "PROPOSED");
        }
        countingJdbc.reset();

        service.read(USER_ID, connectionId);

        // 9 = 기존 섹션들 + 1 = 보유 심볼 전체의 최신 판단을 한 번에 가져오는 BC-2 조인 쿼리.
        org.assertj.core.api.Assertions.assertThat(oneRowQueries).isEqualTo(10);
        org.assertj.core.api.Assertions.assertThat(countingJdbc.count())
                .isEqualTo(oneRowQueries);
    }

    @SuppressWarnings("unchecked")
    private DashboardReadModelService newService(JdbcTemplate countingJdbc) {
        var noSync = mock(ObjectProvider.class);
        when(noSync.getIfAvailable()).thenReturn(null);
        return new DashboardReadModelService(
                countingJdbc,
                objectMapper,
                new FreshPortfolioReadService(
                        new PortfolioReadService(
                                countingJdbc,
                                objectMapper,
                                Duration.ofMinutes(15)),
                        noSync),
                new PortfolioAnalysisWorkflowService(
                        countingJdbc,
                        transactionManager,
                        objectMapper,
                        "http://localhost:8000",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(15),
                        new NotificationOutboxWriter(countingJdbc, objectMapper)),
                riskPolicies);
    }

    // --- BC-4: 포트폴리오 위험 평가 (판정은 서버) -------------------------------------------

    @Test
    void riskEvaluationJudgesConcentrationAgainstPolicyOnTheServer() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.10"), currencyTotals("0.10"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.unavailable").value(false))
                // 정책 미설정 사용자는 플랫폼 기본값(version 0)이며, 그것도 유효한 영구 상태다.
                .andExpect(jsonPath("$.riskEvaluation.data.policyVersion").value(0))
                .andExpect(jsonPath("$.riskEvaluation.data.evaluatedAt").isNotEmpty())
                .andExpect(jsonPath("$.riskEvaluation.data.items.length()").value(2))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].key")
                        .value("POSITION_CONCENTRATION:NVDA"))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].scope").value("POSITION"))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].subject").value("NVDA"))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].current").value(0.10))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].limit").value(0.25))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].usageRatio").value(0.4))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].breached").value(false))
                .andExpect(jsonPath("$.riskEvaluation.data.items[1].key")
                        .value("CURRENCY_CONCENTRATION:USD"))
                .andExpect(jsonPath("$.riskEvaluation.data.items[1].scope").value("CURRENCY"))
                .andExpect(jsonPath("$.riskEvaluation.data.items[1].subject").value("USD"))
                .andExpect(jsonPath("$.riskEvaluation.data.items[1].breached").value(false));
    }

    @Test
    void riskEvaluationMarksBreachWhenCurrentExceedsPolicyLimit() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("1"), currencyTotals("1"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].breached").value(true))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].usageRatio").value(4))
                .andExpect(jsonPath("$.riskEvaluation.data.items[1].breached").value(true));
    }

    @Test
    void riskEvaluationUsesTheUsersCustomPolicyVersionAndLimit() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.50"), currencyTotals("0.50"));
        insertRiskPolicy(USER_ID, 7, "0.9000");

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.data.policyVersion").value(7))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].limit").value(0.9))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].breached").value(false));
    }

    @Test
    void riskEvaluationIsUnavailableWithoutAnAnalysisResult() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertPortfolio(connectionId, USER_ID, TIME, true);

        // 빈 items 로 "위험 없음"을 단언하지 않는다 — 근거가 없으면 unavailable 이다.
        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.unavailable").value(true))
                .andExpect(jsonPath("$.riskEvaluation.unavailableReason")
                        .value("ANALYSIS_RESULT_NOT_FOUND"))
                .andExpect(jsonPath("$.riskEvaluation.data").doesNotExist());
    }

    @Test
    void riskEvaluationReportsNullWeightAsUnknownFieldInsteadOfAnItem() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("null"), currencyTotals("0.10"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.unavailable").value(false))
                .andExpect(jsonPath("$.riskEvaluation.unknown").value(true))
                .andExpect(jsonPath("$.riskEvaluation.unknownFields",
                        containsInAnyOrder("positions[NVDA].weight")))
                .andExpect(jsonPath("$.riskEvaluation.data.items.length()").value(1))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].key")
                        .value("CURRENCY_CONCENTRATION:USD"));
    }

    @Test
    void riskEvaluationPropagatesDegradedAnalysisAsStaleAndUnknown() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        // 기존 픽스처: status=DEGRADED, quality has no accounting-cash field.
        insertAnalysis(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.unavailable").value(false))
                .andExpect(jsonPath("$.riskEvaluation.stale").value(true))
                .andExpect(jsonPath("$.riskEvaluation.unknown").value(false))
                .andExpect(jsonPath("$.riskEvaluation.unknownFields").isEmpty())
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].breached").value(true));
    }

    // --- BC-2: 포지션별 위험 등급 + 저장된 판단 조인 ----------------------------------------

    @Test
    void positionDecisionJoinsStoredStockAnalysisDecisionWithoutRecomputingIt() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.10"), currencyTotals("0.10"));
        var runId = insertStockAnalysis(USER_ID, "NVDA", TIME.plusMinutes(3),
                "2026-07-28T00:03:00Z", decision("BUY", "0.72", "decision-rule-v1"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionDecisions.unavailable").value(false))
                .andExpect(jsonPath("$.positionDecisions.unknown").value(false))
                .andExpect(jsonPath("$.positionDecisions.data.length()").value(1))
                .andExpect(jsonPath("$.positionDecisions.data[0].symbol").value("NVDA"))
                // 0.10 / 0.25 = 0.4 → 한도의 80% 미만이므로 LOW
                .andExpect(jsonPath("$.positionDecisions.data[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.positionDecisions.data[0].decision").value("BUY"))
                .andExpect(jsonPath("$.positionDecisions.data[0].confidence").value(0.72))
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionRuleVersion")
                        .value("decision-rule-v1"))
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionAsOf")
                        .value("2026-07-28T00:03:00Z"))
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionRunId")
                        .value(runId.toString()));
    }

    @Test
    void positionRiskLevelIsHighWhenConcentrationItemIsBreached() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.30"), currencyTotals("0.30"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].breached").value(true))
                .andExpect(jsonPath("$.positionDecisions.data[0].riskLevel").value("HIGH"));
    }

    @Test
    void positionRiskLevelIsMediumAtEightyPercentOfTheLimit() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        // 0.20 / 0.25 = 0.80 — 경계값은 MEDIUM 쪽에 포함된다.
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.20"), currencyTotals("0.20"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].usageRatio").value(0.8))
                .andExpect(jsonPath("$.riskEvaluation.data.items[0].breached").value(false))
                .andExpect(jsonPath("$.positionDecisions.data[0].riskLevel").value("MEDIUM"));
    }

    @Test
    void positionRiskLevelIsNullWhenNoConcentrationItemExists() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        // weight 누락 → 집중도 항목이 만들어지지 않는다. 모름을 LOW 로 접지 않는다.
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("null"), currencyTotals("0.10"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionDecisions.unavailable").value(false))
                .andExpect(jsonPath("$.positionDecisions.data[0].riskLevel").doesNotExist())
                .andExpect(jsonPath("$.positionDecisions.unknown").value(true))
                .andExpect(jsonPath("$.positionDecisions.unknownFields",
                        containsInAnyOrder("positions[NVDA].riskLevel")));
    }

    @Test
    void positionRiskLevelIsNullWhenRiskEvaluationIsUnavailable() throws Exception {
        var connectionId = insertConnection(USER_ID);
        insertPortfolio(connectionId, USER_ID, TIME, true);

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskEvaluation.unavailable").value(true))
                .andExpect(jsonPath("$.positionDecisions.unavailable").value(false))
                .andExpect(jsonPath("$.positionDecisions.data[0].symbol").value("NVDA"))
                .andExpect(jsonPath("$.positionDecisions.data[0].riskLevel").doesNotExist());
    }

    @Test
    void positionDecisionFieldsAreAllNullWithoutAStoredStockAnalysis() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.10"), currencyTotals("0.10"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionDecisions.data[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.positionDecisions.data[0].decision").doesNotExist())
                .andExpect(jsonPath("$.positionDecisions.data[0].confidence").doesNotExist())
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionRuleVersion")
                        .doesNotExist())
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionAsOf").doesNotExist())
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionRunId").doesNotExist());
    }

    @Test
    void storedAnalysisWithoutADecisionKeepsTheDecisionNullButStaysTraceable() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.10"), currencyTotals("0.10"));
        // 지표 부족으로 판단이 만들어지지 않은 실행. HOLD 같은 기본값으로 대체하지 않는다.
        var runId = insertStockAnalysis(
                USER_ID, "NVDA", TIME.plusMinutes(3), "2026-07-28T00:03:00Z", "null");

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionDecisions.data[0].decision").doesNotExist())
                .andExpect(jsonPath("$.positionDecisions.data[0].confidence").doesNotExist())
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionRuleVersion")
                        .doesNotExist())
                // 판단이 없다는 사실 자체가 어느 실행에서 나왔는지는 추적 가능해야 한다.
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionAsOf")
                        .value("2026-07-28T00:03:00Z"))
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionRunId")
                        .value(runId.toString()));
    }

    @Test
    void positionDecisionUsesTheLatestSucceededRunAndIgnoresFailedOnes() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.10"), currencyTotals("0.10"));
        insertStockAnalysis(USER_ID, "NVDA", TIME.plusMinutes(1), "2026-07-28T00:01:00Z",
                decision("SELL", "0.30", "decision-rule-v1"));
        var latest = insertStockAnalysis(USER_ID, "NVDA", TIME.plusMinutes(5),
                "2026-07-28T00:05:00Z", decision("HOLD", "0.51", "decision-rule-v1"));
        insertFailedStockAnalysis(USER_ID, "NVDA", TIME.plusMinutes(9));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionDecisions.data[0].decision").value("HOLD"))
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionAsOf")
                        .value("2026-07-28T00:05:00Z"))
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionRunId")
                        .value(latest.toString()));
    }

    @Test
    void anotherUsersStockAnalysisDecisionIsNeverExposed() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.10"), currencyTotals("0.10"));
        // 같은 심볼에 대한 타 사용자의 최신 판단. 이 값이 새면 안 된다.
        insertStockAnalysis(OTHER_USER_ID, "NVDA", TIME.plusMinutes(9), "2026-07-28T00:09:00Z",
                decision("SELL", "0.99", "decision-rule-v1"));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionDecisions.data[0].decision").doesNotExist())
                .andExpect(jsonPath("$.positionDecisions.data[0].decisionRunId").doesNotExist());
    }

    @Test
    void positionDecisionsAreUnavailableWithoutAPortfolioSnapshot() throws Exception {
        var connectionId = insertConnection(USER_ID);

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionDecisions.unavailable").value(true))
                .andExpect(jsonPath("$.positionDecisions.unavailableReason")
                        .value("PORTFOLIO_SNAPSHOT_NOT_FOUND"))
                .andExpect(jsonPath("$.positionDecisions.data").doesNotExist());
    }

    @Test
    void positionDecisionQueryCountDoesNotGrowWithHeldSymbols() {
        var connectionId = insertConnection(USER_ID);
        var snapshotId = insertPortfolio(connectionId, USER_ID, TIME, true);
        insertAnalysisResponse(connectionId, USER_ID, snapshotId, TIME.plusSeconds(2), "COMPLETED",
                cleanQuality(), positions("0.10"), currencyTotals("0.10"));
        insertStockAnalysis(USER_ID, "NVDA", TIME.plusMinutes(3), "2026-07-28T00:03:00Z",
                decision("BUY", "0.72", "decision-rule-v1"));
        var counting = new CountingJdbcTemplate(dataSource);
        var service = newService(counting);

        service.read(USER_ID, connectionId);
        var oneSymbolQueries = counting.count();

        var manySymbols = insertSyncRun(connectionId, USER_ID, TIME.plusMinutes(10));
        for (var index = 0; index < 20; index++) {
            var symbol = "SYM" + index;
            insertPosition(manySymbols, USER_ID, connectionId, symbol, TIME.plusMinutes(10));
            insertStockAnalysis(USER_ID, symbol, TIME.plusMinutes(11), "2026-07-28T00:11:00Z",
                    decision("HOLD", "0.50", "decision-rule-v1"));
        }
        counting.reset();

        service.read(USER_ID, connectionId);

        org.assertj.core.api.Assertions.assertThat(counting.count()).isEqualTo(oneSymbolQueries);
    }

    private static String decision(String action, String confidence, String ruleVersion) {
        return """
                {"action":"%s","confidence":%s,"ruleVersion":"%s","basis":[],"missingData":[]}"""
                .formatted(action, confidence, ruleVersion);
    }

    private UUID insertStockAnalysis(
            UUID userId,
            String symbol,
            OffsetDateTime completedAt,
            String asOf,
            String decisionJson
    ) {
        var snapshotId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.update("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash,
                    collected_at, created_at
                ) VALUES (?, ?, ?, '1', '{}'::jsonb, ?, ?, ?)
                """, snapshotId, userId, symbol, "0".repeat(64), completedAt, completedAt);
        jdbc.update("""
                INSERT INTO stock_analysis_runs (
                    id, user_id, input_snapshot_id, symbol, status, started_at, completed_at
                ) VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, ?)
                """, runId, userId, snapshotId, symbol, completedAt, completedAt);
        jdbc.update("""
                INSERT INTO stock_analysis_results (
                    id, stock_analysis_run_id, user_id, input_snapshot_id,
                    schema_version, result_status, response, created_at
                ) VALUES (?, ?, ?, ?, '1', 'COMPLETED', CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), runId, userId, snapshotId, """
                {
                  "requestId":"%s",
                  "schemaVersion":"1",
                  "inputSnapshotId":"%s",
                  "symbol":"%s",
                  "asOf":"%s",
                  "status":"COMPLETED",
                  "missingData":[],
                  "observations":[],
                  "analyzers":[],
                  "decision":%s,
                  "positionPlan":null
                }
                """.formatted(runId, snapshotId, symbol, asOf, decisionJson), completedAt);
        return runId;
    }

    private void insertFailedStockAnalysis(
            UUID userId,
            String symbol,
            OffsetDateTime completedAt
    ) {
        jdbc.update("""
                INSERT INTO stock_analysis_runs (
                    id, user_id, symbol, status, error_code, started_at, completed_at
                ) VALUES (?, ?, ?, 'FAILED', 'STOCK_ANALYSIS_TIMEOUT', ?, ?)
                """, UUID.randomUUID(), userId, symbol, completedAt, completedAt);
    }

    private static String cleanQuality() {
        return "{\"stale\":false,\"partial\":false,\"unknownFields\":[]}";
    }

    private static String positions(String weight) {
        return """
                [{"symbol":"NVDA","currency":"USD","marketValue":120,"profitLoss":20,
                  "weight":%s}]""".formatted(weight);
    }

    private static String currencyTotals(String concentration) {
        return """
                [{"currency":"USD","marketValue":120,"profitLoss":20,
                  "concentration":%s}]""".formatted(concentration);
    }

    private void insertRiskPolicy(UUID userId, long version, String maxConcentration) {
        jdbc.update("""
                INSERT INTO risk_policies (
                    user_id, version, max_order_amount_krw, max_order_amount_usd,
                    max_quantity, max_concentration, updated_at, updated_by
                ) VALUES (?, ?, 10000000, 10000, 100, CAST(? AS numeric), ?, 'test')
                """, userId, version, maxConcentration, TIME);
    }

    private void insertAnalysisResponse(
            UUID connectionId,
            UUID userId,
            UUID snapshotId,
            OffsetDateTime time,
            String status,
            String quality,
            String positions,
            String currencyTotals
    ) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_runs (
                    id, user_id, broker_connection_id, input_sync_run_id,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, ?)
                """, runId, userId, connectionId, snapshotId, time, time.plusSeconds(1));
        jdbc.update("""
                INSERT INTO analysis_results (
                    id, analysis_run_id, user_id, broker_connection_id, input_sync_run_id,
                    schema_version, result_status, response, created_at
                ) VALUES (?, ?, ?, ?, ?, '1', ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), runId, userId, connectionId, snapshotId, status, """
                {
                  "requestId":"%s",
                  "schemaVersion":"1",
                  "asOf":"2026-07-28T00:00:00Z",
                  "status":"%s",
                  "quality":%s,
                  "positions":%s,
                  "currencyTotals":%s
                }
                """.formatted(runId, status, quality, positions, currencyTotals),
                time.plusSeconds(1));
    }

    private UUID insertConnection(UUID userId) {
        var connectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], TIME, TIME);
        return connectionId;
    }

    private UUID insertPortfolio(
            UUID connectionId,
            UUID userId,
            OffsetDateTime time,
            boolean includeUsd
    ) {
        var runId = insertSyncRun(connectionId, userId, time);
        jdbc.update("""
                INSERT INTO account_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, account_type,
                    display_account_number, total_purchase_amounts, market_value_amounts,
                    market_value_after_cost_amounts, profit_loss_amounts,
                    profit_loss_after_cost_amounts, daily_profit_loss_amounts,
                    profit_loss_rate, profit_loss_rate_after_cost, daily_profit_loss_rate,
                    cash_balance_status, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'GENERAL', '****5678',
                    '{"USD":100}'::jsonb, '{"USD":120}'::jsonb, '{"USD":119}'::jsonb,
                    '{"USD":20}'::jsonb, '{"USD":19}'::jsonb, '{"USD":1}'::jsonb,
                    0.20, 0.19, 0.01, 'UNKNOWN', ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId, time, time);
        insertPosition(runId, userId, connectionId, "NVDA", time);
        insertCapacity(runId, userId, connectionId, "KRW", time);
        if (includeUsd) {
            insertCapacity(runId, userId, connectionId, "USD", time);
        }
        return runId;
    }

    private UUID insertSyncRun(UUID connectionId, UUID userId, OffsetDateTime time) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, userId, connectionId, time, time.plusSeconds(1));
        return runId;
    }

    private void insertPosition(
            UUID runId,
            UUID userId,
            UUID connectionId,
            String symbol,
            OffsetDateTime time
    ) {
        jdbc.update("""
                INSERT INTO position_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, symbol, name,
                    market_country, quantity, currency, average_price, last_price,
                    purchase_amount, market_value_amount, market_value_after_cost,
                    profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                    profit_loss_rate_after_cost, daily_profit_loss_amount,
                    daily_profit_loss_rate, commission, tax, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, 'NVIDIA', 'US', 1, 'USD', 100, 120,
                    100, 120, 119, 20, 19, 0.20, 0.19, 1, 0.01, 1, NULL, ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId, symbol, time, time);
    }

    private void insertCapacity(
            UUID runId,
            UUID userId,
            UUID connectionId,
            String currency,
            OffsetDateTime time
    ) {
        jdbc.update("""
                INSERT INTO account_capacity_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, currency,
                    cash_buying_power, observed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, 1000, ?, ?)
                """, UUID.randomUUID(), runId, userId, connectionId, currency, time, time);
    }

    private void insertFailedRun(UUID connectionId, UUID userId, OffsetDateTime time) {
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, error_code, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'FAILED', 'BROKER_TEMPORARY', ?, ?)
                """, UUID.randomUUID(), userId, connectionId, time, time.plusSeconds(1));
    }

    private void insertAnalysis(
            UUID connectionId,
            UUID userId,
            UUID snapshotId,
            OffsetDateTime time
    ) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_runs (
                    id, user_id, broker_connection_id, input_sync_run_id,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, ?)
                """, runId, userId, connectionId, snapshotId, time, time.plusSeconds(1));
        jdbc.update("""
                INSERT INTO analysis_results (
                    id, analysis_run_id, user_id, broker_connection_id, input_sync_run_id,
                    schema_version, result_status, response, created_at
                ) VALUES (?, ?, ?, ?, ?, '1', 'DEGRADED', CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), runId, userId, connectionId, snapshotId, """
                {
                  "requestId":"%s",
                  "schemaVersion":"1",
                  "asOf":"2026-07-28T00:00:00Z",
                  "status":"DEGRADED",
                  "quality":{
                    "stale":false,
                    "partial":false,
                    "unknownFields":[]
                  },
                  "positions":[{
                    "symbol":"NVDA",
                    "currency":"USD",
                    "marketValue":120,
                    "profitLoss":20,
                    "weight":1
                  }],
                  "currencyTotals":[{
                    "currency":"USD",
                    "marketValue":120,
                    "profitLoss":20,
                    "concentration":1
                  }]
                }
                """.formatted(runId), time.plusSeconds(1));
    }

    private UUID insertEvent(
            UUID connectionId,
            UUID userId,
            String sourceEventId,
            OffsetDateTime time
    ) {
        var eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO intelligence_events (
                    id, user_id, broker_connection_id, source, source_event_id,
                    event_type, summary, affected_symbols, occurred_at, collected_at
                ) VALUES (?, ?, ?, 'manual', ?, 'EARNINGS', 'summary',
                          '["NVDA"]'::jsonb, ?, ?)
                """, eventId, userId, connectionId, sourceEventId, time, time);
        return eventId;
    }

    private void insertOrder(
            UUID connectionId,
            UUID userId,
            String symbol,
            String status
    ) {
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?) ON CONFLICT DO NOTHING",
                connectionId);
        jdbc.update("""
                INSERT INTO order_intents (
                    id, broker_account_id, user_id, broker_connection_id, quantity,
                    side, order_type, symbol, limit_price, trading_currency, status, version
                ) VALUES (?, ?, ?, ?, 1, 'BUY', 'MARKET', ?, NULL, 'USD', ?, 0)
                """, UUID.randomUUID(), connectionId, userId, connectionId, symbol, status);
    }

    private void insertOrderWithStatus(
            UUID connectionId,
            UUID userId,
            String symbol,
            String status,
            OffsetDateTime createdAt
    ) {
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?) ON CONFLICT DO NOTHING",
                connectionId);
        var expiresAt = createdAt == null ? null : createdAt.plusMinutes(15);
        var terminal = java.util.Set.of(
                "COMPLETED", "PARTIALLY_COMPLETED", "CANCELED", "REJECTED", "EXPIRED", "BLOCKED");
        if (!terminal.contains(status)) {
            jdbc.update("""
                    INSERT INTO order_intents (
                        id, broker_account_id, user_id, broker_connection_id, quantity,
                        side, order_type, symbol, limit_price, trading_currency, status,
                        created_at, expires_at, version
                    ) VALUES (?, ?, ?, ?, 1, 'BUY', 'MARKET', ?, NULL, 'USD', ?, ?, ?, 0)
                    """, UUID.randomUUID(), connectionId, userId, connectionId, symbol, status,
                    createdAt, expiresAt);
            return;
        }
        var filled = "COMPLETED".equals(status) ? 1 : 0;
        var remaining = "COMPLETED".equals(status) ? 0 : 1;
        jdbc.update("""
                INSERT INTO order_intents (
                    id, broker_account_id, user_id, broker_connection_id, quantity,
                    side, order_type, symbol, limit_price, trading_currency, status,
                    terminal_reason, terminal_at, final_filled_quantity, remaining_quantity,
                    created_at, expires_at, version
                ) VALUES (?, ?, ?, ?, 1, 'BUY', 'MARKET', ?, NULL, 'USD', ?, 'TEST', ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), connectionId, userId, connectionId, symbol, status,
                createdAt, filled, remaining, createdAt, expiresAt);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {

        private final AtomicInteger queries = new AtomicInteger();

        private CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> T query(
                PreparedStatementCreator creator,
                PreparedStatementSetter setter,
                ResultSetExtractor<T> extractor
        ) {
            queries.incrementAndGet();
            return super.query(creator, setter, extractor);
        }

        private int count() {
            return queries.get();
        }

        private void reset() {
            queries.set(0);
        }
    }
}
