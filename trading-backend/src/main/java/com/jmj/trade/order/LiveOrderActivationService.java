package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderAck;
import com.jmj.trade.broker.BrokerOrderDispatchStatus;
import com.jmj.trade.broker.BrokerOrderModification;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderRequest;
import com.jmj.trade.broker.BrokerOrderSide;
import com.jmj.trade.broker.BrokerOrderType;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** The only application service allowed to submit a LIVE order intent. */
public final class LiveOrderActivationService {

    private static final Pattern CLIENT_ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,36}");

    private final BrokerAdapter quotes;
    private final BrokerOrderPort orders;
    private final OrderIntentRepository intents;
    private final BrokerOrderRepository brokerOrders;
    private final SubmissionAttemptRepository attempts;
    private final OrderSubmissionService submissions;
    private final OrderIntentTransitionService transitions;
    private final PreTradeRiskEngine risk;
    private final OrderApprovalStepUpService stepUp;
    private final LiveOrderSafetyLedger safety;
    private final KillSwitchStateReader killSwitches;
    private final TransactionTemplate transactions;

    public LiveOrderActivationService(
            BrokerAdapter quotes,
            BrokerOrderPort orders,
            OrderIntentRepository intents,
            BrokerOrderRepository brokerOrders,
            SubmissionAttemptRepository attempts,
            OrderSubmissionService submissions,
            OrderIntentTransitionService transitions,
            PreTradeRiskEngine risk,
            OrderApprovalStepUpService stepUp,
            LiveOrderSafetyLedger safety,
            KillSwitchStateReader killSwitches,
            TransactionTemplate transactions
    ) {
        this.quotes = Objects.requireNonNull(quotes, "quotes");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.brokerOrders = Objects.requireNonNull(brokerOrders, "brokerOrders");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.stepUp = Objects.requireNonNull(stepUp, "stepUp");
        this.safety = Objects.requireNonNull(safety, "safety");
        this.killSwitches = Objects.requireNonNull(killSwitches, "killSwitches");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public UUID propose(UUID userId, Proposal proposal) {
        requireId(userId, "userId");
        if (proposal == null || proposal.connectionId() == null || proposal.brokerAccountId() == null) {
            throw validation();
        }
        validateOrder(proposal.side(), proposal.type(), proposal.symbol(), proposal.quantity(), proposal.limitPrice(), proposal.currency());
        return Objects.requireNonNull(transactions.execute(status -> {
            requireAllowlisted(userId, proposal.connectionId(), proposal.brokerAccountId());
            var id = UUID.randomUUID();
            // Existing paper proposals use the connection id as their local account row. Live proposals
            // must name the registered account explicitly and never silently substitute a connection id.
            var intent = OrderIntent.proposedLive(
                    id, proposal.brokerAccountId(), userId, proposal.connectionId(), proposal.side(), proposal.type(),
                    proposal.symbol(), proposal.quantity(), proposal.limitPrice(), proposal.currency());
            intents.saveAndFlush(intent);
            return id;
        }));
    }

    public OrderApprovalStepUpService.IssuedStepUp issueStepUp(UUID userId, UUID orderIntentId, Instant authTime) {
        var intent = ownedIntent(userId, orderIntentId);
        if (intent.getExecutionMode() != OrderExecutionMode.LIVE
                || (intent.getStatus() != OrderIntentStatus.PROPOSED && intent.getStatus() != OrderIntentStatus.APPROVED)) {
            throw conflict();
        }
        return stepUp.issue(userId, orderIntentId, authTime);
    }

    public void approve(UUID userId, UUID orderIntentId, String stepUpToken, String actor,
                        BigDecimal displayedQuantity, BigDecimal displayedMaxLoss, Currency displayedCurrency) {
        var target = ownedIntent(userId, orderIntentId);
        if (target.getExecutionMode() != OrderExecutionMode.LIVE || target.getStatus() != OrderIntentStatus.PROPOSED) {
            throw conflict();
        }
        var price = currentPrice(target);
        if (displayedQuantity == null || displayedMaxLoss == null || displayedCurrency != target.getTradingCurrency()
                || displayedQuantity.compareTo(target.getQuantity()) != 0
                || scaled(displayedMaxLoss, target.getTradingCurrency())
                        .compareTo(scaled(price.multiply(target.getQuantity()), target.getTradingCurrency())) != 0) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.CONFLICT,
                    "live order display does not match server quote");
        }
        transactions.execute(status -> {
            var intent = intents.findOwnedByIdForUpdate(orderIntentId, userId, target.getBrokerConnectionId())
                    .orElseThrow(() -> new LiveOrderActivationException(
                            LiveOrderActivationException.Code.NOT_FOUND, "live order not found"));
            if (intent.getStatus() != OrderIntentStatus.PROPOSED || intent.getExecutionMode() != OrderExecutionMode.LIVE) {
                throw conflict();
            }
            consumeStepUp(userId, orderIntentId, stepUpToken);
            var decision = risk.approveLive(new PreTradeRiskEngine.ApprovalCommand(
                    userId, target.getBrokerConnectionId(), orderIntentId, price, Instant.now(), actor));
            if (!decision.approved()) {
                throw new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED,
                        decision.reasons().getFirst().name());
            }
            return null;
        });
    }

    public DispatchResult dispatch(UUID userId, UUID orderIntentId, String clientOrderId,
                                   String stepUpToken, String actor) {
        requireId(userId, "userId");
        requireId(orderIntentId, "orderIntentId");
        requireClientOrderId(clientOrderId);
        var intent = ownedIntent(userId, orderIntentId);
        if (intent.getExecutionMode() != OrderExecutionMode.LIVE || intent.getStatus() != OrderIntentStatus.APPROVED) {
            throw conflict();
        }
        var price = currentPrice(intent);
        var prepared = Objects.requireNonNull(transactions.execute(status -> prepare(
                userId, orderIntentId, clientOrderId, stepUpToken, actor, price)));
        if (!prepared.callBroker()) {
            return result(prepared.attemptId());
        }

        BrokerResponse<BrokerOrderAck> response;
        try {
            response = orders.placeOrder(prepared.account(), prepared.request(), clientOrderId);
        } catch (RuntimeException exception) {
            response = null;
        }
        final var brokerResponse = response;
        var attemptId = prepared.attemptId();
        transactions.execute(status -> {
            if (brokerResponse == null || brokerResponse.value() == null) {
                submissions.markUnknown(attemptId, Instant.now(), new DispatchEvidence(clientOrderId, "transport-unknown"), actor);
                return null;
            }
            var ack = brokerResponse.value();
            if (ack.status() == com.jmj.trade.broker.BrokerOrderDispatchStatus.ACCEPTED) {
                if (ack.brokerOrderId() == null || !clientOrderId.equals(ack.idempotencyKey())) {
                    submissions.markManualReview(attemptId, Instant.now(),
                            new DispatchEvidence(clientOrderId,
                                    "client-order-id-mismatch brokerOrderId=" +
                                            Objects.toString(ack.brokerOrderId(), "missing")), actor);
                } else {
                    submissions.confirmBrokerOrder(attemptId, new OrderSubmissionService.BrokerConfirmation(
                            ack.brokerOrderId(), ack.idempotencyKey(), BrokerOrderStatus.PENDING,
                            BigDecimal.ZERO, Instant.now()), actor);
                }
            } else if (ack.status() == com.jmj.trade.broker.BrokerOrderDispatchStatus.REJECTED) {
                submissions.rejectBeforeBrokerOrderId(attemptId, Instant.now(),
                        new DispatchEvidence(clientOrderId, "broker-rejected"), actor);
            } else {
                submissions.markUnknown(attemptId, Instant.now(),
                        new DispatchEvidence(clientOrderId, "broker-unknown"), actor);
            }
            return null;
        });
        return result(attemptId);
    }

    /** 취소·정정은 운영자의 명시 호출만 허용하며, UNKNOWN 결과를 재전송하지 않는다. */
    public OperationResult cancel(UUID userId, UUID orderIntentId, String stepUpToken, String actor) {
        var target = liveBrokerOrder(userId, orderIntentId);
        var account = allowlistedAccount(userId, target.intent());
        consumeStepUp(userId, orderIntentId, stepUpToken);
        return invokeOperation(target.order().getBrokerOrderId(),
                () -> orders.cancelOrder(account, target.order().getBrokerOrderId()));
    }

    /** 미국 주식 정정은 공식 계약과 포트 정책에 맞춰 LIMIT 가격만 지원한다. */
    public OperationResult modify(UUID userId, UUID orderIntentId, BigDecimal newLimitPrice,
                                 String stepUpToken, String actor) {
        var target = liveBrokerOrder(userId, orderIntentId);
        if (target.intent().getType() != OrderType.LIMIT
                || newLimitPrice == null || newLimitPrice.signum() <= 0
                || target.intent().getLimitPrice() == null
                || newLimitPrice.compareTo(target.intent().getLimitPrice()) > 0) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED,
                    "live price modification exceeds the approved order limit");
        }
        if (killSwitches.anyEngaged(userId, target.intent().getBrokerConnectionId())) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED,
                    "live order kill switch is engaged");
        }
        currentPrice(target.intent());
        var account = allowlistedAccount(userId, target.intent());
        consumeStepUp(userId, orderIntentId, stepUpToken);
        return invokeOperation(target.order().getBrokerOrderId(),
                () -> orders.modifyOrder(account, BrokerOrderModification.reprice(
                        target.order().getBrokerOrderId(), newLimitPrice)));
    }

    private OperationResult invokeOperation(String originalBrokerOrderId,
                                            java.util.function.Supplier<BrokerResponse<BrokerOrderAck>> operation) {
        BrokerResponse<BrokerOrderAck> response;
        try {
            response = operation.get();
        } catch (RuntimeException exception) {
            response = null;
        }
        if (response == null || response.value() == null) {
            return new OperationResult(BrokerOrderDispatchStatus.UNKNOWN, originalBrokerOrderId);
        }
        var ack = response.value();
        return new OperationResult(ack.status(), ack.brokerOrderId());
    }

    private LiveBrokerOrder liveBrokerOrder(UUID userId, UUID orderIntentId) {
        var intent = ownedIntent(userId, orderIntentId);
        if (intent.getExecutionMode() != OrderExecutionMode.LIVE
                || intent.getStatus() != OrderIntentStatus.ACTIVE) {
            throw conflict();
        }
        var order = brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(orderIntentId)
                .orElseThrow(() -> new LiveOrderActivationException(
                        LiveOrderActivationException.Code.NOT_FOUND, "live broker order not found"));
        if (!intent.getBrokerAccountId().equals(order.getBrokerAccountId())) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.MANUAL_REVIEW_REQUIRED,
                    "live broker account mapping mismatch");
        }
        if (switch (order.getStatus()) {
            case FILLED, CANCELED, REJECTED, CANCEL_REJECTED, REPLACE_REJECTED, REPLACED -> true;
            default -> false;
        }) {
            throw conflict();
        }
        return new LiveBrokerOrder(intent, order);
    }

    private BrokerAccountRef allowlistedAccount(UUID userId, OrderIntent intent) {
        try {
            return safety.resolve(userId, intent.getBrokerConnectionId(), intent.getBrokerAccountId());
        } catch (IllegalStateException exception) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED,
                    "live account is not allowlisted");
        }
    }

    private Prepared prepare(UUID userId, UUID orderIntentId, String clientOrderId, String stepUpToken,
                             String actor, BigDecimal price) {
        var intent = intents.findOwnedByIdForUpdate(orderIntentId, userId,
                        ownedIntent(userId, orderIntentId).getBrokerConnectionId())
                .orElseThrow(() -> new LiveOrderActivationException(
                        LiveOrderActivationException.Code.NOT_FOUND, "live order not found"));
        if (intent.getExecutionMode() != OrderExecutionMode.LIVE || intent.getStatus() != OrderIntentStatus.APPROVED) {
            throw conflict();
        }
        var request = request(intent);
        var hash = requestHash(request);
        var existing = attempts.findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(orderIntentId, clientOrderId);
        if (existing.isPresent()) {
            if (!existing.get().getRequestBodyHash().equals(hash)) {
                throw conflict();
            }
            return new Prepared(existing.get().getId(), null, null, false);
        }
        consumeStepUp(userId, orderIntentId, stepUpToken);
        var submission = risk.submitLive(userId, intent.getBrokerConnectionId(), orderIntentId,
                price, Instant.now(), actor);
        if (!submission.decision().approved()) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED,
                    submission.decision().reasons().getFirst().name());
        }
        var reservation = requireReservation(userId, intent, orderIntentId,
                submission.decision().orderAmount());
        var attemptId = submissions.createInitialAttempt(
                orderIntentId, clientOrderId, hash, clientOrderId, Instant.now(), actor);
        submissions.startDispatch(attemptId, Instant.now(),
                new DispatchEvidence(clientOrderId, "toss-1.2.5"), actor);
        return new Prepared(attemptId, reservation.account(), request, true);
    }

    private BrokerOrderRequest request(OrderIntent intent) {
        return new BrokerOrderRequest(
                intent.getSide() == OrderSide.BUY ? BrokerOrderSide.BUY : BrokerOrderSide.SELL,
                intent.getType() == OrderType.LIMIT ? BrokerOrderType.LIMIT : BrokerOrderType.MARKET,
                intent.getSymbol(), intent.getQuantity(), intent.getLimitPrice(), intent.getTradingCurrency());
    }

    private void requireAllowlisted(UUID userId, UUID connectionId, UUID brokerAccountId) {
        try {
            safety.resolve(userId, connectionId, brokerAccountId);
        } catch (IllegalStateException exception) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED,
                    "live account is not allowlisted");
        }
    }

    private LiveOrderSafetyLedger.Reservation requireReservation(
            UUID userId, OrderIntent intent, UUID orderIntentId, BigDecimal amount) {
        try {
            return safety.reserve(userId, intent.getBrokerConnectionId(), intent.getBrokerAccountId(),
                    orderIntentId, intent.getTradingCurrency(), amount, Instant.now());
        } catch (IllegalStateException exception) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED,
                    "live account safety limit rejected the order");
        }
    }

    private BigDecimal currentPrice(OrderIntent intent) {
        var quote = quotes.getQuote(new BrokerConnectionRef(intent.getBrokerConnectionId()), intent.getSymbol()).value();
        var price = intent.getSide() == OrderSide.BUY ? quote.askPrice() : quote.bidPrice();
        if (price == null) {
            price = quote.lastPrice();
        }
        if (price == null || price.signum() <= 0 || quote.currency() != intent.getTradingCurrency()) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED,
                    "live quote is unavailable or mismatched");
        }
        return price;
    }

    private OrderIntent ownedIntent(UUID userId, UUID orderIntentId) {
        requireId(userId, "userId");
        requireId(orderIntentId, "orderIntentId");
        return intents.findById(orderIntentId)
                .filter(intent -> userId.equals(intent.getUserId()))
                .orElseThrow(() -> new LiveOrderActivationException(
                        LiveOrderActivationException.Code.NOT_FOUND, "live order not found"));
    }

    private DispatchResult result(UUID attemptId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var attempt = attempts.findById(attemptId).orElseThrow();
            var intent = intents.findById(attempt.getOrderIntentId()).orElseThrow();
            return new DispatchResult(attempt.getId(), attempt.getStatus(), intent.getStatus(),
                    attempt.getConfirmedBrokerOrderId());
        }));
    }

    private void consumeStepUp(UUID userId, UUID orderIntentId, String token) {
        try {
            stepUp.consume(userId, orderIntentId, token);
        } catch (PaperOrderWorkflowException exception) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.STEP_UP_REQUIRED,
                    "live order step-up is required");
        }
    }

    private static String requestHash(BrokerOrderRequest request) {
        return sha256(String.join("|", request.side().name(), request.type().name(), request.symbol(),
                request.quantity().stripTrailingZeros().toPlainString(),
                request.limitPrice() == null ? "" : request.limitPrice().stripTrailingZeros().toPlainString(),
                request.currency().name()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void validateOrder(OrderSide side, OrderType type, String symbol, BigDecimal quantity,
                                      BigDecimal limitPrice, Currency currency) {
        try {
            OrderIntent.proposed(UUID.randomUUID(), UUID.randomUUID(), side, type, symbol, quantity, limitPrice, currency);
        } catch (RuntimeException exception) {
            throw validation();
        }
    }

    private static BigDecimal scaled(BigDecimal amount, Currency currency) {
        return amount.setScale(currency == Currency.KRW ? 0 : 2, java.math.RoundingMode.HALF_UP);
    }

    private static void requireClientOrderId(String value) {
        if (value == null || !CLIENT_ORDER_ID.matcher(value).matches()) {
            throw validation();
        }
    }

    private static void requireId(UUID value, String field) {
        if (value == null) {
            throw new LiveOrderActivationException(LiveOrderActivationException.Code.VALIDATION, field + " is required");
        }
    }

    private static LiveOrderActivationException validation() {
        return new LiveOrderActivationException(LiveOrderActivationException.Code.VALIDATION, "invalid live order");
    }

    private static LiveOrderActivationException conflict() {
        return new LiveOrderActivationException(LiveOrderActivationException.Code.CONFLICT, "live order conflict");
    }

    public record Proposal(UUID connectionId, UUID brokerAccountId, OrderSide side, OrderType type,
                           String symbol, BigDecimal quantity, BigDecimal limitPrice, Currency currency) {
    }

    public record DispatchResult(UUID attemptId, SubmissionAttemptStatus attemptStatus,
                                 OrderIntentStatus intentStatus, UUID brokerOrderId) {
    }

    private record Prepared(UUID attemptId, BrokerAccountRef account, BrokerOrderRequest request, boolean callBroker) {
    }

    private record LiveBrokerOrder(OrderIntent intent, BrokerOrder order) {
    }

    public record OperationResult(BrokerOrderDispatchStatus status, String brokerOrderId) {
    }
}
