package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.Currency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PaperOrderWorkflowService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,80}");

    private final BrokerAdapter broker;
    private final OrderIntentRepository intentRepository;
    private final OrderIntentTransitionService transitionService;
    private final PreTradeRiskEngine riskEngine;
    private final OrderApprovalStepUpService stepUp;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PaperOrderWorkflowService(
            BrokerAdapter broker,
            OrderIntentRepository intentRepository,
            OrderIntentTransitionService transitionService,
            PreTradeRiskEngine riskEngine,
            OrderApprovalStepUpService stepUp,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.intentRepository = Objects.requireNonNull(intentRepository, "intentRepository");
        this.transitionService = Objects.requireNonNull(transitionService, "transitionService");
        this.riskEngine = Objects.requireNonNull(riskEngine, "riskEngine");
        this.stepUp = Objects.requireNonNull(stepUp, "stepUp");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public OrderView propose(UUID userId, String idempotencyKey, Channel channel, ProposeCommand command) {
        requireUserAndKey(userId, idempotencyKey, channel);
        validate(command, userId);
        var fingerprint = proposalFingerprint(command);
        var replay = replay(userId, idempotencyKey, Action.PROPOSE, null, channel, fingerprint);
        if (replay.isPresent()) {
            return read(userId, replay.get());
        }

        return Objects.requireNonNull(transactions.execute(status -> {
            lockConnection(userId, command.connectionId());
            var intendedId = UUID.randomUUID();
            var receipt = record(
                    userId,
                    idempotencyKey,
                    intendedId,
                    Action.PROPOSE,
                    channel,
                    fingerprint);
            if (!receipt.inserted()) {
                return read(userId, receipt.orderIntentId());
            }
            jdbc.update("INSERT INTO broker_accounts (id) VALUES (?) ON CONFLICT DO NOTHING",
                    command.connectionId());
            intentRepository.saveAndFlush(OrderIntent.proposed(
                    intendedId,
                    command.connectionId(),
                    userId,
                    command.connectionId(),
                    command.side(),
                    command.type(),
                    command.symbol(),
                    command.quantity(),
                    command.limitPrice(),
                    command.currency()));
            return read(userId, intendedId);
        }));
    }

    /**
     * 주문 승인 (플랜 원장 E2). 화면 표시값 대조와 step-up 재인증을 요구한다.
     *
     * <p>표시값 불일치는 409(서버 계산값 동봉)로, step-up 실패는 401 로 거절하며 어느 쪽도 intent
     * 상태를 바꾸지 않는다. 이중 승인은 기존 {@code Idempotency-Key} 경로와 PROPOSED compare-and-set
     * 으로 단일 intent 만 만든다. 승인은 즉시 제출 의사이며 grace period 는 없다(SPEC:966).
     */
    public OrderView approve(UUID userId, UUID orderIntentId, String idempotencyKey, ApproveCommand command) {
        requireApproveCommand(command);
        var channel = command.channel();
        requireUserAndKey(userId, idempotencyKey, channel);
        requireId(orderIntentId);
        var fingerprint = actionFingerprint(Action.APPROVE, orderIntentId);
        var replay = replay(userId, idempotencyKey, Action.APPROVE, orderIntentId, channel, fingerprint);
        if (replay.isPresent()) {
            return read(userId, replay.get());
        }

        var target = quoteTarget(userId, orderIntentId);
        if (target.status() != OrderIntentStatus.PROPOSED) {
            throw PaperOrderWorkflowException.conflict();
        }
        var price = serverPrice(target);
        // 표시값 대조: 서버 재계산값과 다르면 부작용 없이 409(서버 계산값 동봉).
        requireDisplayMatches(command, displaySnapshot(target, price));

        return Objects.requireNonNull(transactions.execute(status -> {
            lockConnection(userId, target.connectionId());
            var receipt = record(
                    userId,
                    idempotencyKey,
                    orderIntentId,
                    Action.APPROVE,
                    channel,
                    fingerprint);
            if (!receipt.inserted()) {
                return read(userId, receipt.orderIntentId());
            }
            var intent = lockedIntent(userId, target.connectionId(), orderIntentId);
            if (intent.getStatus() != OrderIntentStatus.PROPOSED) {
                throw PaperOrderWorkflowException.conflict();
            }
            // step-up 재인증 확인은 상태 전이 직전, 트랜잭션 안에서 단일 사용 소비. 승인이 롤백되면
            // 토큰 소비도 함께 롤백돼 재시도에서 다시 쓸 수 있다.
            stepUp.consume(userId, orderIntentId, command.stepUpToken());
            var now = Instant.now();
            var actor = actor(channel, userId);
            var approval = riskEngine.approve(new PreTradeRiskEngine.ApprovalCommand(
                    userId,
                    target.connectionId(),
                    orderIntentId,
                    price,
                    now,
                    actor));
            if (approval.approved()) {
                riskEngine.submitPaper(
                        userId,
                        target.connectionId(),
                        new PaperTradingBroker.SubmitCommand(
                                orderIntentId,
                                clientOrderId(userId, idempotencyKey),
                                price,
                                intent.getQuantity(),
                                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED,
                                now.plusNanos(1),
                                actor));
            }
            return read(userId, orderIntentId);
        }));
    }

    /**
     * 승인 대상에 바인딩된 step-up 재인증 토큰을 발급한다. 최근 OIDC 재인증({@code authTime})만이
     * 근거이며, 대상은 사용자 소유의 PROPOSED intent 여야 한다.
     */
    public OrderApprovalStepUpService.IssuedStepUp issueStepUp(
            UUID userId,
            UUID orderIntentId,
            Instant authTime
    ) {
        requireId(userId);
        requireId(orderIntentId);
        var target = quoteTarget(userId, orderIntentId);
        if (target.status() != OrderIntentStatus.PROPOSED) {
            throw PaperOrderWorkflowException.conflict();
        }
        return stepUp.issue(userId, orderIntentId, authTime);
    }

    /**
     * 승인 철회 (플랜 원장 E2, SPEC:966). 제출(SUBMITTING) 전이 전, 즉 PROPOSED 에서만
     * compare-and-set(행 잠금 + 상태 대조 + 전이)으로 성공한다. 경합에서 지면 409 로 실패하며
     * 성공을 보장하지 않는다. 이후 단계는 브로커 취소 절차를 쓴다.
     */
    public OrderView withdraw(UUID userId, UUID orderIntentId, String idempotencyKey, Channel channel) {
        requireUserAndKey(userId, idempotencyKey, channel);
        requireId(orderIntentId);
        var fingerprint = actionFingerprint(Action.CANCEL, orderIntentId);
        var replay = replay(userId, idempotencyKey, Action.CANCEL, orderIntentId, channel, fingerprint);
        if (replay.isPresent()) {
            return read(userId, replay.get());
        }

        return Objects.requireNonNull(transactions.execute(status -> {
            var target = quoteTarget(userId, orderIntentId);
            lockConnection(userId, target.connectionId());
            var receipt = record(
                    userId,
                    idempotencyKey,
                    orderIntentId,
                    Action.CANCEL,
                    channel,
                    fingerprint);
            if (!receipt.inserted()) {
                return read(userId, receipt.orderIntentId());
            }
            var intent = lockedIntent(userId, target.connectionId(), orderIntentId);
            if (intent.getStatus() != OrderIntentStatus.PROPOSED) {
                throw PaperOrderWorkflowException.conflict();
            }
            transitionService.terminate(
                    orderIntentId,
                    OrderIntentStatus.CANCELED,
                    "USER_WITHDRAWN",
                    Instant.now(),
                    BigDecimal.ZERO,
                    actor(channel, userId));
            return read(userId, orderIntentId);
        }));
    }

    public OrderView cancel(UUID userId, UUID orderIntentId, String idempotencyKey, Channel channel) {
        requireUserAndKey(userId, idempotencyKey, channel);
        requireId(orderIntentId);
        var fingerprint = actionFingerprint(Action.CANCEL, orderIntentId);
        var replay = replay(userId, idempotencyKey, Action.CANCEL, orderIntentId, channel, fingerprint);
        if (replay.isPresent()) {
            return read(userId, replay.get());
        }

        return Objects.requireNonNull(transactions.execute(status -> {
            var target = quoteTarget(userId, orderIntentId);
            lockConnection(userId, target.connectionId());
            var receipt = record(
                    userId,
                    idempotencyKey,
                    orderIntentId,
                    Action.CANCEL,
                    channel,
                    fingerprint);
            if (!receipt.inserted()) {
                return read(userId, receipt.orderIntentId());
            }
            var intent = lockedIntent(userId, target.connectionId(), orderIntentId);
            if (intent.getStatus() != OrderIntentStatus.PROPOSED) {
                throw PaperOrderWorkflowException.conflict();
            }
            transitionService.terminate(
                    orderIntentId,
                    OrderIntentStatus.CANCELED,
                    channel == Channel.MESSENGER ? "USER_REJECTED" : "USER_CANCELED",
                    Instant.now(),
                    BigDecimal.ZERO,
                    actor(channel, userId));
            return read(userId, orderIntentId);
        }));
    }

    public OrderView read(UUID userId, UUID orderIntentId) {
        requireId(userId);
        requireId(orderIntentId);
        var intent = jdbc.query("""
                SELECT id, broker_connection_id, side, order_type, symbol, quantity,
                       limit_price, trading_currency, status, terminal_reason, terminal_at
                  FROM order_intents
                 WHERE id = ? AND user_id = ?
                """, (resultSet, rowNumber) -> mapIntent(resultSet), orderIntentId, userId)
                .stream().findFirst()
                .orElseThrow(PaperOrderWorkflowException::notFound);
        return new OrderView(
                intent.id(),
                intent.connectionId(),
                intent.side(),
                intent.type(),
                intent.symbol(),
                intent.quantity(),
                intent.limitPrice(),
                intent.currency(),
                intent.status(),
                intent.terminalReason(),
                intent.terminalAt(),
                riskDecisions(orderIntentId),
                statusAudit(orderIntentId),
                commands(userId, orderIntentId));
    }

    private BigDecimal serverPrice(QuoteTarget target) {
        var quote = broker.getQuote(new BrokerConnectionRef(target.connectionId()), target.symbol()).value();
        if (!quote.connection().brokerConnectionId().equals(target.connectionId())
                || !quote.symbol().equals(target.symbol())
                || quote.currency() != target.currency()) {
            throw PaperOrderWorkflowException.conflict();
        }
        var price = target.side() == OrderSide.BUY ? quote.askPrice() : quote.bidPrice();
        if (price == null) {
            price = quote.lastPrice();
        }
        if (price == null || price.signum() <= 0) {
            throw PaperOrderWorkflowException.conflict();
        }
        return price;
    }

    private Optional<UUID> replay(
            UUID userId,
            String key,
            Action action,
            UUID orderIntentId,
            Channel channel,
            String fingerprint
    ) {
        return jdbc.query("""
                SELECT order_intent_id, action, channel, request_fingerprint
                  FROM paper_order_workflow_commands
                 WHERE user_id = ? AND idempotency_key = ?
                """, (resultSet, rowNumber) -> receipt(resultSet, false), userId, key)
                .stream().findFirst()
                .map(existing -> {
                    requireSame(existing, action, orderIntentId, channel, fingerprint);
                    return existing.orderIntentId();
                });
    }

    private Receipt record(
            UUID userId,
            String key,
            UUID orderIntentId,
            Action action,
            Channel channel,
            String fingerprint
    ) {
        var inserted = jdbc.update("""
                INSERT INTO paper_order_workflow_commands (
                    user_id, idempotency_key, order_intent_id, action,
                    channel, actor, request_fingerprint, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """,
                userId,
                key,
                orderIntentId,
                action.name(),
                channel.name(),
                userId.toString(),
                fingerprint,
                OffsetDateTime.now());
        if (inserted == 1) {
            return new Receipt(true, orderIntentId, action, channel, fingerprint);
        }
        var existing = jdbc.query("""
                SELECT order_intent_id, action, channel, request_fingerprint
                  FROM paper_order_workflow_commands
                 WHERE user_id = ? AND idempotency_key = ?
                """, (resultSet, rowNumber) -> receipt(resultSet, false), userId, key)
                .stream().findFirst()
                .orElseThrow(PaperOrderWorkflowException::conflict);
        requireSame(existing, action, action == Action.PROPOSE ? null : orderIntentId, channel, fingerprint);
        return existing;
    }

    private void requireSame(
            Receipt existing,
            Action action,
            UUID orderIntentId,
            Channel channel,
            String fingerprint
    ) {
        if (existing.action() != action
                || orderIntentId != null && !existing.orderIntentId().equals(orderIntentId)
                || existing.channel() != channel
                || !existing.fingerprint().equals(fingerprint)) {
            throw PaperOrderWorkflowException.conflict();
        }
    }

    private QuoteTarget quoteTarget(UUID userId, UUID orderIntentId) {
        return jdbc.query("""
                SELECT intent.broker_connection_id, intent.side, intent.order_type, intent.symbol,
                       intent.quantity, intent.limit_price, intent.trading_currency, intent.status
                  FROM order_intents intent
                  JOIN broker_connections connection
                    ON connection.id = intent.broker_connection_id
                   AND connection.user_id = intent.user_id
                 WHERE intent.id = ? AND intent.user_id = ?
                   AND connection.status = 'ACTIVE'
                   AND connection.deleted_at IS NULL
                """, (resultSet, rowNumber) -> new QuoteTarget(
                resultSet.getObject("broker_connection_id", UUID.class),
                OrderSide.valueOf(resultSet.getString("side")),
                OrderType.valueOf(resultSet.getString("order_type")),
                resultSet.getString("symbol"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getBigDecimal("limit_price"),
                Currency.valueOf(resultSet.getString("trading_currency")),
                OrderIntentStatus.valueOf(resultSet.getString("status"))), orderIntentId, userId)
                .stream().findFirst()
                .orElseThrow(PaperOrderWorkflowException::notFound);
    }

    private OrderIntent lockedIntent(UUID userId, UUID connectionId, UUID orderIntentId) {
        return intentRepository.findOwnedByIdForUpdate(orderIntentId, userId, connectionId)
                .orElseThrow(PaperOrderWorkflowException::notFound);
    }

    private void lockConnection(UUID userId, UUID connectionId) {
        if (jdbc.queryForList("""
                SELECT id FROM broker_connections
                 WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                 FOR UPDATE
                """, UUID.class, connectionId, userId).size() != 1) {
            throw PaperOrderWorkflowException.notFound();
        }
    }

    private List<RiskDecisionView> riskDecisions(UUID orderIntentId) {
        return jdbc.query("""
                SELECT id, phase, outcome, reason_codes, snapshot_id,
                       order_amount, currency, created_at
                  FROM pre_trade_risk_decisions
                 WHERE order_intent_id = ?
                 ORDER BY created_at, id
                """, (resultSet, rowNumber) -> new RiskDecisionView(
                resultSet.getObject("id", UUID.class),
                PreTradeRiskEngine.Phase.valueOf(resultSet.getString("phase")),
                resultSet.getString("outcome"),
                reasons(resultSet.getString("reason_codes")),
                resultSet.getObject("snapshot_id", UUID.class),
                resultSet.getBigDecimal("order_amount"),
                Currency.valueOf(resultSet.getString("currency")),
                instant(resultSet, "created_at")), orderIntentId);
    }

    private List<StatusAuditView> statusAudit(UUID orderIntentId) {
        return jdbc.query("""
                SELECT from_status, to_status, actor, terminal_reason, occurred_at
                  FROM order_intent_audit_logs
                 WHERE order_intent_id = ?
                 ORDER BY occurred_at, id
                """, (resultSet, rowNumber) -> new StatusAuditView(
                OrderIntentStatus.valueOf(resultSet.getString("from_status")),
                OrderIntentStatus.valueOf(resultSet.getString("to_status")),
                resultSet.getString("actor"),
                resultSet.getString("terminal_reason"),
                instant(resultSet, "occurred_at")), orderIntentId);
    }

    private List<CommandView> commands(UUID userId, UUID orderIntentId) {
        return jdbc.query("""
                SELECT action, channel, actor, idempotency_key, created_at
                  FROM paper_order_workflow_commands
                 WHERE user_id = ? AND order_intent_id = ?
                 ORDER BY created_at, idempotency_key
                """, (resultSet, rowNumber) -> new CommandView(
                Action.valueOf(resultSet.getString("action")),
                Channel.valueOf(resultSet.getString("channel")),
                resultSet.getString("actor"),
                resultSet.getString("idempotency_key"),
                instant(resultSet, "created_at")), userId, orderIntentId);
    }

    private static IntentRow mapIntent(ResultSet resultSet) throws SQLException {
        return new IntentRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("broker_connection_id", UUID.class),
                OrderSide.valueOf(resultSet.getString("side")),
                OrderType.valueOf(resultSet.getString("order_type")),
                resultSet.getString("symbol"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getBigDecimal("limit_price"),
                Currency.valueOf(resultSet.getString("trading_currency")),
                OrderIntentStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("terminal_reason"),
                instant(resultSet, "terminal_at"));
    }

    private static Receipt receipt(ResultSet resultSet, boolean inserted) throws SQLException {
        return new Receipt(
                inserted,
                resultSet.getObject("order_intent_id", UUID.class),
                Action.valueOf(resultSet.getString("action")),
                Channel.valueOf(resultSet.getString("channel")),
                resultSet.getString("request_fingerprint"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> reasons(String codes) {
        return codes.isBlank() ? List.of() : Arrays.asList(codes.split(","));
    }

    private static void validate(ProposeCommand command, UUID userId) {
        if (command == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
        try {
            OrderIntent.proposed(
                    UUID.randomUUID(),
                    command.connectionId(),
                    userId,
                    command.connectionId(),
                    command.side(),
                    command.type(),
                    command.symbol(),
                    command.quantity(),
                    command.limitPrice(),
                    command.currency());
        } catch (RuntimeException exception) {
            throw PaperOrderWorkflowException.validationFailed();
        }
    }

    private static void requireApproveCommand(ApproveCommand command) {
        // 표시값 누락 시 승인 거절. 기본값으로 채우지 않는다(SPEC:966). step-up 토큰 누락은
        // consume 단계에서 401 로 처리한다.
        if (command == null
                || command.displayedQuantity() == null
                || command.displayedMaxLoss() == null
                || command.displayedCurrency() == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
    }

    private static PaperOrderWorkflowException.DisplaySnapshot displaySnapshot(
            QuoteTarget target,
            BigDecimal price
    ) {
        // 현재 최대손실 = 주문 명목가(체결가 × 수량). E1(TradeProposal) 도입 시 정교화한다.
        return new PaperOrderWorkflowException.DisplaySnapshot(
                target.quantity(),
                price.multiply(target.quantity()),
                target.currency());
    }

    private static void requireDisplayMatches(
            ApproveCommand command,
            PaperOrderWorkflowException.DisplaySnapshot server
    ) {
        // 비교 기준: 통화는 enum 정확 일치. 수량은 값 동등(BigDecimal.compareTo, 스케일 무시).
        // 금액은 통화 최소단위(USD 소수 2자리, KRW 0자리)로 HALF_UP 반올림 후 정확 일치.
        // 허용 오차(tolerance) 밴드는 두지 않는다.
        if (command.displayedCurrency() != server.currency()
                || command.displayedQuantity().compareTo(server.quantity()) != 0
                || scaledAmount(command.displayedMaxLoss(), server.currency())
                        .compareTo(scaledAmount(server.maxLoss(), server.currency())) != 0) {
            throw PaperOrderWorkflowException.displayMismatch(server);
        }
    }

    private static BigDecimal scaledAmount(BigDecimal value, Currency currency) {
        return value.setScale(currency == Currency.KRW ? 0 : 2, RoundingMode.HALF_UP);
    }

    private static void requireUserAndKey(UUID userId, String key, Channel channel) {
        requireId(userId);
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches() || channel == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
    }

    private static void requireId(UUID id) {
        if (id == null) {
            throw PaperOrderWorkflowException.validationFailed();
        }
    }

    private static String proposalFingerprint(ProposeCommand command) {
        return String.join("|",
                command.connectionId().toString(),
                command.side().name(),
                command.type().name(),
                command.symbol(),
                decimal(command.quantity()),
                command.limitPrice() == null ? "" : decimal(command.limitPrice()),
                command.currency().name());
    }

    private static String actionFingerprint(Action action, UUID orderIntentId) {
        return action + "|" + orderIntentId;
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String actor(Channel channel, UUID userId) {
        return channel + ":" + userId;
    }

    private static String clientOrderId(UUID userId, String idempotencyKey) {
        return UUID.nameUUIDFromBytes(
                (userId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public enum Channel {
        WEB,
        MESSENGER,
        API
    }

    public enum Action {
        PROPOSE,
        APPROVE,
        CANCEL
    }

    public record ApproveCommand(
            Channel channel,
            BigDecimal displayedQuantity,
            BigDecimal displayedMaxLoss,
            Currency displayedCurrency,
            String stepUpToken,
            // E1(TradeProposal) 도입 시 승인 대상 버전 대조에 쓰일 seam. 현재는 미사용.
            Long proposalVersion
    ) {
    }

    public record ProposeCommand(
            UUID connectionId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency
    ) {
    }

    public record OrderView(
            UUID id,
            UUID connectionId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency,
            OrderIntentStatus status,
            String terminalReason,
            Instant terminalAt,
            List<RiskDecisionView> riskDecisions,
            List<StatusAuditView> statusAudit,
            List<CommandView> commands
    ) {
    }

    public record RiskDecisionView(
            UUID id,
            PreTradeRiskEngine.Phase phase,
            String outcome,
            List<String> reasons,
            UUID snapshotId,
            BigDecimal orderAmount,
            Currency currency,
            Instant createdAt
    ) {
    }

    public record StatusAuditView(
            OrderIntentStatus fromStatus,
            OrderIntentStatus toStatus,
            String actor,
            String terminalReason,
            Instant occurredAt
    ) {
    }

    public record CommandView(
            Action action,
            Channel channel,
            String actor,
            String idempotencyKey,
            Instant createdAt
    ) {
    }

    private record IntentRow(
            UUID id,
            UUID connectionId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency,
            OrderIntentStatus status,
            String terminalReason,
            Instant terminalAt
    ) {
    }

    private record QuoteTarget(
            UUID connectionId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency,
            OrderIntentStatus status
    ) {
    }

    private record Receipt(
            boolean inserted,
            UUID orderIntentId,
            Action action,
            Channel channel,
            String fingerprint
    ) {
    }
}
