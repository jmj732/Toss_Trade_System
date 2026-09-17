package com.jmj.trade.order;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** MCP trade boundary. Read keys never construct this bean; live orders remain feature-gated. */
public final class McpOrderExecutionService {
    private static final Duration PROPOSAL_TTL = Duration.ofMinutes(5);
    private static final String CLIENT_PREFIX = "mcp_";

    private final LiveOrderActivationService activation;
    private final PreTradeRiskEngine risk;
    private final FreshPortfolioReadService portfolios;
    private final BrokerAdapter quotes;
    private final BrokerOrderPort orders;
    private final LiveOrderSafetyLedger safety;
    private final OrderIntentRepository intents;
    private final BrokerOrderRepository brokerOrders;
    private final SubmissionAttemptRepository attempts;
    private final JdbcTemplate jdbc;
    private final UnknownAttemptReconciler reconciler;
    private final OrderSubmissionService submissions;

    public McpOrderExecutionService(LiveOrderActivationService activation, PreTradeRiskEngine risk,
                                    FreshPortfolioReadService portfolios, BrokerAdapter quotes,
                                    BrokerOrderPort orders, LiveOrderSafetyLedger safety,
                                    OrderIntentRepository intents, BrokerOrderRepository brokerOrders,
                                    SubmissionAttemptRepository attempts, JdbcTemplate jdbc,
                                    UnknownAttemptReconciler reconciler, OrderSubmissionService submissions) {
        this.activation = Objects.requireNonNull(activation);
        this.risk = Objects.requireNonNull(risk);
        this.portfolios = Objects.requireNonNull(portfolios);
        this.quotes = Objects.requireNonNull(quotes);
        this.orders = Objects.requireNonNull(orders);
        this.safety = Objects.requireNonNull(safety);
        this.intents = Objects.requireNonNull(intents);
        this.brokerOrders = Objects.requireNonNull(brokerOrders);
        this.attempts = Objects.requireNonNull(attempts);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.reconciler = Objects.requireNonNull(reconciler);
        this.submissions = Objects.requireNonNull(submissions);
    }

    public PrepareResult prepare(UUID userId, UUID connectionId, PrepareCommand command) {
        requireIds(userId, connectionId);
        var order = normalize(command);
        var accountId = accountId(userId, connectionId);
        var account = safety.resolve(userId, connectionId, accountId);
        var portfolio = portfolios.read(userId, connectionId);
        if (portfolio == null || portfolio.stale() || portfolio.partial()) throw blocked("portfolio is stale or partial");
        var reference = referencePrice(quote(connectionId, order.symbol()), order.sideEnum());
        var estimatePrice = order.orderTypeEnum() == OrderType.LIMIT ? order.price() : reference;
        var amount = estimatePrice.multiply(order.quantity());
        var buyingPower = buyingPower(portfolio);
        if (order.sideEnum() == OrderSide.BUY && (buyingPower == null || buyingPower.compareTo(amount) < 0)) {
            throw blocked("buying power is insufficient");
        }
        var position = position(portfolio, order.symbol());
        var current = position == null || position.quantity() == null ? BigDecimal.ZERO : position.quantity();
        if (order.sideEnum() == OrderSide.SELL
                && (position == null || position.sellableQuantity() == null
                || position.sellableQuantity().compareTo(order.quantity()) < 0)) {
            throw blocked("sellable quantity is insufficient or unknown");
        }
        var open = orders.getOrders(account, BrokerOrderGroup.OPEN);
        if (open == null || open.value() == null) throw blocked("open order snapshot is unavailable");
        if (open.value().stream().anyMatch(o -> order.symbol().equalsIgnoreCase(o.symbol()))) {
            throw blocked("an open order already exists for this symbol");
        }
        var decision = risk.preview(new PreTradeRiskEngine.PreviewCommand(
                userId, connectionId, order.sideEnum(), order.orderTypeEnum(), order.symbol(), order.quantity(),
                order.price(), Currency.USD, reference, Instant.now()));
        if (!decision.approved()) throw blocked(decision.reasons().isEmpty()
                ? "pre-trade risk rejected order" : decision.reasons().getFirst().name());
        var id = activation.propose(userId, new LiveOrderActivationService.Proposal(
                connectionId, accountId, order.sideEnum(), order.orderTypeEnum(), order.symbol(), order.quantity(),
                order.price(), Currency.USD), PROPOSAL_TTL);
        var intent = intents.findOwnedById(id, userId, connectionId)
                .orElseThrow(() -> new IllegalStateException("proposal was not persisted"));
        intent.stampProposalReferencePrice(reference);
        intents.saveAndFlush(intent);
        submissions.auditConnector(id, "MCP_PREPARE", "MCP:" + userId,
                "{\"symbol\":\"" + order.symbol() + "\",\"side\":\"" + order.side() + "\"}");
        return new PrepareResult(proposalId(id), "READY_FOR_APPROVAL",
                new Order(order.symbol(), order.side(), order.orderType(), order.quantity(), order.price()), amount,
                new PreTrade(buyingPower, current,
                        order.sideEnum() == OrderSide.BUY ? current.add(order.quantity()) : current.subtract(order.quantity()),
                        "PASS"), intent.getExpiresAt());
    }

