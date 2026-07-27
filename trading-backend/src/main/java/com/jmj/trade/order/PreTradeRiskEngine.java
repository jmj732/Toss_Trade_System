package com.jmj.trade.order;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.connection.BrokerConnectionException;
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

    private final PortfolioReadService portfolioReadService;
    private final PaperTradingBroker paperTradingBroker;
    private final OrderIntentRepository intentRepository;
    private final OrderIntentTransitionService transitionService;
    private final JdbcTemplate jdbc;
    private final BigDecimal maxKrwOrderAmount;
    private final BigDecimal maxUsdOrderAmount;
    private final BigDecimal maxQuantity;
    private final BigDecimal maxConcentration;

    public PreTradeRiskEngine(
            PortfolioReadService portfolioReadService,
            PaperTradingBroker paperTradingBroker,
            OrderIntentRepository intentRepository,
            OrderIntentTransitionService transitionService,
            JdbcTemplate jdbc,
            BigDecimal maxKrwOrderAmount,
            BigDecimal maxUsdOrderAmount,
            BigDecimal maxQuantity,
            BigDecimal maxConcentration
    ) {
        this.portfolioReadService = Objects.requireNonNull(portfolioReadService, "portfolioReadService");
        this.paperTradingBroker = Objects.requireNonNull(paperTradingBroker, "paperTradingBroker");
        this.intentRepository = Objects.requireNonNull(intentRepository, "intentRepository");
        this.transitionService = Objects.requireNonNull(transitionService, "transitionService");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.maxKrwOrderAmount = positive(maxKrwOrderAmount, "maxKrwOrderAmount");
        this.maxUsdOrderAmount = positive(maxUsdOrderAmount, "maxUsdOrderAmount");
        this.maxQuantity = positive(maxQuantity, "maxQuantity");
        this.maxConcentration = positive(maxConcentration, "maxConcentration");
        if (maxConcentration.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("maxConcentration must not exceed 1");
        }
    }

    @Transactional
    public Decision approve(ApprovalCommand command) {
        requireApprovalCommand(command);
        lockConnection(command.userId(), command.connectionId());
        var intent = lockedIntent(command.orderIntentId(), command.userId(), command.connectionId());
        requirePaperIntent(intent);
        if (intent.getStatus() != OrderIntentStatus.PROPOSED) {
            throw new IllegalStateException("risk approval requires PROPOSED intent");
        }
        var portfolio = portfolioReadService.read(command.userId(), command.connectionId());
        var decision = evaluate(
                Phase.APPROVAL,
                command.userId(),
                command.connectionId(),
                intent,
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
        requirePaperIntent(intent);
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
                intent,
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

    private Decision evaluate(
            Phase phase,
            UUID userId,
            UUID connectionId,
            OrderIntent intent,
            BigDecimal referencePrice,
            Instant evaluatedAt,
            PortfolioReadService.PortfolioView portfolio
    ) {
        var reasons = new ArrayList<Reason>();
        if (portfolio.stale()) {
            reasons.add(Reason.STALE_SNAPSHOT);
        }
        if (portfolio.partial()) {
            reasons.add(Reason.PARTIAL_SNAPSHOT);
        }
        if (portfolio.unknownFields().contains("account.cashBalance")) {
            reasons.add(Reason.CASH_UNKNOWN);
        }

        var price = intent.getType() == OrderType.LIMIT ? intent.getLimitPrice() : referencePrice;
        var orderAmount = price.multiply(intent.getQuantity());
        if (intent.getQuantity().compareTo(maxQuantity) > 0) {
            reasons.add(Reason.MAX_QUANTITY_EXCEEDED);
        }
        if (orderAmount.compareTo(maxOrderAmount(intent.getTradingCurrency())) > 0) {
            reasons.add(Reason.MAX_ORDER_AMOUNT_EXCEEDED);
        }
        if (intent.getSide() == OrderSide.BUY) {
            evaluateBuyLimits(
                    userId,
                    connectionId,
                    intent,
                    portfolio,
                    orderAmount,
                    reasons);
        }

        return new Decision(
                UUID.randomUUID(),
                phase,
                reasons.isEmpty(),
                List.copyOf(reasons),
                portfolio.syncRunId(),
                orderAmount,
                intent.getTradingCurrency(),
                evaluatedAt);
    }

    private void evaluateBuyLimits(
            UUID userId,
            UUID connectionId,
            OrderIntent intent,
            PortfolioReadService.PortfolioView portfolio,
            BigDecimal orderAmount,
            List<Reason> reasons
    ) {
        var reserved = reserved(
                userId,
                connectionId,
                portfolio.syncRunId(),
                intent.getTradingCurrency(),
                intent.getSymbol(),
                intent.getId());
        var buyingPower = portfolio.buyingPower().get(intent.getTradingCurrency().name());
        if (buyingPower != null
                && orderAmount.compareTo(buyingPower.cashBuyingPower().subtract(reserved.total())) > 0) {
            reasons.add(Reason.BUYING_POWER_EXCEEDED);
        }
        if (portfolio.account() == null) {
            return;
        }

        var portfolioValue = portfolio.account().marketValueAmounts()
                .getOrDefault(intent.getTradingCurrency().name(), BigDecimal.ZERO);
        var symbolValue = portfolio.positions().stream()
                .filter(position -> position.currency().equals(intent.getTradingCurrency().name()))
                .filter(position -> position.symbol().equals(intent.getSymbol()))
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
                    phase, outcome, reason_codes, order_amount, currency, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    private OrderIntent lockedIntent(UUID intentId, UUID userId, UUID connectionId) {
        requireId(intentId, "orderIntentId");
        return intentRepository.findOwnedByIdForUpdate(intentId, userId, connectionId)
                .orElseThrow(BrokerConnectionException::notFound);
    }

    private BigDecimal maxOrderAmount(Currency currency) {
        return currency == Currency.KRW ? maxKrwOrderAmount : maxUsdOrderAmount;
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

    private static void requirePaperIntent(OrderIntent intent) {
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
        CASH_UNKNOWN,
        BUYING_POWER_EXCEEDED,
        MAX_ORDER_AMOUNT_EXCEEDED,
        MAX_QUANTITY_EXCEEDED,
        CONCENTRATION_EXCEEDED
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

    public record Decision(
            UUID id,
            Phase phase,
            boolean approved,
            List<Reason> reasons,
            UUID snapshotId,
            BigDecimal orderAmount,
            Currency currency,
            Instant evaluatedAt
    ) {
    }

    public record SubmissionResult(
            Decision decision,
            PaperTradingBroker.Result paperResult
    ) {
    }

    private record Reservation(BigDecimal total, BigDecimal symbol) {
    }
}
