package com.jmj.trade.order;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.connection.BrokerConnectionException;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class PreTradeRiskEngine {

    /**
     * 아직 intent 가 없는 평가(미리보기)에서 "제외할 intent 없음"을 나타내는 sentinel. 무작위 UUID 로
     * 생성되는 실제 intent id 와 절대 충돌하지 않으므로 {@code order_intent_id <> ?} 필터가 아무 행도
     * 제외하지 않는다 — 미리보기는 기존 예약분을 전부 계산에 넣는 것이 옳다.
     */
    private static final UUID NO_INTENT = new UUID(0L, 0L);

    private final PortfolioReadService portfolioReadService;
    private final FreshPortfolioReadService freshPortfolioReadService;
    private final PaperTradingBroker paperTradingBroker;
    private final OrderIntentRepository intentRepository;
    private final OrderIntentTransitionService transitionService;
    private final JdbcTemplate jdbc;
    private final RiskPolicyService riskPolicyService;
    private final List<PreSubmitRevalidationCheck> preSubmitChecks;

    public PreTradeRiskEngine(
            PortfolioReadService portfolioReadService,
            FreshPortfolioReadService freshPortfolioReadService,
            PaperTradingBroker paperTradingBroker,
            OrderIntentRepository intentRepository,
            OrderIntentTransitionService transitionService,
            JdbcTemplate jdbc,
            RiskPolicyService riskPolicyService,
            List<PreSubmitRevalidationCheck> preSubmitChecks
    ) {
        this.portfolioReadService = Objects.requireNonNull(portfolioReadService, "portfolioReadService");
        this.freshPortfolioReadService = Objects.requireNonNull(
                freshPortfolioReadService, "freshPortfolioReadService");
        this.paperTradingBroker = Objects.requireNonNull(paperTradingBroker, "paperTradingBroker");
        this.intentRepository = Objects.requireNonNull(intentRepository, "intentRepository");
        this.transitionService = Objects.requireNonNull(transitionService, "transitionService");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.riskPolicyService = Objects.requireNonNull(riskPolicyService, "riskPolicyService");
        this.preSubmitChecks = List.copyOf(Objects.requireNonNull(preSubmitChecks, "preSubmitChecks"));
    }

    @Transactional
    public Decision approve(ApprovalCommand command) {
        return approve(command, OrderExecutionMode.PAPER, null);
    }

    @Transactional
    public Decision approveLive(ApprovalCommand command) {
        requireApprovalCommand(command);
        return approveLive(command, freshPortfolioReadService.read(command.userId(), command.connectionId()));
    }

    @Transactional
    public Decision approveLive(
            ApprovalCommand command,
            PortfolioReadService.PortfolioView syncedPortfolio
    ) {
        requireApprovalCommand(command);
        return approve(command, OrderExecutionMode.LIVE, syncedPortfolio);
    }

    @Transactional
    public Decision reviewLiveModification(UUID userId, UUID connectionId, UUID orderIntentId,
                                           BigDecimal referencePrice, Instant evaluatedAt, String actor) {
        requireId(userId, "userId");
        requireId(connectionId, "connectionId");
        requireId(orderIntentId, "orderIntentId");
        Objects.requireNonNull(referencePrice, "referencePrice");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        lockConnection(userId, connectionId);
        var intent = lockedIntent(orderIntentId, userId, connectionId);
        requireCompleteIntent(intent, OrderExecutionMode.LIVE);
        if (intent.getStatus() != OrderIntentStatus.ACTIVE) {
            throw new IllegalStateException("live modification risk review requires ACTIVE intent");
        }
        var decision = evaluate(
                Phase.FINAL,
                userId,
                connectionId,
                Subject.of(intent),
                referencePrice,
                evaluatedAt,
                freshPortfolioReadService.read(userId, connectionId));
        store(decision, userId, connectionId, orderIntentId);
        return decision;
    }

    /**
     * 비영속 사전 위험 미리보기 (BC-6). 아직 존재하지 않는 주문을 승인 경로와 <em>동일한</em> 평가
     * 코어({@link #evaluate})로 판정하되, 어떤 상태도 남기지 않는다 — intent 도, 위험 판정 행도,
     * outbox/감사 이벤트도, buying power 예약도 만들지 않는다.
     *
     * <p>{@code readOnly = true} 는 의도 선언이자 방어선이다(Hibernate flush 억제 + 드라이버 힌트).
     * 다만 이것만으로 쓰기가 물리적으로 불가능해진다고 가정하지 말 것 — 실제 보증은 이 경로가
     * {@link #store} 를 부르지 않는다는 사실이고, 그 사실은
     * {@code PaperOrderWorkflowApiIntegrationTest} 가 관련 테이블 행 수 0 으로 검증한다.
     *
     * <p>평가 단계는 승인과 같은 {@link Phase#APPROVAL} 이다. 제출 직전 재검증(FINAL)은 미리보기가
     * 대신할 수 없는 최종 방어선이며, 이 응답은 승인 시점의 재검사를 결코 건너뛰게 하지 않는다.
     */
    @Transactional(readOnly = true)
    public Decision preview(PreviewCommand command) {
        requirePreviewCommand(command);
        // 소유권 검사는 승인 경로와 동일한 기준(본인 소유의 ACTIVE 연결)이다. 미리보기라고 완화하지
        // 않는다. 다만 쓰기가 없으므로 행 잠금(FOR UPDATE)은 잡지 않는다.
        requireOwnedConnection(command.userId(), command.connectionId());
        var subject = Subject.preview(
                command.userId(),
                command.connectionId(),
                command.side(),
                command.type(),
                command.symbol(),
                command.quantity(),
                command.limitPrice(),
                command.currency());
        return evaluate(
                Phase.APPROVAL,
                command.userId(),
                command.connectionId(),
                subject,
                command.referencePrice(),
                command.evaluatedAt(),
                portfolioReadService.read(command.userId(), command.connectionId()));
    }

    private Decision approve(
            ApprovalCommand command,
            OrderExecutionMode expectedMode,
            PortfolioReadService.PortfolioView syncedPortfolio
    ) {
        requireApprovalCommand(command);
        lockConnection(command.userId(), command.connectionId());
        var intent = lockedIntent(command.orderIntentId(), command.userId(), command.connectionId());
        requireCompleteIntent(intent, expectedMode);
        if (intent.getStatus() != OrderIntentStatus.PROPOSED) {
            throw new IllegalStateException("risk approval requires PROPOSED intent");
        }
        var portfolio = currentPortfolio(command.userId(), command.connectionId(), syncedPortfolio);
        var decision = evaluate(
                Phase.APPROVAL,
                command.userId(),
                command.connectionId(),
                Subject.of(intent),
                command.referencePrice(),
                command.evaluatedAt(),
                portfolio);
        store(decision, command.userId(), command.connectionId(), intent.getId());
        if (decision.approved()) {
            transitionService.approve(intent.getId(), command.actor());
        }
        return decision;
    }

    @Transactional
    public SubmissionResult submitPaper(
            UUID userId,
            UUID connectionId,
            PaperTradingBroker.SubmitCommand command
    ) {
        requireId(userId, "userId");
        requireId(connectionId, "connectionId");
        if (command == null) {
            throw new IllegalArgumentException("paper submit command is required");
        }
        lockConnection(userId, connectionId);
        var intent = lockedIntent(command.orderIntentId(), userId, connectionId);
        requireCompleteIntent(intent, OrderExecutionMode.PAPER);
        if (intent.getStatus() != OrderIntentStatus.APPROVED) {
            throw new IllegalStateException("final risk validation requires APPROVED intent");
        }
        if (!hasApprovedDecision(userId, connectionId, intent.getId())) {
            throw new IllegalStateException("approved pre-trade risk decision is required");
        }

        transitionService.startRevalidation(intent.getId(), command.actor());
        var portfolio = portfolioReadService.read(userId, connectionId);
        var decision = evaluate(
                Phase.FINAL,
                userId,
                connectionId,
                Subject.of(intent),
                command.referencePrice(),
                command.occurredAt(),
                portfolio);
        store(decision, userId, connectionId, intent.getId());
        if (!decision.approved()) {
            transitionService.terminate(
                    intent.getId(),
                    OrderIntentStatus.BLOCKED,
                    decision.reasons().getFirst().name(),
                    command.occurredAt(),
                    BigDecimal.ZERO,
                    command.actor());
            return new SubmissionResult(decision, null);
        }

        transitionService.markSubmissionPending(intent.getId(), command.actor());
        return new SubmissionResult(decision, paperTradingBroker.submit(command));
    }

    @Transactional
    public LiveSubmission submitLive(UUID userId, UUID connectionId, UUID orderIntentId,
                                     BigDecimal referencePrice, Instant occurredAt, String actor) {
        validateLiveSubmission(userId, connectionId, orderIntentId, referencePrice, occurredAt, actor);
        return submitLive(
                userId,
                connectionId,
                orderIntentId,
                referencePrice,
                occurredAt,
                actor,
                freshPortfolioReadService.read(userId, connectionId));
    }

    @Transactional
    public LiveSubmission submitLive(UUID userId, UUID connectionId, UUID orderIntentId,
                                     BigDecimal referencePrice, Instant occurredAt, String actor,
                                     PortfolioReadService.PortfolioView syncedPortfolio) {
        validateLiveSubmission(userId, connectionId, orderIntentId, referencePrice, occurredAt, actor);
        lockConnection(userId, connectionId);
        var intent = lockedIntent(orderIntentId, userId, connectionId);
        requireCompleteIntent(intent, OrderExecutionMode.LIVE);
        if (intent.getStatus() != OrderIntentStatus.APPROVED) {
            throw new IllegalStateException("live dispatch requires APPROVED intent");
        }
        if (!hasApprovedDecision(userId, connectionId, intent.getId())) {
            throw new IllegalStateException("approved pre-trade risk decision is required");
        }
        transitionService.startRevalidation(intent.getId(), actor);
        var portfolio = currentPortfolio(userId, connectionId, syncedPortfolio);
        var decision = evaluate(
                Phase.FINAL, userId, connectionId, Subject.of(intent), referencePrice, occurredAt, portfolio);
        store(decision, userId, connectionId, intent.getId());
        if (!decision.approved()) {
            transitionService.terminate(intent.getId(), OrderIntentStatus.BLOCKED,
                    decision.reasons().getFirst().name(), occurredAt, BigDecimal.ZERO, actor);
        } else {
            transitionService.markSubmissionPending(intent.getId(), actor);
        }
        return new LiveSubmission(
                decision,
                intent.getId(),
                intent.getBrokerAccountId(),
                intent.getSide(),
                intent.getType(),
                intent.getSymbol(),
                intent.getQuantity(),
                intent.getLimitPrice(),
                intent.getTradingCurrency());
    }

    private static void validateLiveSubmission(
            UUID userId,
            UUID connectionId,
            UUID orderIntentId,
            BigDecimal referencePrice,
            Instant occurredAt,
            String actor
    ) {
        requireId(userId, "userId");
        requireId(connectionId, "connectionId");
        requireId(orderIntentId, "orderIntentId");
        positive(referencePrice, "referencePrice");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }

    private PortfolioReadService.PortfolioView currentPortfolio(
            UUID userId,
            UUID connectionId,
            PortfolioReadService.PortfolioView syncedPortfolio
    ) {
        var latest = portfolioReadService.read(userId, connectionId);
        if (syncedPortfolio != null
                && syncedPortfolio.stale()
                && Objects.equals(syncedPortfolio.syncRunId(), latest.syncRunId())) {
            return syncedPortfolio;
        }
        return latest;
    }

    /**
     * 위험 판정 코어. 승인(APPROVAL)·제출 직전 재검증(FINAL)·비영속 미리보기가 모두 이 한 곳을 쓴다.
     * 순수 평가만 하고 아무것도 저장하지 않는다 — 영속화는 호출측의 {@link #store} 책임이다. 규칙이
     * 두 벌로 갈라지면 미리보기와 실제 승인 결과가 어긋나 사용자를 속이게 되므로 분기시키지 말 것.
     */
    private Decision evaluate(
            Phase phase,
            UUID userId,
            UUID connectionId,
            Subject subject,
            BigDecimal referencePrice,
            Instant evaluatedAt,
            PortfolioReadService.PortfolioView portfolio
    ) {
        var policy = riskPolicyService.current(userId);
        var reasons = new ArrayList<Reason>();
        if (portfolio.stale()) {
            reasons.add(Reason.STALE_SNAPSHOT);
        }
        if (portfolio.partial()) {
            reasons.add(Reason.PARTIAL_SNAPSHOT);
        }
        var price = subject.type() == OrderType.LIMIT ? subject.limitPrice() : referencePrice;
        var orderAmount = price.multiply(subject.quantity());
        if (subject.quantity().compareTo(policy.maxQuantity()) > 0) {
            reasons.add(Reason.MAX_QUANTITY_EXCEEDED);
        }
        if (orderAmount.compareTo(maxOrderAmount(policy, subject.currency())) > 0) {
            reasons.add(Reason.MAX_ORDER_AMOUNT_EXCEEDED);
        }
        if (subject.side() == OrderSide.BUY) {
            evaluateBuyLimits(
                    userId,
                    connectionId,
                    subject,
                    portfolio,
                    orderAmount,
                    policy.maxConcentration(),
                    reasons);
        }

        if (phase == Phase.FINAL) {
            runPreSubmitRevalidation(userId, connectionId, subject, orderAmount, portfolio, reasons);
        }

        return new Decision(
                UUID.randomUUID(),
                phase,
                reasons.isEmpty(),
                List.copyOf(reasons),
                portfolio.syncRunId(),
                orderAmount,
                subject.currency(),
                policy.version(),
                evaluatedAt);
    }

    private void evaluateBuyLimits(
            UUID userId,
            UUID connectionId,
            Subject subject,
            PortfolioReadService.PortfolioView portfolio,
            BigDecimal orderAmount,
            BigDecimal maxConcentration,
            List<Reason> reasons
    ) {
        var reserved = reserved(
                userId,
                connectionId,
                portfolio.syncRunId(),
                subject.currency(),
                subject.symbol(),
                subject.excludeIntentId());
        var buyingPower = portfolio.buyingPower().get(subject.currency().name());
        if (buyingPower != null
                && orderAmount.compareTo(buyingPower.cashBuyingPower().subtract(reserved.total())) > 0) {
            reasons.add(Reason.BUYING_POWER_EXCEEDED);
        }
        if (portfolio.account() == null) {
            return;
        }

        var portfolioValue = portfolio.account().marketValueAmounts()
                .getOrDefault(subject.currency().name(), BigDecimal.ZERO);
        var symbolValue = portfolio.positions().stream()
                .filter(position -> position.currency().equals(subject.currency().name()))
                .filter(position -> position.symbol().equals(subject.symbol()))
                .map(PortfolioReadService.PositionView::marketValueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var projectedTotal = portfolioValue.add(reserved.total()).add(orderAmount);
        var projectedSymbol = symbolValue.add(reserved.symbol()).add(orderAmount);
        if (projectedTotal.signum() == 0
                || projectedSymbol.divide(projectedTotal, MathContext.DECIMAL128)
                .compareTo(maxConcentration) > 0) {
            reasons.add(Reason.CONCENTRATION_EXCEEDED);
        }
    }

    private void runPreSubmitRevalidation(
            UUID userId,
            UUID connectionId,
            Subject subject,
            BigDecimal orderAmount,
            PortfolioReadService.PortfolioView portfolio,
            List<Reason> reasons
    ) {
        var context = new PreSubmitContext(
                userId,
                connectionId,
                subject.intentUserId(),
                subject.intentConnectionId(),
                subject.side(),
                subject.symbol(),
                subject.quantity(),
                subject.currency(),
                orderAmount,
                portfolio,
                sameSymbolOpenOrderExists(connectionId, subject.symbol(), subject.excludeIntentId()));
        for (var check : preSubmitChecks) {
            check.evaluate(context).ifPresent(reasons::add);
        }
    }

    private boolean sameSymbolOpenOrderExists(UUID connectionId, String symbol, UUID excludeIntentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM order_intents
                     WHERE broker_connection_id = ?
                       AND symbol = ?
                       AND id <> ?
                       AND status IN (
                           'SUBMISSION_PENDING', 'ACTIVE',
                           'RECONCILIATION_REQUIRED', 'MANUAL_REVIEW_REQUIRED')
                )
                """, Boolean.class, connectionId, symbol, excludeIntentId));
    }

    private Reservation reserved(
            UUID userId,
            UUID connectionId,
            UUID snapshotId,
            Currency currency,
            String symbol,
            UUID currentIntentId
    ) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(decision.order_amount), 0) AS total,
                       COALESCE(SUM(
                           CASE WHEN intent.symbol = ? THEN decision.order_amount ELSE 0 END
                       ), 0) AS symbol
                  FROM pre_trade_risk_decisions decision
                  JOIN order_intents intent ON intent.id = decision.order_intent_id
                 WHERE decision.user_id = ?
                   AND decision.broker_connection_id = ?
                   AND decision.snapshot_id = ?
                   AND decision.phase = 'FINAL'
                   AND decision.outcome = 'APPROVED'
                   AND decision.currency = ?
                   AND decision.order_intent_id <> ?
                   AND intent.side = 'BUY'
                   AND intent.status NOT IN ('REJECTED', 'BLOCKED', 'EXPIRED', 'CANCELED')
                """, (resultSet, rowNumber) -> new Reservation(
                resultSet.getBigDecimal("total"),
                resultSet.getBigDecimal("symbol")),
                symbol,
                userId,
                connectionId,
                snapshotId,
                currency.name(),
                currentIntentId);
    }

    private void store(Decision decision, UUID userId, UUID connectionId, UUID intentId) {
        jdbc.update("""
                INSERT INTO pre_trade_risk_decisions (
                    id, order_intent_id, user_id, broker_connection_id, snapshot_id,
                    phase, outcome, reason_codes, order_amount, currency,
                    risk_policy_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                decision.id(),
                intentId,
                userId,
                connectionId,
                decision.snapshotId(),
                decision.phase().name(),
                decision.approved() ? "APPROVED" : "BLOCKED",
                decision.reasons().stream().map(Enum::name).reduce((left, right) -> left + "," + right).orElse(""),
                decision.orderAmount(),
                decision.currency().name(),
                decision.riskPolicyVersion(),
                OffsetDateTime.ofInstant(decision.evaluatedAt(), ZoneOffset.UTC));
    }

    private boolean hasApprovedDecision(UUID userId, UUID connectionId, UUID intentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM pre_trade_risk_decisions
                     WHERE order_intent_id = ?
                       AND user_id = ?
                       AND broker_connection_id = ?
                       AND phase = 'APPROVAL'
                       AND outcome = 'APPROVED'
                )
                """, Boolean.class, intentId, userId, connectionId));
    }

    private void lockConnection(UUID userId, UUID connectionId) {
        if (jdbc.queryForList("""
                SELECT id
                  FROM broker_connections
                 WHERE id = ?
                   AND user_id = ?
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                 FOR UPDATE
                """, UUID.class, connectionId, userId).size() != 1) {
            throw BrokerConnectionException.notFound();
        }
    }

    /**
     * 잠금 없는 소유권 검사. 조건은 {@link #lockConnection} 과 동일하되 {@code FOR UPDATE} 를 잡지
     * 않는다 — 읽기 전용 트랜잭션에서는 행 잠금을 걸 수 없고, 쓰기가 없으므로 걸 이유도 없다.
     */
    private void requireOwnedConnection(UUID userId, UUID connectionId) {
        if (jdbc.queryForList("""
                SELECT id
                  FROM broker_connections
                 WHERE id = ?
                   AND user_id = ?
                   AND status = 'ACTIVE'
                   AND deleted_at IS NULL
                """, UUID.class, connectionId, userId).size() != 1) {
            throw BrokerConnectionException.notFound();
        }
    }

    private OrderIntent lockedIntent(UUID intentId, UUID userId, UUID connectionId) {
        requireId(intentId, "orderIntentId");
        return intentRepository.findOwnedByIdForUpdate(intentId, userId, connectionId)
                .orElseThrow(BrokerConnectionException::notFound);
    }

    private static BigDecimal maxOrderAmount(RiskPolicyService.RiskPolicySnapshot policy, Currency currency) {
        return currency == Currency.KRW ? policy.maxOrderAmountKrw() : policy.maxOrderAmountUsd();
    }

    private static void requireApprovalCommand(ApprovalCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("approval command is required");
        }
        requireId(command.userId(), "userId");
        requireId(command.connectionId(), "connectionId");
        requireId(command.orderIntentId(), "orderIntentId");
        positive(command.referencePrice(), "referencePrice");
        Objects.requireNonNull(command.evaluatedAt(), "evaluatedAt");
        if (command.actor() == null || command.actor().isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }

    private static void requirePreviewCommand(PreviewCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("preview command is required");
        }
        requireId(command.userId(), "userId");
        requireId(command.connectionId(), "connectionId");
        positive(command.referencePrice(), "referencePrice");
        Objects.requireNonNull(command.evaluatedAt(), "evaluatedAt");
        if (command.side() == null
                || command.type() == null
                || command.symbol() == null
                || command.symbol().isBlank()
                || command.currency() == null) {
            throw new IllegalArgumentException("risk preview requires a complete order subject");
        }
        positive(command.quantity(), "quantity");
        if (command.type() == OrderType.LIMIT) {
            positive(command.limitPrice(), "limitPrice");
        } else if (command.limitPrice() != null) {
            throw new IllegalArgumentException("MARKET order must not have limitPrice");
        }
    }

    private static void requireCompleteIntent(OrderIntent intent, OrderExecutionMode expectedMode) {
        if (intent.getExecutionMode() != expectedMode) {
            throw new IllegalStateException("order execution mode is not " + expectedMode);
        }
        if (intent.getSide() == null
                || intent.getType() == null
                || intent.getSymbol() == null
                || intent.getTradingCurrency() == null
                || intent.getType() == OrderType.LIMIT && intent.getLimitPrice() == null) {
            throw new IllegalStateException("risk validation requires complete paper order intent");
        }
    }

    private static void requireId(UUID id, String fieldName) {
        if (id == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static BigDecimal positive(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    public enum Phase {
        APPROVAL,
        FINAL
    }

    public enum Reason {
        STALE_SNAPSHOT,
        PARTIAL_SNAPSHOT,
        BUYING_POWER_EXCEEDED,
        MAX_ORDER_AMOUNT_EXCEEDED,
        MAX_QUANTITY_EXCEEDED,
        CONCENTRATION_EXCEEDED,
        ACCOUNT_OWNERSHIP_MISMATCH,
        SELLABLE_QUANTITY_UNKNOWN,
        SELLABLE_QUANTITY_INSUFFICIENT,
        OPEN_ORDER_EXISTS,
        KILL_SWITCH_ENGAGED,
        KILL_SWITCH_STATE_UNAVAILABLE
    }

    public record ApprovalCommand(
            UUID userId,
            UUID connectionId,
            UUID orderIntentId,
            BigDecimal referencePrice,
            Instant evaluatedAt,
            String actor
    ) {
    }

    /** 아직 존재하지 않는 주문에 대한 미리보기 입력 (BC-6). actor 가 없다 — 상태를 바꾸지 않으므로. */
    public record PreviewCommand(
            UUID userId,
            UUID connectionId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency,
            BigDecimal referencePrice,
            Instant evaluatedAt
    ) {
    }

    /**
     * 평가 코어가 실제로 필요로 하는 주문 속성만 담은 값 객체. 저장된 {@link OrderIntent} 와 아직
     * 존재하지 않는 미리보기 대상을 같은 코어에 태우기 위한 이음매다.
     */
    private record Subject(
            UUID intentId,
            UUID intentUserId,
            UUID intentConnectionId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency
    ) {
        static Subject of(OrderIntent intent) {
            return new Subject(
                    intent.getId(),
                    intent.getUserId(),
                    intent.getBrokerConnectionId(),
                    intent.getSide(),
                    intent.getType(),
                    intent.getSymbol(),
                    intent.getQuantity(),
                    intent.getLimitPrice(),
                    intent.getTradingCurrency());
        }

        static Subject preview(
                UUID userId,
                UUID connectionId,
                OrderSide side,
                OrderType type,
                String symbol,
                BigDecimal quantity,
                BigDecimal limitPrice,
                Currency currency
        ) {
            return new Subject(
                    null, userId, connectionId, side, type, symbol, quantity, limitPrice, currency);
        }

        UUID excludeIntentId() {
            return intentId == null ? NO_INTENT : intentId;
        }
    }

    public record Decision(
            UUID id,
            Phase phase,
            boolean approved,
            List<Reason> reasons,
            UUID snapshotId,
            BigDecimal orderAmount,
            Currency currency,
            long riskPolicyVersion,
            Instant evaluatedAt
    ) {
    }

    public record SubmissionResult(
            Decision decision,
            PaperTradingBroker.Result paperResult
    ) {
    }

    public record LiveSubmission(
            Decision decision,
            UUID orderIntentId,
            UUID brokerAccountId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency
    ) {
    }

    private record Reservation(BigDecimal total, BigDecimal symbol) {
    }
}