    public SubmitResult submit(UUID userId, UUID connectionId, String proposalId) {
        requireIds(userId, connectionId);
        var id = parseProposalId(proposalId);
        var intent = intents.findOwnedById(id, userId, connectionId)
                .orElseThrow(() -> new LiveOrderActivationException(LiveOrderActivationException.Code.NOT_FOUND,
                        "proposal not found"));
        var existing = brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(id);
        if (existing.isPresent()) return submitResult(proposalId, existing.get());
        var latest = attempts.findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(id, clientOrderId(id));
        if (latest.isPresent() && latest.get().getStatus() == SubmissionAttemptStatus.UNKNOWN) {
            return reconcileUnknown(proposalId, latest.get().getId(), intent);
        }
        if (intent.getStatus() == OrderIntentStatus.RECONCILIATION_REQUIRED
                || intent.getStatus() == OrderIntentStatus.MANUAL_REVIEW_REQUIRED) {
            return new SubmitResult(proposalId, "UNKNOWN", null);
        }
        if (intent.getStatus() != OrderIntentStatus.PROPOSED) {
            return new SubmitResult(proposalId, mapIntentStatus(intent.getStatus()), null);
        }
        if (intent.getExpiresAt() != null && !Instant.now().isBefore(intent.getExpiresAt())) {
            activation.expireFromMcp(userId, id, "MCP:" + userId);
            return new SubmitResult(proposalId, "EXPIRED", null);
        }
        if (intent.getProposalReferencePrice() == null) throw blocked("proposal reference price is unavailable");
        var portfolio = portfolios.read(userId, connectionId);
        if (portfolio == null || portfolio.stale() || portfolio.partial()) throw blocked("portfolio is stale or partial");
        var price = referencePrice(quote(connectionId, intent.getSymbol()), intent.getSide());
        if (price.compareTo(intent.getProposalReferencePrice()) != 0) throw blocked("quote changed since proposal");
        activation.approveFromMcp(userId, id, "MCP:" + userId);
        var dispatch = activation.dispatchFromMcp(userId, id, clientOrderId(id), "MCP:" + userId, price);
        var persisted = brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(id);
        if (persisted.isPresent()) return submitResult(proposalId, persisted.get());
        if (dispatch.attemptStatus() == SubmissionAttemptStatus.UNKNOWN) {
            return reconcileUnknown(proposalId, dispatch.attemptId(), intent);
        }
        return new SubmitResult(proposalId, mapIntentStatus(dispatch.intentStatus()), null);
    }

