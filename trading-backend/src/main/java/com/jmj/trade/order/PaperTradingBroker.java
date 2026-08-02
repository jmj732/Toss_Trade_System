package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerOrderAck;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderRequest;
import com.jmj.trade.broker.BrokerOrderType;
import com.jmj.trade.broker.BrokerOrderView;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PaperTradingBroker implements BrokerOrderPort {

    private final OrderIntentRepository intentRepository;
    private final SubmissionIdempotencyKeyRepository idempotencyKeyRepository;
    private final SubmissionAttemptRepository attemptRepository;
    private final BrokerOrderRepository brokerOrderRepository;
    private final OrderSubmissionService submissionService;
    private final JdbcTemplate jdbcTemplate;
    private final BigDecimal commissionRate;
    private final BigDecimal taxRate;
    private final int amountScale;

    /**
     * 포트({@link BrokerOrderPort}) 주문 장부. 기존 DB 기반 페이퍼 체결 워크플로
     * ({@link #submit}/{@link #recover}/{@link #cancel})와 독립적인, 브로커 주문 접수/조회를
     * 흉내내는 in-memory 시뮬레이션이다. 기존 동작은 그대로 보존하고 포트 계약만 추가한다.
     */
    private final ConcurrentMap<String, PaperPortOrder> orderPortBook = new ConcurrentHashMap<>();

    public PaperTradingBroker(
            OrderIntentRepository intentRepository,
            SubmissionIdempotencyKeyRepository idempotencyKeyRepository,
            SubmissionAttemptRepository attemptRepository,
            BrokerOrderRepository brokerOrderRepository,
            OrderSubmissionService submissionService,
            JdbcTemplate jdbcTemplate,
            @Value("${paper-trading.commission-rate:0}") BigDecimal commissionRate,
            @Value("${paper-trading.tax-rate:0}") BigDecimal taxRate,
            @Value("${paper-trading.amount-scale:2}") int amountScale
    ) {
        if (commissionRate.compareTo(BigDecimal.ZERO) < 0 || taxRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("paper trading rates must not be negative");
        }
        if (amountScale < 0) {
            throw new IllegalArgumentException("paper trading amount scale must not be negative");
        }
        this.intentRepository = intentRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.attemptRepository = attemptRepository;
        this.brokerOrderRepository = brokerOrderRepository;
        this.submissionService = submissionService;
        this.jdbcTemplate = jdbcTemplate;
        this.commissionRate = commissionRate;
        this.taxRate = taxRate;
        this.amountScale = amountScale;
    }

    // ------------------------------------------------------------------
    // BrokerOrderPort: in-memory 페이퍼 브로커 주문 장부(플랜 원장 B4).
    // modifyOrder 는 포트 default(공유 정책: 수량 정정 거부 / 가격 정정 미지원)를 그대로 상속한다.
    // ------------------------------------------------------------------

    @Override
    public BrokerResponse<BrokerOrderAck> placeOrder(
            BrokerAccountRef account,
            BrokerOrderRequest request,
            String idempotencyKey
    ) {
        requireAccount(account);
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        requireText(idempotencyKey, "idempotencyKey");
        var brokerOrderId = paperBrokerOrderId(account, idempotencyKey);
        // computeIfAbsent 는 원자적이라 같은 멱등 키의 동시/재호출도 브로커 주문을 하나만 만든다.
        var order = orderPortBook.computeIfAbsent(brokerOrderId, id -> new PaperPortOrder(
                id,
                account.brokerAccountId(),
                idempotencyKey,
                request,
                request.type() == BrokerOrderType.MARKET ? request.quantity() : BigDecimal.ZERO,
                request.type() == BrokerOrderType.MARKET
                        ? BrokerOrderLifecycle.FILLED
                        : BrokerOrderLifecycle.PENDING));
        return BrokerOrderPort.ack(BrokerOrderAck.accepted(order.brokerOrderId(), order.idempotencyKey()));
    }

    @Override
    public BrokerResponse<BrokerOrderAck> cancelOrder(BrokerAccountRef account, String brokerOrderId) {
        requireAccount(account);
        requireText(brokerOrderId, "brokerOrderId");
        var existing = orderPortBook.get(brokerOrderId);
        if (existing == null || !existing.brokerAccountId().equals(account.brokerAccountId())) {
            return BrokerOrderPort.ack(BrokerOrderAck.rejected(BrokerErrorCategory.NOT_FOUND, brokerOrderId, null));
        }
        if (existing.lifecycle().group() == BrokerOrderGroup.CLOSED) {
            return BrokerOrderPort.ack(
                    BrokerOrderAck.rejected(BrokerErrorCategory.CONTRACT, brokerOrderId, existing.idempotencyKey()));
        }
        orderPortBook.computeIfPresent(brokerOrderId, (id, order) -> order.canceled());
        return BrokerOrderPort.ack(BrokerOrderAck.accepted(brokerOrderId, existing.idempotencyKey()));
    }

    @Override
    public BrokerResponse<BrokerOrderView> getOrder(BrokerAccountRef account, String brokerOrderId) {
        requireAccount(account);
        requireText(brokerOrderId, "brokerOrderId");
        var order = orderPortBook.get(brokerOrderId);
        if (order == null || !order.brokerAccountId().equals(account.brokerAccountId())) {
            throw new BrokerException(BrokerErrorCategory.NOT_FOUND, null, null, null, null, false,
                    "paper broker order not found");
        }
        return new BrokerResponse<>(order.view(), BrokerOrderPort.localMetadata());
    }

    @Override
    public BrokerResponse<List<BrokerOrderView>> getOrders(BrokerAccountRef account, BrokerOrderGroup group) {
        requireAccount(account);
        if (group == null) {
            throw new IllegalArgumentException("group is required");
        }
        var views = orderPortBook.values().stream()
                .filter(order -> order.brokerAccountId().equals(account.brokerAccountId()))
                .filter(order -> order.lifecycle().group() == group)
                .sorted(Comparator.comparing(PaperPortOrder::brokerOrderId))
                .map(PaperPortOrder::view)
                .toList();
        return new BrokerResponse<>(List.copyOf(views), BrokerOrderPort.localMetadata());
    }

    private static void requireAccount(BrokerAccountRef account) {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static String paperBrokerOrderId(BrokerAccountRef account, String idempotencyKey) {
        return "paper-" + UUID.nameUUIDFromBytes(
                (account.brokerAccountId() + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public Result submit(SubmitCommand command) {
        requireSubmitCommand(command);
        var intent = lockedIntent(command.orderIntentId());
        requirePaperIntent(intent);
        var requestHash = requestHash(intent, command);
        var canonicalId = new SubmissionIdempotencyKeyId(intent.getBrokerAccountId(), command.clientOrderId());
        var canonical = idempotencyKeyRepository.findById(canonicalId);
        if (canonical.isPresent()) {
            var key = canonical.get();
            if (!key.getOrderIntentId().equals(intent.getId()) || !key.getRequestBodyHash().equals(requestHash)) {
                throw new IllegalStateException("idempotency key conflicts with another paper request");
            }
            return result(attemptRepository
                    .findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(
                            intent.getId(), command.clientOrderId())
                    .orElseThrow(() -> new IllegalStateException("idempotency attempt is missing"))
                    .getId());
        }
        if (intent.getStatus() != OrderIntentStatus.SUBMISSION_PENDING) {
            throw new IllegalStateException("paper submission requires SUBMISSION_PENDING intent");
        }

        var attemptId = submissionService.createInitialAttempt(
                intent.getId(),
                command.clientOrderId(),
                requestHash,
                "paper-" + UUID.randomUUID(),
                command.occurredAt(),
                command.actor());
        submissionService.startDispatch(
                attemptId,
                command.occurredAt().plusNanos(1),
                new DispatchEvidence(command.clientOrderId(), "paper"),
                command.actor());

        switch (command.outcome()) {
            case REJECTED -> submissionService.rejectBeforeBrokerOrderId(
                    attemptId,
                    command.occurredAt().plusNanos(2),
                    new DispatchEvidence(command.clientOrderId(), "paper-rejected"),
                    command.actor());
            case UNKNOWN -> submissionService.markUnknown(
                    attemptId,
                    command.occurredAt().plusNanos(2),
                    new DispatchEvidence(command.clientOrderId(), "paper-unknown"),
                    command.actor());
            case ACKNOWLEDGED -> confirm(
                    intent,
                    attemptId,
                    command.clientOrderId(),
                    command.referencePrice(),
                    command.availableQuantity(),
                    command.occurredAt().plusNanos(2),
                    command.actor());
        }
        return result(attemptId);
    }

    @Transactional
    public Result recover(RecoveryCommand command) {
        requireRecoveryCommand(command);
        var attempt = attemptRepository.findById(command.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("submission attempt not found: " + command.attemptId()));
        var intent = lockedIntent(attempt.getOrderIntentId());
        requirePaperIntent(intent);
        if (attempt.getStatus() != SubmissionAttemptStatus.UNKNOWN) {
            throw new IllegalStateException("paper recovery requires UNKNOWN attempt");
        }

        if (!command.brokerOrderFound()) {
            submissionService.recordReconciliation(
                    attempt.getId(),
                    reconciliation(
                            attempt,
                            null,
                            null,
                            BigDecimal.ZERO,
                            OrderSubmissionService.Execution.unknown(),
                            command.checkedAt()),
                    command.actor());
        } else {
            var fill = fill(intent, command.referencePrice(), command.availableQuantity());
            submissionService.recordReconciliation(
                    attempt.getId(),
                    reconciliation(
                            attempt,
                            "paper-" + attempt.getId(),
                            fill.status(),
                            fill.quantity(),
                            execution(intent, command.referencePrice(), fill.quantity()),
                            command.checkedAt()),
                    command.actor());
        }
        return result(attempt.getId());
    }

    @Transactional
    public Result cancel(UUID orderIntentId, Instant canceledAt, String actor) {
        requireInstant(canceledAt, "canceledAt");
        requireText(actor, "actor");
        var intent = lockedIntent(orderIntentId);
        requirePaperIntent(intent);
        if (intent.getStatus() != OrderIntentStatus.ACTIVE) {
            throw new IllegalStateException("paper cancel requires ACTIVE intent");
        }
        var brokerOrder = brokerOrderRepository.findFirstByOrderIntentIdOrderByIdAsc(orderIntentId)
                .orElseThrow(() -> new IllegalStateException("paper broker order not found"));
        if (!brokerOrder.getBrokerOrderId().startsWith("paper-")) {
            throw new IllegalStateException("paper broker cannot cancel a live broker order");
        }
        var latest = latestExecution(brokerOrder.getId());
        submissionService.recordBrokerOrderUpdate(
                brokerOrder.getId(),
                BrokerOrderStatus.CANCELED,
                latest.filledQuantity(),
                latest.execution(),
                canceledAt,
                actor);
        return resultByBrokerOrder(brokerOrder.getId());
    }

    private void confirm(
            OrderIntent intent,
            UUID attemptId,
            String clientOrderId,
            BigDecimal referencePrice,
            BigDecimal availableQuantity,
            Instant capturedAt,
            String actor
    ) {
        var fill = fill(intent, referencePrice, availableQuantity);
        var brokerOrderId = "paper-" + attemptId;
        var execution = execution(intent, referencePrice, fill.quantity());
        submissionService.confirmBrokerOrder(
                attemptId,
                new OrderSubmissionService.BrokerConfirmation(
                        brokerOrderId,
                        clientOrderId,
                        fill.status(),
                        fill.quantity(),
                        capturedAt,
                        execution),
                actor);
    }

    private OrderSubmissionService.ReconciliationResult reconciliation(
            SubmissionAttempt attempt,
            String brokerOrderId,
            BrokerOrderStatus status,
            BigDecimal filledQuantity,
            OrderSubmissionService.Execution execution,
            Instant checkedAt
    ) {
        return new OrderSubmissionService.ReconciliationResult(
                true,
                true,
                checkedAt.minusSeconds(1),
                checkedAt,
                true,
                hash("paper-recovery|" + attempt.getId() + "|" + brokerOrderId + "|" + checkedAt),
                brokerOrderId,
                brokerOrderId == null ? null : attempt.getClientOrderId(),
                status,
                filledQuantity,
                checkedAt,
                execution);
    }

    private Fill fill(OrderIntent intent, BigDecimal referencePrice, BigDecimal availableQuantity) {
        var executable = intent.getType() == OrderType.MARKET
                || intent.getSide() == OrderSide.BUY && referencePrice.compareTo(intent.getLimitPrice()) <= 0
                || intent.getSide() == OrderSide.SELL && referencePrice.compareTo(intent.getLimitPrice()) >= 0;
        var quantity = executable ? intent.getQuantity().min(availableQuantity) : BigDecimal.ZERO;
        var status = quantity.signum() == 0
                ? BrokerOrderStatus.PENDING
                : quantity.compareTo(intent.getQuantity()) < 0
                ? BrokerOrderStatus.PARTIALLY_FILLED
                : BrokerOrderStatus.FILLED;
        return new Fill(quantity, status);
    }

    private OrderSubmissionService.Execution execution(
            OrderIntent intent,
            BigDecimal price,
            BigDecimal filledQuantity
    ) {
        if (filledQuantity.signum() == 0) {
            return new OrderSubmissionService.Execution(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    intent.getTradingCurrency());
        }
        var amount = price.multiply(filledQuantity).setScale(amountScale, RoundingMode.HALF_UP);
        var commission = amount.multiply(commissionRate).setScale(amountScale, RoundingMode.HALF_UP);
        var tax = intent.getSide() == OrderSide.SELL
                ? amount.multiply(taxRate).setScale(amountScale, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(amountScale);
        return new OrderSubmissionService.Execution(price, amount, commission, tax, intent.getTradingCurrency());
    }

    private Result result(UUID attemptId) {
        var attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("submission attempt not found: " + attemptId));
        if (attempt.getConfirmedBrokerOrderId() == null) {
            var intent = intentRepository.findById(attempt.getOrderIntentId()).orElseThrow();
            return new Result(
                    attempt.getId(),
                    null,
                    attempt.getStatus(),
                    intent.getStatus(),
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    intent.getTradingCurrency());
        }
        return resultByBrokerOrder(attempt.getConfirmedBrokerOrderId());
    }

    private Result resultByBrokerOrder(UUID brokerOrderId) {
        var brokerOrder = brokerOrderRepository.findById(brokerOrderId).orElseThrow();
        var intent = intentRepository.findById(brokerOrder.getOrderIntentId()).orElseThrow();
        var attempt = attemptRepository
                .findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(
                        intent.getId(), brokerOrder.getClientOrderId())
                .orElseThrow();
        var execution = latestExecution(brokerOrderId);
        return new Result(
                attempt.getId(),
                brokerOrder.getId(),
                attempt.getStatus(),
                intent.getStatus(),
                brokerOrder.getStatus(),
                execution.filledQuantity(),
                execution.execution().filledAmount(),
                execution.execution().commission(),
                execution.execution().tax(),
                execution.execution().currency());
    }

    private StoredExecution latestExecution(UUID brokerOrderId) {
        return jdbcTemplate.query("""
                SELECT filled_quantity, average_filled_price, filled_amount, commission, tax, currency
                  FROM execution_snapshots
                 WHERE broker_order_id = ?
                 ORDER BY captured_at DESC, id DESC
                 LIMIT 1
                """, (resultSet, rowNumber) -> new StoredExecution(
                resultSet.getBigDecimal("filled_quantity"),
                new OrderSubmissionService.Execution(
                        resultSet.getBigDecimal("average_filled_price"),
                        resultSet.getBigDecimal("filled_amount"),
                        resultSet.getBigDecimal("commission"),
                        resultSet.getBigDecimal("tax"),
                        resultSet.getString("currency") == null
                                ? null
                                : Currency.valueOf(resultSet.getString("currency")))),
                brokerOrderId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("paper execution snapshot not found"));
    }

    private String requestHash(OrderIntent intent, SubmitCommand command) {
        return hash(String.join("|",
                intent.getId().toString(),
                intent.getSide().name(),
                intent.getType().name(),
                intent.getSymbol(),
                decimal(intent.getQuantity()),
                intent.getLimitPrice() == null ? "" : decimal(intent.getLimitPrice()),
                intent.getTradingCurrency().name(),
                decimal(command.referencePrice()),
                decimal(command.availableQuantity()),
                command.outcome().name()));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private OrderIntent lockedIntent(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("orderIntentId is required");
        }
        return intentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("order intent not found: " + id));
    }

    private static void requirePaperIntent(OrderIntent intent) {
        if (intent.getExecutionMode() != OrderExecutionMode.PAPER) {
            throw new IllegalStateException("paper broker cannot submit a live order intent");
        }
        if (intent.getSide() == null
                || intent.getType() == null
                || intent.getSymbol() == null
                || intent.getTradingCurrency() == null
                || intent.getType() == OrderType.LIMIT && intent.getLimitPrice() == null) {
            throw new IllegalStateException("paper order intent is incomplete");
        }
    }

    private static void requireSubmitCommand(SubmitCommand command) {
        if (command == null || command.outcome() == null) {
            throw new IllegalArgumentException("paper submit command and outcome are required");
        }
        requireText(command.clientOrderId(), "clientOrderId");
        requirePositive(command.referencePrice(), "referencePrice");
        requireNonNegative(command.availableQuantity(), "availableQuantity");
        requireInstant(command.occurredAt(), "occurredAt");
        requireText(command.actor(), "actor");
    }

    private static void requireRecoveryCommand(RecoveryCommand command) {
        if (command == null || command.attemptId() == null) {
            throw new IllegalArgumentException("paper recovery command and attemptId are required");
        }
        requirePositive(command.referencePrice(), "referencePrice");
        requireNonNegative(command.availableQuantity(), "availableQuantity");
        requireInstant(command.checkedAt(), "checkedAt");
        requireText(command.actor(), "actor");
    }

    private static void requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void requireNonNegative(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
    }

    private static void requireInstant(Instant value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    public enum DispatchOutcome {
        ACKNOWLEDGED,
        REJECTED,
        UNKNOWN
    }

    public record SubmitCommand(
            UUID orderIntentId,
            String clientOrderId,
            BigDecimal referencePrice,
            BigDecimal availableQuantity,
            DispatchOutcome outcome,
            Instant occurredAt,
            String actor
    ) {
    }

    public record RecoveryCommand(
            UUID attemptId,
            boolean brokerOrderFound,
            BigDecimal referencePrice,
            BigDecimal availableQuantity,
            Instant checkedAt,
            String actor
    ) {
    }

    public record Result(
            UUID attemptId,
            UUID brokerOrderId,
            SubmissionAttemptStatus attemptStatus,
            OrderIntentStatus intentStatus,
            BrokerOrderStatus brokerStatus,
            BigDecimal filledQuantity,
            BigDecimal filledAmount,
            BigDecimal commission,
            BigDecimal tax,
            Currency currency
    ) {
    }

    private record Fill(BigDecimal quantity, BrokerOrderStatus status) {
    }

    private record StoredExecution(
            BigDecimal filledQuantity,
            OrderSubmissionService.Execution execution
    ) {
    }

    /** {@link BrokerOrderPort} in-memory 장부의 페이퍼 주문 레코드(불변; 취소 시 교체). */
    private record PaperPortOrder(
            String brokerOrderId,
            String brokerAccountId,
            String idempotencyKey,
            BrokerOrderRequest request,
            BigDecimal filledQuantity,
            BrokerOrderLifecycle lifecycle
    ) {
        PaperPortOrder canceled() {
            return new PaperPortOrder(
                    brokerOrderId, brokerAccountId, idempotencyKey, request, filledQuantity,
                    BrokerOrderLifecycle.CANCELED);
        }

        BrokerOrderView view() {
            return new BrokerOrderView(
                    brokerOrderId,
                    idempotencyKey,
                    request.side(),
                    request.type(),
                    request.symbol(),
                    request.quantity(),
                    filledQuantity,
                    request.limitPrice(),
                    request.currency(),
                    lifecycle);
        }
    }
}
