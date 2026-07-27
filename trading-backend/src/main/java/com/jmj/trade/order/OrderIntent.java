package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@DynamicUpdate
@Table(name = "order_intents")
public class OrderIntent {

    @Id
    private UUID id;

    @Column(name = "broker_account_id", nullable = false)
    private UUID brokerAccountId;

    @Column(nullable = false, precision = 28, scale = 10)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 10)
    private OrderType type;

    @Column(length = 30)
    private String symbol;

    @Column(name = "limit_price", precision = 28, scale = 10)
    private BigDecimal limitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "trading_currency", length = 3)
    private Currency tradingCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderIntentStatus status;

    @Column(name = "terminal_reason", length = 80)
    private String terminalReason;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "final_filled_quantity", precision = 28, scale = 10)
    private BigDecimal finalFilledQuantity;

    @Column(name = "remaining_quantity", precision = 28, scale = 10)
    private BigDecimal remainingQuantity;

    @Version
    @Column(nullable = false)
    private long version;

    protected OrderIntent() {
    }

    private OrderIntent(UUID id, UUID brokerAccountId, BigDecimal quantity) {
        this.id = id;
        this.brokerAccountId = brokerAccountId;
        this.quantity = quantity;
        this.status = OrderIntentStatus.PROPOSED;
    }

    public static OrderIntent proposed(UUID id, UUID brokerAccountId, BigDecimal quantity) {
        return new OrderIntent(id, brokerAccountId, quantity);
    }

    public static OrderIntent proposed(
            UUID id,
            UUID brokerAccountId,
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
        BigDecimal limitPrice,
            Currency tradingCurrency
    ) {
        var intent = new OrderIntent(id, brokerAccountId, quantity);
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (side == null || type == null || tradingCurrency == null) {
            throw new IllegalArgumentException("side, type and tradingCurrency are required");
        }
        if (symbol == null || symbol.isBlank() || symbol.length() > 30) {
            throw new IllegalArgumentException("symbol must contain 1 to 30 characters");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new IllegalArgumentException("MARKET order must not have limitPrice");
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("LIMIT order requires positive limitPrice");
        }
        intent.side = side;
        intent.type = type;
        intent.symbol = symbol;
        intent.limitPrice = limitPrice;
        intent.tradingCurrency = tradingCurrency;
        return intent;
    }

    public void approve() {
        transitionTo(OrderIntentStatus.APPROVED);
    }

    public void startRevalidation() {
        transitionTo(OrderIntentStatus.REVALIDATING);
    }

    public void markSubmissionPending() {
        transitionTo(OrderIntentStatus.SUBMISSION_PENDING);
    }

    public void requireReconciliation() {
        transitionTo(OrderIntentStatus.RECONCILIATION_REQUIRED);
    }

    public void requireManualReview() {
        transitionTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
    }

    public void activate() {
        transitionTo(OrderIntentStatus.ACTIVE);
    }

    public void terminate(
            OrderIntentStatus terminalStatus,
            String terminalReason,
            Instant terminalAt,
            BigDecimal finalFilledQuantity
    ) {
        requireOpen();
        requireTerminalData(terminalStatus, terminalReason, terminalAt, finalFilledQuantity);
        requireAllowedTransition(terminalStatus);
        requireTerminalQuantity(terminalStatus, finalFilledQuantity);
        this.status = terminalStatus;
        this.terminalReason = terminalReason;
        this.terminalAt = terminalAt;
        this.finalFilledQuantity = finalFilledQuantity;
        this.remainingQuantity = quantity.subtract(finalFilledQuantity);
    }

    private void transitionTo(OrderIntentStatus nextStatus) {
        requireOpen();
        requireAllowedTransition(nextStatus);
        status = nextStatus;
    }

    private void requireOpen() {
        if (isTerminal(status)) {
            throw new IllegalStateException("terminal order intent is immutable");
        }
    }

    private void requireAllowedTransition(OrderIntentStatus nextStatus) {
        if (!isAllowedTransition(status, nextStatus)) {
            throw new IllegalStateException("invalid order intent transition: " + status + " -> " + nextStatus);
        }
    }

    private static boolean isAllowedTransition(OrderIntentStatus current, OrderIntentStatus next) {
        return switch (current) {
            case PROPOSED -> next == OrderIntentStatus.APPROVED
                    || next == OrderIntentStatus.CANCELED
                    || next == OrderIntentStatus.EXPIRED;
            case APPROVED -> next == OrderIntentStatus.REVALIDATING
                    || next == OrderIntentStatus.EXPIRED;
            case REVALIDATING -> next == OrderIntentStatus.SUBMISSION_PENDING
                    || next == OrderIntentStatus.BLOCKED
                    || next == OrderIntentStatus.EXPIRED;
            case SUBMISSION_PENDING -> next == OrderIntentStatus.ACTIVE
                    || next == OrderIntentStatus.REJECTED
                    || next == OrderIntentStatus.RECONCILIATION_REQUIRED;
            case RECONCILIATION_REQUIRED -> next == OrderIntentStatus.ACTIVE
                    || next == OrderIntentStatus.SUBMISSION_PENDING
                    || next == OrderIntentStatus.MANUAL_REVIEW_REQUIRED;
            case ACTIVE -> next == OrderIntentStatus.COMPLETED
                    || next == OrderIntentStatus.PARTIALLY_COMPLETED
                    || next == OrderIntentStatus.CANCELED
                    || next == OrderIntentStatus.MANUAL_REVIEW_REQUIRED;
            case MANUAL_REVIEW_REQUIRED -> next == OrderIntentStatus.ACTIVE
                    || next == OrderIntentStatus.COMPLETED
                    || next == OrderIntentStatus.PARTIALLY_COMPLETED
                    || next == OrderIntentStatus.CANCELED
                    || next == OrderIntentStatus.REJECTED;
            case COMPLETED, PARTIALLY_COMPLETED, CANCELED, REJECTED, EXPIRED, BLOCKED -> false;
        };
    }

    private static boolean isTerminal(OrderIntentStatus status) {
        return status == OrderIntentStatus.COMPLETED
                || status == OrderIntentStatus.PARTIALLY_COMPLETED
                || status == OrderIntentStatus.CANCELED
                || status == OrderIntentStatus.REJECTED
                || status == OrderIntentStatus.EXPIRED
                || status == OrderIntentStatus.BLOCKED;
    }

    private void requireTerminalData(
            OrderIntentStatus terminalStatus,
            String terminalReason,
            Instant terminalAt,
            BigDecimal finalFilledQuantity
    ) {
        if (!isTerminal(terminalStatus)) {
            throw new IllegalArgumentException("terminalStatus must be terminal");
        }
        if (terminalReason == null || terminalReason.isBlank()) {
            throw new IllegalArgumentException("terminalReason is required");
        }
        if (terminalAt == null) {
            throw new IllegalArgumentException("terminalAt is required");
        }
        if (finalFilledQuantity == null) {
            throw new IllegalArgumentException("finalFilledQuantity is required");
        }
        if (finalFilledQuantity.compareTo(BigDecimal.ZERO) < 0
                || finalFilledQuantity.compareTo(quantity) > 0) {
            throw new IllegalArgumentException("finalFilledQuantity must be between zero and quantity");
        }
    }

    private void requireTerminalQuantity(OrderIntentStatus terminalStatus, BigDecimal finalFilledQuantity) {
        if (terminalStatus == OrderIntentStatus.COMPLETED
                && finalFilledQuantity.compareTo(quantity) != 0) {
            throw new IllegalArgumentException("COMPLETED requires finalFilledQuantity equal to quantity");
        }
        if (terminalStatus == OrderIntentStatus.PARTIALLY_COMPLETED
                && (finalFilledQuantity.compareTo(BigDecimal.ZERO) <= 0
                || finalFilledQuantity.compareTo(quantity) >= 0)) {
            throw new IllegalArgumentException("PARTIALLY_COMPLETED requires partial finalFilledQuantity");
        }
        if ((terminalStatus == OrderIntentStatus.CANCELED
                || terminalStatus == OrderIntentStatus.REJECTED
                || terminalStatus == OrderIntentStatus.EXPIRED
                || terminalStatus == OrderIntentStatus.BLOCKED)
                && finalFilledQuantity.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(terminalStatus + " requires zero finalFilledQuantity");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getBrokerAccountId() {
        return brokerAccountId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderType getType() {
        return type;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public Currency getTradingCurrency() {
        return tradingCurrency;
    }

    public OrderIntentStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public String getTerminalReason() {
        return terminalReason;
    }

    public Instant getTerminalAt() {
        return terminalAt;
    }

    public BigDecimal getFinalFilledQuantity() {
        return finalFilledQuantity;
    }

    public BigDecimal getRemainingQuantity() {
        return remainingQuantity;
    }
}