    public CancelResult cancel(UUID userId, UUID connectionId, String brokerOrderId) {
        requireIds(userId, connectionId);
        if (brokerOrderId == null || brokerOrderId.isBlank()) throw blocked("brokerOrderId is required");
        var accountId = accountId(userId, connectionId);
        var local = brokerOrders.findByBrokerAccountIdAndBrokerOrderId(accountId, brokerOrderId)
                .orElseThrow(() -> new LiveOrderActivationException(LiveOrderActivationException.Code.NOT_FOUND,
                        "broker order not found"));
        if (local.getStatus() == BrokerOrderStatus.FILLED || local.getStatus() == BrokerOrderStatus.CANCELED
                || local.getStatus() == BrokerOrderStatus.REJECTED) throw blocked("order is already terminal");
        var result = activation.cancelFromMcp(userId, local.getOrderIntentId(), "MCP:" + userId);
        var status = result.status() == com.jmj.trade.broker.BrokerOrderDispatchStatus.UNKNOWN ? "UNKNOWN"
                : result.status() == com.jmj.trade.broker.BrokerOrderDispatchStatus.ACCEPTED ? "CANCEL_REQUESTED" : "REJECTED";
        if (result.status() == com.jmj.trade.broker.BrokerOrderDispatchStatus.ACCEPTED) {
            local.updateProjection(BrokerOrderStatus.CANCELING);
            brokerOrders.saveAndFlush(local);
        }
        submissions.auditConnector(local.getOrderIntentId(), "MCP_CANCEL", "MCP:" + userId,
                "{\"brokerOrderId\":\"redacted\",\"status\":\"" + status + "\"}");
        return new CancelResult(brokerOrderId, status);
    }

    public OrderResult getOrder(UUID userId, UUID connectionId, String brokerOrderId) {
        requireIds(userId, connectionId);
        if (brokerOrderId == null || brokerOrderId.isBlank()) throw blocked("brokerOrderId is required");
        var account = safety.resolve(userId, connectionId, accountId(userId, connectionId));
        var response = orders.getOrder(account, brokerOrderId);
        return response == null || response.value() == null
                ? new OrderResult(brokerOrderId, "UNKNOWN")
                : new OrderResult(brokerOrderId, mapBrokerStatus(response.value().status()));
    }

    public OrderResult getOrderByClientId(UUID userId, UUID connectionId, String clientOrderId) {
        requireIds(userId, connectionId);
        if (clientOrderId == null || clientOrderId.isBlank()) throw blocked("clientOrderId is required");
        var local = brokerOrders.findByBrokerAccountIdAndClientOrderId(accountId(userId, connectionId), clientOrderId)
                .orElseThrow(() -> new LiveOrderActivationException(LiveOrderActivationException.Code.NOT_FOUND,
                        "broker order not found"));
        return getOrder(userId, connectionId, local.getBrokerOrderId());
    }

    private SubmitResult submitResult(String proposalId, BrokerOrder order) {
        return new SubmitResult(proposalId, mapBrokerStatus(order.getStatus()), order.getBrokerOrderId());
    }

    private SubmitResult reconcileUnknown(String proposalId, UUID attemptId, OrderIntent intent) {
        try {
            var account = safety.resolve(intent.getUserId(), intent.getBrokerConnectionId(), intent.getBrokerAccountId());
            reconciler.reconcile(new UnknownAttemptReconciler.Command(
                    attemptId, account, intent.getUserId(), Instant.now(), "MCP:" + intent.getUserId(),
                    "MCP submit response was not confirmed"));
            submissions.auditConnector(intent.getId(), "MCP_RECONCILIATION", "MCP:" + intent.getUserId(),
                    "{\"attemptId\":\"" + attemptId + "\"}");
            return brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(intent.getId())
                    .map(order -> submitResult(proposalId, order))
                    .orElseGet(() -> new SubmitResult(proposalId, "UNKNOWN", null));
        } catch (RuntimeException ignored) {
            return new SubmitResult(proposalId, "UNKNOWN", null);
        }
    }

    private UUID accountId(UUID userId, UUID connectionId) {
        var ids = jdbc.query("SELECT broker_account_id FROM real_order_account_allowlist WHERE user_id = ? AND broker_connection_id = ? AND enabled = TRUE",
                (rs, row) -> rs.getObject("broker_account_id", UUID.class), userId, connectionId);
        if (ids.size() != 1) throw blocked("live account mapping is missing or ambiguous");
        return ids.getFirst();
    }

    private Quote quote(UUID connectionId, String symbol) {
        var response = quotes.getQuote(new BrokerConnectionRef(connectionId), symbol);
        var quote = response == null ? null : response.value();
        if (quote == null || !symbol.equalsIgnoreCase(quote.symbol()) || quote.currency() != Currency.USD) {
            throw blocked("quote is unavailable or mismatched");
        }
        return quote;
    }

    private static BigDecimal referencePrice(Quote quote, OrderSide side) {
        var value = side == OrderSide.BUY ? quote.askPrice() : quote.bidPrice();
        if (value == null || value.signum() <= 0) value = quote.lastPrice();
        if (value == null || value.signum() <= 0) throw blocked("quote price is unavailable");
        return value;
    }

