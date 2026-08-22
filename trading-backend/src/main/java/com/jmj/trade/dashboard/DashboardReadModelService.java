package com.jmj.trade.dashboard;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadException;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.analysis.PortfolioAnalysisContract;
import com.jmj.trade.analysis.PortfolioAnalysisWorkflowService;
import com.jmj.trade.analysis.StockAnalysisCoreContract;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import com.jmj.trade.intelligence.EventIntelligenceService;
import com.jmj.trade.order.OrderIntentStatus;
import com.jmj.trade.order.OrderExecutionMode;
import com.jmj.trade.order.OrderSide;
import com.jmj.trade.order.OrderType;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public final class DashboardReadModelService {

    private static final int COLLECTION_LIMIT = 100;

    /**
     * {@code riskLevel} 파생 규칙 (BC-2). 판정은 전부 서버가 한다 — 프론트는 이 문자열을 읽기만
     * 하고 다시 계산하지 않는다. 근거는 BC-4 {@code riskEvaluation} 의 {@code POSITION} 항목이며,
     * 그 항목이 없으면 {@code null} 이다(모름과 안전함은 다르다 — {@code LOW} 로 접지 않는다).
     *
     * <ul>
     *   <li>{@code breached == true} → {@code HIGH}</li>
     *   <li>{@code usageRatio >= 0.80} (한도의 80% 이상, 경계 포함) → {@code MEDIUM}</li>
     *   <li>그 외 항목이 존재 → {@code LOW}</li>
     *   <li>항목 없음(weight 누락 · 분석 결과 없음 · 정책 한도 없음) → {@code null}</li>
     * </ul>
     */
    private static final BigDecimal RISK_LEVEL_MEDIUM_USAGE_RATIO = new BigDecimal("0.80");
    private static final String RISK_LEVEL_HIGH = "HIGH";
    private static final String RISK_LEVEL_MEDIUM = "MEDIUM";
    private static final String RISK_LEVEL_LOW = "LOW";
    private static final String POSITION_CONCENTRATION_SCOPE = "POSITION";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final FreshPortfolioReadService portfolios;
    private final PortfolioAnalysisWorkflowService analyses;
    private final RiskPolicyService riskPolicies;

    DashboardReadModelService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            FreshPortfolioReadService portfolios,
            PortfolioAnalysisWorkflowService analyses,
            RiskPolicyService riskPolicies
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.portfolios = Objects.requireNonNull(portfolios, "portfolios");
        this.analyses = Objects.requireNonNull(analyses, "analyses");
        this.riskPolicies = Objects.requireNonNull(riskPolicies, "riskPolicies");
    }

    DashboardView read(UUID userId, UUID connectionId) {
        return read(userId, connectionId, OrderIntentStatus.dashboardOpenStatuses());
    }

    DashboardView read(
            UUID userId,
            UUID connectionId,
            Collection<OrderIntentStatus> proposalStatuses
    ) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(proposalStatuses, "proposalStatuses");
        requireOwnedConnection(userId, connectionId);

        PortfolioReadService.PortfolioView portfolio = null;
        try {
            portfolio = portfolios.read(userId, connectionId);
        } catch (PortfolioReadException ignored) {
            // Missing source is section quality, not whole-dashboard failure.
        }
        var analysis = analyses.latestOptional(userId, connectionId).orElse(null);
        var riskEvaluation = riskEvaluationSection(userId, analysis, portfolio);

        return new DashboardView(
                portfolioSection(portfolio),
                analysisSection(analysis, portfolio),
                riskEvaluation,
                positionDecisionSection(userId, portfolio, riskEvaluation),
                available(pendingEvents(userId, connectionId), false, List.of()),
                available(pendingProposals(userId, connectionId, proposalStatuses), false, List.of()));
    }

    private void requireOwnedConnection(UUID userId, UUID connectionId) {
        if (jdbc.queryForList("""
                SELECT 1
                  FROM broker_connections
                 WHERE id = ?
                   AND user_id = ?
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                """, Integer.class, connectionId, userId).isEmpty()) {
            throw BrokerConnectionException.notFound();
        }
    }

    private Section<PortfolioReadService.PortfolioView> portfolioSection(
            PortfolioReadService.PortfolioView portfolio
    ) {
        if (portfolio == null) {
            return unavailable("PORTFOLIO_SNAPSHOT_NOT_FOUND");
        }
        return available(portfolio, portfolio.stale(), portfolio.unknownFields());
    }

    private Section<PortfolioAnalysisWorkflowService.AnalysisView> analysisSection(
            PortfolioAnalysisWorkflowService.AnalysisView analysis,
            PortfolioReadService.PortfolioView portfolio
    ) {
        if (analysis == null) {
            return unavailable("ANALYSIS_RESULT_NOT_FOUND");
        }
        var quality = analysis.result().quality();
        return available(analysis, analysisStale(analysis, portfolio), quality.unknownFields());
    }

    private static boolean analysisStale(
            PortfolioAnalysisWorkflowService.AnalysisView analysis,
            PortfolioReadService.PortfolioView portfolio
    ) {
        return analysis.result().quality().stale()
                || portfolio != null && !analysis.inputSnapshotId().equals(portfolio.syncRunId());
    }

    /**
     * 포트폴리오 위험 평가 (BC-4). 한도 초과 판정은 <em>전부 서버가 계산</em>한다 — 프론트는
     * {@code breached}/{@code usageRatio} 를 읽기만 하고 다시 계산하지 않는다.
     *
     * <p>근거가 없으면 비어 있는 items 로 "위험 없음"을 단언하지 않고 섹션을 unavailable 로 내린다.
     * 분석이 저하(DEGRADED)됐거나 부분 데이터면 stale 로, 개별 값이 비면 그 항목을 만들지 않고
     * {@code unknownFields} 에 남긴다. 없는 값을 0 으로 대체하지 않는다.
     *
     * <p>지금 정책 모델에 있는 한도는 {@code maxConcentration} 하나뿐이므로 집중도 항목만 낸다.
     * 일일·주간 손실이나 섹터 한도는 정책에 존재하지 않으며, 없는 한도를 지어내지 않는다.
     */
    private Section<RiskEvaluationView> riskEvaluationSection(
            UUID userId,
            PortfolioAnalysisWorkflowService.AnalysisView analysis,
            PortfolioReadService.PortfolioView portfolio
    ) {
        if (analysis == null) {
            return unavailable("ANALYSIS_RESULT_NOT_FOUND");
        }
        var policy = riskPolicies.current(userId);
        var limit = policy.maxConcentration();
        if (limit == null) {
            return unavailable("RISK_POLICY_MAX_CONCENTRATION_UNAVAILABLE");
        }
        var result = analysis.result();
        var unknownFields = new ArrayList<>(result.quality().unknownFields());
        var items = new ArrayList<RiskEvaluationItem>();
        for (var position : orEmpty(result.positions())) {
            if (position.weight() == null) {
                unknownFields.add("positions[" + position.symbol() + "].weight");
                continue;
            }
            items.add(concentrationItem(
                    "POSITION_CONCENTRATION", "POSITION", position.symbol(), position.weight(), limit));
        }
        for (var total : orEmpty(result.currencyTotals())) {
            if (total.concentration() == null) {
                unknownFields.add("currencyTotals[" + total.currency() + "].concentration");
                continue;
            }
            items.add(concentrationItem(
                    "CURRENCY_CONCENTRATION", "CURRENCY", total.currency().name(),
                    total.concentration(), limit));
        }
        var stale = analysisStale(analysis, portfolio)
                || result.status() == PortfolioAnalysisContract.Status.DEGRADED
                || result.quality().partial();
        return available(
                new RiskEvaluationView(policy.version(), Instant.now(), List.copyOf(items)),
                stale,
                unknownFields);
    }

    private static RiskEvaluationItem concentrationItem(
            String keyPrefix,
            String scope,
            String subject,
            BigDecimal current,
            BigDecimal limit
    ) {
        return new RiskEvaluationItem(
                keyPrefix + ":" + subject,
                scope,
                subject,
                current,
                limit,
                // 한도가 0 이면 사용률은 정의되지 않는다. 0 이나 무한대로 대체하지 않고 비운다.
                limit.signum() == 0 ? null : current.divide(limit, MathContext.DECIMAL128),
                current.compareTo(limit) > 0);
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 보유 포지션별 판단 뷰 (BC-2). 판단을 <em>새로 계산하지 않는다</em> — 이미 저장된 종목 분석
     * 실행 결과를 심볼로 조인해 그대로 전달하고, {@code riskLevel} 만 이미 서버가 내린 BC-4
     * 집중도 판정에서 파생한다.
     *
     * <p>기존 {@code portfolio}/{@code riskEvaluation} 섹션을 건드리지 않으려고 별도 최상위 섹션으로
     * 낸다. {@code PortfolioReadService.PositionView} 는 브로커 스냅샷의 사실만 담는 계약이고,
     * 여기 값들은 출처(집중도 판정 · 종목 분석 실행)도 신선도도 다르다 — 한 레코드에 섞으면 어느
     * 필드가 언제 기준인지 구분할 수 없게 된다.
     *
     * <p>행의 기준 집합은 <em>보유 포지션</em>이다. 분석이 없어도 행은 존재하고 판단 필드만 비며,
     * 그래야 프론트가 "판단이 없는 보유 종목"을 식별할 수 있다. 판단의 신선도는 섹션 단위
     * {@code stale} 이 아니라 행마다 실린 {@code decisionAsOf} 로 판단한다 — 오래된 판단을 최신인
     * 것처럼 보이게 하지 않기 위해서다.
     */
    private Section<List<PositionDecisionView>> positionDecisionSection(
            UUID userId,
            PortfolioReadService.PortfolioView portfolio,
            Section<RiskEvaluationView> riskEvaluation
    ) {
        if (portfolio == null) {
            return unavailable("PORTFOLIO_SNAPSHOT_NOT_FOUND");
        }
        var symbols = heldSymbols(portfolio);
        var riskLevels = positionRiskLevels(riskEvaluation);
        var decisions = latestDecisions(userId, symbols);
        var unknownFields = new ArrayList<String>();
        var views = new ArrayList<PositionDecisionView>(symbols.size());
        for (var symbol : symbols) {
            var riskLevel = riskLevels.get(symbol);
            if (riskLevel == null) {
                unknownFields.add("positions[" + symbol + "].riskLevel");
            }
            var stored = decisions.get(symbol.toUpperCase(Locale.ROOT));
            var decision = stored == null ? null : stored.decision();
            views.add(new PositionDecisionView(
                    symbol,
                    riskLevel,
                    decision == null || decision.action() == null ? null : decision.action().name(),
                    decision == null ? null : decision.confidence(),
                    decision == null ? null : decision.ruleVersion(),
                    stored == null ? null : stored.asOf(),
                    stored == null ? null : stored.runId()));
        }
        return available(List.copyOf(views), portfolio.stale(), unknownFields);
    }

    private static List<String> heldSymbols(PortfolioReadService.PortfolioView portfolio) {
        var symbols = new ArrayList<String>();
        for (var position : orEmpty(portfolio.positions())) {
            if (position.symbol() != null && !symbols.contains(position.symbol())) {
                symbols.add(position.symbol());
            }
        }
        return symbols;
    }

    /** 이미 계산된 BC-4 판정을 다시 계산하지 않고 등급 문자열로만 옮긴다. */
    private static Map<String, String> positionRiskLevels(Section<RiskEvaluationView> section) {
        var evaluation = section.data();
        if (evaluation == null) {
            return Map.of();
        }
        var levels = new HashMap<String, String>();
        for (var item : orEmpty(evaluation.items())) {
            if (POSITION_CONCENTRATION_SCOPE.equals(item.scope())) {
                levels.put(item.subject(), riskLevel(item));
            }
        }
        return levels;
    }

    private static String riskLevel(RiskEvaluationItem item) {
        if (item.breached()) {
            return RISK_LEVEL_HIGH;
        }
        if (item.usageRatio() != null
                && item.usageRatio().compareTo(RISK_LEVEL_MEDIUM_USAGE_RATIO) >= 0) {
            return RISK_LEVEL_MEDIUM;
        }
        return RISK_LEVEL_LOW;
    }

    /**
     * 보유 심볼 전체의 최신 성공 실행을 <em>한 번의 쿼리</em>로 가져온다 (심볼당 한 번씩 도는 N+1 금지).
     * {@code DISTINCT ON (symbol)} 이 심볼별 첫 행만 남기므로 애플리케이션에서 다시 추리지 않는다.
     * 사용자 격리는 {@code run.user_id = ?} 와 결과 조인의 {@code result.user_id = run.user_id} 로
     * 걸린다 — 다른 사용자의 같은 심볼 분석은 애초에 후보에 들어오지 않는다.
     */
    private Map<String, StoredDecision> latestDecisions(UUID userId, List<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        var normalized = symbols.stream()
                .map(symbol -> symbol.toUpperCase(Locale.ROOT))
                .distinct()
                .toArray(String[]::new);
        var decisions = new HashMap<String, StoredDecision>();
        jdbc.query("""
                SELECT DISTINCT ON (run.symbol)
                       run.symbol,
                       run.id AS run_id,
                       (result.response -> 'asOf')::text AS as_of,
                       (result.response -> 'decision')::text AS decision
                  FROM stock_analysis_runs run
                  JOIN stock_analysis_results result
                    ON result.stock_analysis_run_id = run.id
                   AND result.user_id = run.user_id
                   AND result.input_snapshot_id = run.input_snapshot_id
                 WHERE run.user_id = ?
                   AND run.status = 'SUCCEEDED'
                   AND run.symbol = ANY(?)
                 ORDER BY run.symbol, run.completed_at DESC, run.id DESC
                """,
                (PreparedStatementSetter) ps -> {
                    ps.setObject(1, userId);
                    ps.setArray(2, ps.getConnection().createArrayOf("text", normalized));
                },
                (resultSet, rowNumber) -> new StoredDecision(
                        resultSet.getString("symbol"),
                        resultSet.getObject("run_id", UUID.class),
                        decodeAsOf(resultSet.getString("as_of")),
                        decodeDecision(resultSet.getString("decision"))))
                .forEach(stored -> decisions.put(stored.symbol(), stored));
        return decisions;
    }

    private Instant decodeAsOf(String json) {
        if (json == null || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Instant.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored stock analysis asOf is invalid", exception);
        }
    }

    private StockAnalysisCoreContract.Decision decodeDecision(String json) {
        if (json == null || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, StockAnalysisCoreContract.Decision.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored stock analysis decision is invalid", exception);
        }
    }

    private record StoredDecision(
            String symbol,
            UUID runId,
            Instant asOf,
            StockAnalysisCoreContract.Decision decision
    ) {
    }

    private List<PendingEventView> pendingEvents(UUID userId, UUID connectionId) {
        return jdbc.query("""
                SELECT event.id, event.source, event.source_event_id, event.event_type,
                       event.summary, event.affected_symbols::text, event.occurred_at,
                       event.macro_scope::text, event.collected_at,
                       EXISTS (
                           SELECT 1
                             FROM event_analysis_comparisons comparison
                            WHERE comparison.event_id = event.id
                              AND comparison.user_id = event.user_id
                              AND comparison.broker_connection_id =
                                  event.broker_connection_id
                       ) AS comparison_available
                  FROM intelligence_events event
                  LEFT JOIN event_reviews review
                    ON review.event_id = event.id
                   AND review.user_id = event.user_id
                   AND review.broker_connection_id = event.broker_connection_id
                 WHERE event.user_id = ?
                   AND event.broker_connection_id = ?
                   AND review.event_id IS NULL
                 ORDER BY event.collected_at DESC, event.id DESC
                 LIMIT ?
                """, (resultSet, rowNumber) -> new PendingEventView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source"),
                resultSet.getString("source_event_id"),
                resultSet.getString("event_type"),
                resultSet.getString("summary"),
                decodeSymbols(resultSet.getString("affected_symbols")),
                decodeMacroScope(resultSet.getString("macro_scope")),
                instant(resultSet.getObject("occurred_at", OffsetDateTime.class)),
                instant(resultSet.getObject("collected_at", OffsetDateTime.class)),
                resultSet.getBoolean("comparison_available")
        ), userId, connectionId, COLLECTION_LIMIT);
    }

    private List<PendingProposalView> pendingProposals(
            UUID userId,
            UUID connectionId,
            Collection<OrderIntentStatus> statuses
    ) {
        // 상태는 바인딩된 배열(= ANY(?))로만 필터한다. 상태 이름을 SQL 문자열에 절대 삽입하지 않는다.
        var statusNames = statuses.stream().map(Enum::name).toArray(String[]::new);
        return jdbc.query("""
                SELECT id, execution_mode, side, order_type, symbol, quantity, limit_price,
                       trading_currency, status, created_at, expires_at
                  FROM order_intents
                 WHERE user_id = ?
                   AND broker_connection_id = ?
                   AND status = ANY(?)
                 ORDER BY created_at DESC NULLS LAST, symbol, id
                 LIMIT ?
                """,
                (PreparedStatementSetter) ps -> {
                    ps.setObject(1, userId);
                    ps.setObject(2, connectionId);
                    ps.setArray(3, ps.getConnection().createArrayOf("text", statusNames));
                    ps.setInt(4, COLLECTION_LIMIT);
                },
                (resultSet, rowNumber) -> new PendingProposalView(
                        resultSet.getObject("id", UUID.class),
                        OrderExecutionMode.valueOf(resultSet.getString("execution_mode")),
                        OrderSide.valueOf(resultSet.getString("side")),
                        OrderType.valueOf(resultSet.getString("order_type")),
                        resultSet.getString("symbol"),
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("limit_price"),
                        Currency.valueOf(resultSet.getString("trading_currency")),
                        OrderIntentStatus.valueOf(resultSet.getString("status")),
                        instantOrNull(resultSet.getObject("created_at", OffsetDateTime.class)),
                        instantOrNull(resultSet.getObject("expires_at", OffsetDateTime.class))));
    }

    private List<String> decodeSymbols(String json) {
        try {
            return List.copyOf(List.of(objectMapper.readValue(json, String[].class)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored event symbols are invalid", exception);
        }
    }

    private List<EventIntelligenceService.MacroScope> decodeMacroScope(String json) {
        try {
            return List.of(objectMapper.readValue(
                    json, EventIntelligenceService.MacroScope[].class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored event macro scope is invalid", exception);
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private static Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static <T> Section<T> available(
            T data,
            boolean stale,
            List<String> unknownFields
    ) {
        var fields = List.copyOf(unknownFields);
        return new Section<>(stale, !fields.isEmpty(), fields, false, null, data);
    }

    private static <T> Section<T> unavailable(String reason) {
        return new Section<>(false, false, List.of(), true, reason, null);
    }

    public record DashboardView(
            Section<PortfolioReadService.PortfolioView> portfolio,
            Section<PortfolioAnalysisWorkflowService.AnalysisView> analysis,
            Section<RiskEvaluationView> riskEvaluation,
            Section<List<PositionDecisionView>> positionDecisions,
            Section<List<PendingEventView>> pendingEvents,
            Section<List<PendingProposalView>> pendingOrderProposals
    ) {
    }

    /**
     * 보유 포지션 한 건의 위험 등급과 저장된 판단 (BC-2).
     *
     * <p>{@code riskLevel} 은 서버가 BC-4 집중도 판정에서 파생한다. 나머지는 이미 저장된 종목 분석
     * 실행에서 그대로 읽어온 값이며 여기서 다시 계산하지 않는다.
     *
     * <p>{@code decisionRunId}/{@code decisionAsOf} 는 성공한 분석 실행이 있으면 그 실행이 판단을
     * 냈는지와 무관하게 채워진다. 따라서 {@code decisionRunId == null} 은 "분석한 적 없음"이고,
     * {@code decisionRunId != null && decision == null} 은 "분석했지만 지표가 모자라 판단이 없음"으로
     * 서로 구분된다. {@code HOLD} 같은 기본값으로 대체하지 않는다.
     */
    public record PositionDecisionView(
            String symbol,
            String riskLevel,
            String decision,
            BigDecimal confidence,
            String decisionRuleVersion,
            Instant decisionAsOf,
            UUID decisionRunId
    ) {
    }

    public record RiskEvaluationView(
            long policyVersion,
            Instant evaluatedAt,
            List<RiskEvaluationItem> items
    ) {
    }

    public record RiskEvaluationItem(
            String key,
            String scope,
            String subject,
            BigDecimal current,
            BigDecimal limit,
            BigDecimal usageRatio,
            boolean breached
    ) {
    }

    public record Section<T>(
            boolean stale,
            boolean unknown,
            List<String> unknownFields,
            boolean unavailable,
            String unavailableReason,
            T data
    ) {
    }

    public record PendingEventView(
            UUID id,
            String source,
            String sourceEventId,
            String type,
            String summary,
            List<String> affectedSymbols,
            List<EventIntelligenceService.MacroScope> macroScope,
            Instant occurredAt,
            Instant collectedAt,
            boolean comparisonAvailable
    ) {
    }

    public record PendingProposalView(
            UUID id,
            OrderExecutionMode executionMode,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency,
            OrderIntentStatus status,
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}