    private static BigDecimal buyingPower(PortfolioReadService.PortfolioView view) {
        var value = view.buyingPower() == null ? null : view.buyingPower().get(Currency.USD.name());
        return value == null ? null : value.cashBuyingPower();
    }

    private static PortfolioReadService.PositionView position(PortfolioReadService.PortfolioView view, String symbol) {
        if (view.positions() == null) return null;
        return view.positions().stream().filter(p -> symbol.equalsIgnoreCase(p.symbol())).findFirst().orElse(null);
    }

    private static PrepareCommand normalize(PrepareCommand command) {
        if (command == null) throw blocked("order is required");
        var symbol = command.symbol() == null ? "" : command.symbol().trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9.-]{1,30}")) throw blocked("symbol is invalid");
        OrderSide side; OrderType type;
        try {
            side = OrderSide.valueOf(command.side().trim().toUpperCase(Locale.ROOT));
            type = OrderType.valueOf(command.orderType().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) { throw blocked("side or orderType is invalid"); }
        if (command.quantity() == null || command.quantity().signum() <= 0) throw blocked("quantity must be positive");
        if (type == OrderType.LIMIT && (command.price() == null || command.price().signum() <= 0)) throw blocked("LIMIT price must be positive");
        if (type == OrderType.MARKET && command.price() != null) throw blocked("MARKET price must be omitted");
        return new PrepareCommand(symbol, side.name(), type.name(), command.quantity(), command.price());
    }

    private static UUID parseProposalId(String value) {
        if (value == null || !value.startsWith("ordp_")) throw blocked("proposalId is invalid");
        try { return UUID.fromString(value.substring(5)); } catch (IllegalArgumentException e) { throw blocked("proposalId is invalid"); }
    }

    private static String proposalId(UUID id) { return "ordp_" + id; }
    private static String clientOrderId(UUID id) { return CLIENT_PREFIX + id.toString().replace("-", ""); }
    private static void requireIds(UUID userId, UUID connectionId) { if (userId == null || connectionId == null) throw blocked("identity is required"); }
    private static LiveOrderActivationException blocked(String message) { return new LiveOrderActivationException(LiveOrderActivationException.Code.SAFETY_BLOCKED, message); }
    private static String mapIntentStatus(OrderIntentStatus status) {
        return switch (status) { case COMPLETED -> "FILLED"; case PARTIALLY_COMPLETED -> "PARTIAL_FILLED"; case CANCELED -> "CANCELED"; case REJECTED, BLOCKED -> "REJECTED"; case EXPIRED -> "EXPIRED"; default -> "UNKNOWN"; };
    }
    private static String mapBrokerStatus(BrokerOrderStatus status) {
        return switch (status) { case PENDING, CANCELING, REPLACING -> "SUBMITTED"; case PARTIALLY_FILLED -> "PARTIAL_FILLED"; case FILLED -> "FILLED"; case CANCELED -> "CANCELED"; default -> "REJECTED"; };
    }
    private static String mapBrokerStatus(BrokerOrderLifecycle status) {
        return switch (status) { case PENDING, CANCELING, REPLACING -> "SUBMITTED"; case PARTIALLY_FILLED -> "PARTIAL_FILLED"; case FILLED -> "FILLED"; case CANCELED -> "CANCELED"; default -> "REJECTED"; };
    }

    public record PrepareCommand(String symbol, String side, String orderType, BigDecimal quantity, BigDecimal price) {
        OrderSide sideEnum() { return OrderSide.valueOf(side); }
        OrderType orderTypeEnum() { return OrderType.valueOf(orderType); }
    }
    public record PrepareResult(String proposalId, String status, Order order, BigDecimal estimatedAmount, PreTrade preTrade, Instant expiresAt) {}
    public record SubmitResult(String proposalId, String status, String brokerOrderId) {}
    public record CancelResult(String brokerOrderId, String status) {}
    public record OrderResult(String brokerOrderId, String status) {}
    public record Order(String symbol, String side, String orderType, BigDecimal quantity, BigDecimal price) {}
    public record PreTrade(BigDecimal buyingPower, BigDecimal currentQuantity, BigDecimal projectedQuantity, String riskGate) {}
}
