package com.jmj.trade.order;

import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.investment.InvestmentBuyRiskGate;
import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.investment.InvestmentSellReadinessService;
import com.jmj.trade.investment.InvestmentRiskPreflightService;
import com.jmj.trade.investment.InvestmentStrategicBuyRevalidationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final com.jmj.trade.investment.InvestmentSellReadinessService sellReadiness;
    private final InvestmentContextService marketRiskContext;
    private final TransactionTemplate transactions;
    private final InvestmentRiskPreflightService riskPreflight;
    private final InvestmentStrategicBuyRevalidationService buyRevalidation;

    public McpOrderExecutionService(LiveOrderActivationService activation, PreTradeRiskEngine risk,
                                    FreshPortfolioReadService portfolios, BrokerAdapter quotes,
                                    BrokerOrderPort orders, LiveOrderSafetyLedger safety,
                                    OrderIntentRepository intents, BrokerOrderRepository brokerOrders,
                                    SubmissionAttemptRepository attempts, JdbcTemplate jdbc,
                                    UnknownAttemptReconciler reconciler, OrderSubmissionService submissions) {
        this(activation, risk, portfolios, quotes, orders, safety, intents, brokerOrders, attempts, jdbc,
                reconciler, submissions, null, null, null, null, null);
    }

    public McpOrderExecutionService(LiveOrderActivationService activation, PreTradeRiskEngine risk,
                                    FreshPortfolioReadService portfolios, BrokerAdapter quotes,
                                    BrokerOrderPort orders, LiveOrderSafetyLedger safety,
                                    OrderIntentRepository intents, BrokerOrderRepository brokerOrders,
                                    SubmissionAttemptRepository attempts, JdbcTemplate jdbc,
                                    UnknownAttemptReconciler reconciler, OrderSubmissionService submissions,
                                    com.jmj.trade.investment.InvestmentSellReadinessService sellReadiness,
                                    TransactionTemplate transactions) {
        this(activation, risk, portfolios, quotes, orders, safety, intents, brokerOrders, attempts, jdbc,
                reconciler, submissions, sellReadiness, transactions, null, null, null);
    }

    public McpOrderExecutionService(LiveOrderActivationService activation, PreTradeRiskEngine risk,
                                    FreshPortfolioReadService portfolios, BrokerAdapter quotes,
                                    BrokerOrderPort orders, LiveOrderSafetyLedger safety,
                                    OrderIntentRepository intents, BrokerOrderRepository brokerOrders,
                                    SubmissionAttemptRepository attempts, JdbcTemplate jdbc,
                                    UnknownAttemptReconciler reconciler, OrderSubmissionService submissions,
                                    com.jmj.trade.investment.InvestmentSellReadinessService sellReadiness,
                                    TransactionTemplate transactions, InvestmentContextService marketRiskContext) {
        this(activation, risk, portfolios, quotes, orders, safety, intents, brokerOrders, attempts, jdbc,
                reconciler, submissions, sellReadiness, transactions, marketRiskContext, null, null);
    }

    public McpOrderExecutionService(LiveOrderActivationService activation, PreTradeRiskEngine risk,
                                    FreshPortfolioReadService portfolios, BrokerAdapter quotes,
                                    BrokerOrderPort orders, LiveOrderSafetyLedger safety,
                                    OrderIntentRepository intents, BrokerOrderRepository brokerOrders,
                                    SubmissionAttemptRepository attempts, JdbcTemplate jdbc,
                                    UnknownAttemptReconciler reconciler, OrderSubmissionService submissions,
                                    com.jmj.trade.investment.InvestmentSellReadinessService sellReadiness,
                                    TransactionTemplate transactions, InvestmentContextService marketRiskContext,
                                    InvestmentRiskPreflightService riskPreflight,
                                    InvestmentStrategicBuyRevalidationService buyRevalidation) {
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
        this.sellReadiness = sellReadiness;
        this.transactions = transactions;
        this.marketRiskContext = marketRiskContext;
        this.riskPreflight = riskPreflight;
        this.buyRevalidation = buyRevalidation;
    }

    public PrepareResult prepare(UUID userId, UUID connectionId, PrepareCommand command) {
        if (transactions == null) return prepareLocked(userId, connectionId, command, null, null);
        return transactions.execute(status -> prepareLocked(userId, connectionId, command, null, null));
    }

    /** Links a caller-authored ADD decision to this BUY proposal; it is not AI revalidation evidence by itself. */
    public PrepareResult prepareWithDecision(UUID userId, UUID connectionId, UUID decisionId,
                                             PrepareCommand command) {
        if (decisionId == null) throw blocked("investment decision ID is required");
        if (transactions == null) return prepareLocked(userId, connectionId, command, null, decisionId);
        return transactions.execute(status -> prepareLocked(userId, connectionId, command, null, decisionId));
    }

    /** Strict defensive SELL path. Action and target live in an owned, current Decision Ledger row. */
    public PrepareResult prepareDefensive(UUID userId, UUID connectionId, UUID decisionId, UUID eventId,
                                          String symbol, BigDecimal quantity, BigDecimal limitPrice) {
        if (transactions == null) throw blocked("transaction lock is unavailable");
        return transactions.execute(status -> {
            lockOwnedConnection(userId, connectionId);
            if (sellReadiness == null) throw blocked("defensive sell readiness is unavailable");
            var readiness = sellReadiness.evaluate(userId, connectionId, decisionId, eventId);
            if (!"READY".equals(readiness.actionStatus()) || !"READY".equals(readiness.sizingStatus())
                    || !"READY".equals(readiness.pricingStatus()) || !"READY".equals(readiness.prepareStatus()))
                throw blocked("defensive sell readiness is blocked: " + String.join(",", readiness.blockers()));
            if (!"REDUCE".equals(readiness.action()) && !"EXIT".equals(readiness.action()))
                throw blocked("authorized decision is not REDUCE or EXIT");
            var normalized = normalize(new PrepareCommand(symbol, "SELL", "LIMIT", quantity, limitPrice));
            if (!readiness.ticker().equals(normalized.symbol())
                    || normalized.quantity().compareTo(readiness.quantity()) != 0
                    || normalized.price().compareTo(readiness.limitPrice()) != 0)
                throw blocked("requested order does not match current defensive sell readiness");
            var proposal = prepareLocked(userId, connectionId, normalized, readiness.limitPrice(), null);
            var linked = jdbc.update("""
                    UPDATE order_intents
                       SET investment_decision_id = ?, investment_risk_event_id = ?
                     WHERE id = ? AND user_id = ? AND broker_connection_id = ? AND status = 'PROPOSED'
                    """, decisionId, eventId, parseProposalId(proposal.proposalId()), userId, connectionId);
            if (linked != 1) throw blocked("defensive proposal audit link could not be persisted");
            return new PrepareResult(proposal.proposalId(), proposal.status(), proposal.order(),
                    proposal.estimatedAmount(), proposal.preTrade(), proposal.expiresAt(),
                    decisionId, eventId, readiness, proposal.investmentBuyGate());
        });
    }

    private PrepareResult prepareLocked(UUID userId, UUID connectionId, PrepareCommand command,
                                        BigDecimal verifiedSellReference, UUID buyDecisionId) {
        requireIds(userId, connectionId);
        var order = normalize(command);
        if (buyDecisionId != null && order.sideEnum() != OrderSide.BUY)
            throw blocked("investment decision links are only supported for BUY proposals");
        lockOwnedConnection(userId, connectionId);
        if (hasActivePreparedOrder(userId, connectionId, order.symbol()))
            throw blocked("an active prepared order already exists for this symbol");
        var accountId = accountId(userId, connectionId);
        var account = safety.resolve(userId, connectionId, accountId);
        var portfolio = portfolios.read(userId, connectionId);
        var accountLocalDefensiveSell = verifiedSellReference != null
                && InvestmentSellReadinessService.accountLocalSellSnapshotUsable(portfolio);
        if (portfolio == null || portfolio.stale() || portfolio.positions() == null
                || (portfolio.partial() && !accountLocalDefensiveSell))
            throw blocked("portfolio is stale or partial");
        var liveQuote = verifiedSellReference == null ? quote(connectionId, order.symbol()) : null;
        var reference = liveQuote == null ? verifiedSellReference : referencePrice(liveQuote, order.sideEnum());
        var buyGate = requireMarketRiskForBuy(userId, order.symbol(), order.sideEnum(), marketRiskPrice(liveQuote, reference),
                liveQuote == null ? null : liveQuote.observedAt());
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
        if (buyDecisionId != null) {
            var linkedDecision = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM investment_decision_ledger
                         WHERE user_id = ? AND decision_id = ? AND asset = ? AND action = 'ADD'
                    )
                    """, Boolean.class, userId, buyDecisionId, order.symbol());
            if (!Boolean.TRUE.equals(linkedDecision)) throw blocked("linked BUY decision is unavailable or mismatched");
            var linked = jdbc.update("""
                    UPDATE order_intents SET investment_buy_decision_id = ?
                     WHERE id = ? AND user_id = ? AND broker_connection_id = ? AND side = 'BUY'
                       AND status = 'PROPOSED' AND investment_buy_decision_id IS NULL
                    """, buyDecisionId, id, userId, connectionId);
            if (linked != 1) throw blocked("BUY decision link could not be persisted");
        }
        submissions.auditConnector(id, "MCP_PREPARE", "MCP:" + userId,
                "{\"symbol\":\"" + order.symbol() + "\",\"side\":\"" + order.side() + "\"}");
        return new PrepareResult(proposalId(id), "READY_FOR_APPROVAL",
                new Order(order.symbol(), order.side(), order.orderType(), order.quantity(), order.price()), amount,
                new PreTrade(buyingPower, current,
                        order.sideEnum() == OrderSide.BUY ? current.add(order.quantity()) : current.subtract(order.quantity()),
                        "PASS"), intent.getExpiresAt(), null, null, null, buyGate);
    }

    private void lockOwnedConnection(UUID userId, UUID connectionId) {
        var owned = jdbc.query("""
                SELECT id FROM broker_connections
                 WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                 FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), connectionId, userId);
        if (owned.size() != 1) throw blocked("owned active broker connection is unavailable");
    }

    private boolean hasActivePreparedOrder(UUID userId, UUID connectionId, String symbol) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM order_intents
                     WHERE user_id = ? AND broker_connection_id = ? AND upper(symbol) = ?
                       AND (status <> 'PROPOSED' OR expires_at IS NULL OR expires_at > now())
                       AND status IN ('PROPOSED','APPROVED','REVALIDATING','SUBMISSION_PENDING',
                                      'RECONCILIATION_REQUIRED','MANUAL_REVIEW_REQUIRED','ACTIVE','PARTIALLY_COMPLETED')
                )
                """, Boolean.class, userId, connectionId, symbol));
    }

    private DefensiveLink defensiveLink(UUID intentId, UUID userId, UUID connectionId) {
        var rows = jdbc.query("""
                SELECT investment_decision_id, investment_risk_event_id, investment_buy_decision_id
                  FROM order_intents
                 WHERE id = ? AND user_id = ? AND broker_connection_id = ?
                """, (rs, row) -> new DefensiveLink(
                (UUID) rs.getObject("investment_decision_id"),
                (UUID) rs.getObject("investment_risk_event_id"),
                (UUID) rs.getObject("investment_buy_decision_id")), intentId, userId, connectionId);
        if (rows.size() != 1) throw blocked("order proposal was not found");
        var link = rows.getFirst();
        if ((link.decisionId() == null) != (link.eventId() == null))
            throw blocked("defensive proposal audit link is incomplete");
        if (link.buyDecisionId() != null && (link.decisionId() != null || link.eventId() != null))
            throw blocked("order proposal has conflicting investment decision links");
        return link.decisionId() == null && link.buyDecisionId() == null ? null : link;
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
        var proposalLinks = defensiveLink(id, userId, connectionId);
        var defensive = proposalLinks != null && proposalLinks.decisionId() != null ? proposalLinks : null;
        var buyDecisionId = proposalLinks == null ? null : proposalLinks.buyDecisionId();
        var portfolio = portfolios.read(userId, connectionId);
        var accountLocalDefensiveSell = defensive != null
                && intent.getSide() == OrderSide.SELL
                && InvestmentSellReadinessService.accountLocalSellSnapshotUsable(portfolio);
        if (portfolio == null || portfolio.stale() || portfolio.positions() == null
                || (portfolio.partial() && !accountLocalDefensiveSell))
            throw blocked("portfolio is stale or partial");
        BigDecimal price;
        Quote liveQuote = null;
        if (defensive != null) {
            if (intent.getSide() != OrderSide.SELL || intent.getType() != OrderType.LIMIT)
                throw blocked("risk-linked proposal must be a SELL LIMIT");
            if (sellReadiness == null) throw blocked("defensive sell readiness is unavailable");
            var readiness = sellReadiness.evaluate(userId, connectionId,
                    defensive.decisionId(), defensive.eventId(), id);
            if (!"READY".equals(readiness.actionStatus()) || !"READY".equals(readiness.sizingStatus())
                    || !"READY".equals(readiness.pricingStatus()) || !"READY".equals(readiness.prepareStatus())
                    || !intent.getSymbol().equalsIgnoreCase(readiness.ticker())
                    || intent.getQuantity().compareTo(readiness.quantity()) != 0
                    || intent.getLimitPrice() == null || intent.getLimitPrice().compareTo(readiness.limitPrice()) != 0)
                throw blocked("risk-linked sell readiness changed since proposal");
            price = readiness.limitPrice();
        } else {
            liveQuote = quote(connectionId, intent.getSymbol());
            price = referencePrice(liveQuote, intent.getSide());
        }
        if (price.compareTo(intent.getProposalReferencePrice()) != 0) throw blocked("quote changed since proposal");
        var currentMarketPrice = marketRiskPrice(liveQuote, price);
        var buyGate = requireMarketRiskForBuy(userId, intent.getSymbol(), intent.getSide(), currentMarketPrice,
                liveQuote == null ? null : liveQuote.observedAt(), id);
        if (intent.getSide() == OrderSide.BUY && buyGate != null
                && "REVIEW_REQUIRED".equals(buyGate.status())
                && "STRATEGIC".equals(buyGate.investmentHorizon())) {
            validateStrategicReviewSubmission(userId, intent, buyDecisionId, buyGate, currentMarketPrice, portfolio);
        }
        // Authenticated submit is an individual order approval only. Strategic thesis revalidation is
        // separately persisted and checked above; submit_order is never accepted as its evidence.
        activation.approveFromMcp(userId, id, "MCP:" + userId);
        var dispatch = defensive == null
                ? activation.dispatchFromMcp(userId, id, clientOrderId(id), "MCP:" + userId, price)
                : activation.dispatchFromMcpWithVerifiedReference(userId, id, clientOrderId(id),
                "MCP:" + userId, price);
        var persisted = brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(id);
        if (persisted.isPresent()) return submitResult(proposalId, persisted.get(), buyGate);
        if (dispatch.attemptStatus() == SubmissionAttemptStatus.UNKNOWN) {
            return reconcileUnknown(proposalId, dispatch.attemptId(), intent, buyGate);
        }
        return new SubmitResult(proposalId, mapIntentStatus(dispatch.intentStatus()), null, buyGate);
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
        // Order lookup is read-only. The live allowlist gates prepare/submit/cancel, but must not
        // hide the broker's current status for an already known connected account.
        var account = connectedAccount(connectionId);
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
        return submitResult(proposalId, order, null);
    }

    private SubmitResult submitResult(String proposalId, BrokerOrder order,
                                      InvestmentBuyRiskGate.Decision buyGate) {
        return new SubmitResult(proposalId, mapBrokerStatus(order.getStatus()), order.getBrokerOrderId(), buyGate);
    }

    private SubmitResult reconcileUnknown(String proposalId, UUID attemptId, OrderIntent intent) {
        return reconcileUnknown(proposalId, attemptId, intent, null);
    }

    private SubmitResult reconcileUnknown(String proposalId, UUID attemptId, OrderIntent intent,
                                         InvestmentBuyRiskGate.Decision buyGate) {
        try {
            var account = safety.resolve(intent.getUserId(), intent.getBrokerConnectionId(), intent.getBrokerAccountId());
            reconciler.reconcile(new UnknownAttemptReconciler.Command(
                    attemptId, account, intent.getUserId(), Instant.now(), "MCP:" + intent.getUserId(),
                    "MCP submit response was not confirmed"));
            submissions.auditConnector(intent.getId(), "MCP_RECONCILIATION", "MCP:" + intent.getUserId(),
                    "{\"attemptId\":\"" + attemptId + "\"}");
            return brokerOrders.findFirstByOrderIntentIdOrderByIdAsc(intent.getId())
                    .map(order -> submitResult(proposalId, order, buyGate))
                    .orElseGet(() -> new SubmitResult(proposalId, "UNKNOWN", null, buyGate));
        } catch (RuntimeException ignored) {
            return new SubmitResult(proposalId, "UNKNOWN", null, buyGate);
        }
    }

    private UUID accountId(UUID userId, UUID connectionId) {
        var ids = jdbc.query("SELECT broker_account_id FROM real_order_account_allowlist WHERE user_id = ? AND broker_connection_id = ? AND enabled = TRUE",
                (rs, row) -> rs.getObject("broker_account_id", UUID.class), userId, connectionId);
        if (ids.size() != 1) throw blocked("live account mapping is missing or ambiguous");
        return ids.getFirst();
    }

    private BrokerAccountRef connectedAccount(UUID connectionId) {
        var response = quotes.getAccounts(new BrokerConnectionRef(connectionId));
        var accounts = response == null ? null : response.value();
        if (accounts == null || accounts.size() != 1 || accounts.getFirst() == null
                || accounts.getFirst().account() == null) {
            throw blocked("exactly one broker account is required");
        }
        return accounts.getFirst().account();
    }

    private Quote quote(UUID connectionId, String symbol) {
        var response = quotes.getQuote(new BrokerConnectionRef(connectionId), symbol);
        var quote = response == null ? null : response.value();
        if (quote == null || !symbol.equalsIgnoreCase(quote.symbol()) || quote.currency() != Currency.USD) {
            throw blocked("quote is unavailable or mismatched");
        }
        return quote;
    }

    private InvestmentBuyRiskGate.Decision requireMarketRiskForBuy(UUID userId, String symbol, OrderSide side,
                                                                   BigDecimal referencePrice,
                                                                   Instant quoteObservedAt) {
        return requireMarketRiskForBuy(userId, symbol, side, referencePrice, quoteObservedAt, null);
    }

    private InvestmentBuyRiskGate.Decision requireMarketRiskForBuy(UUID userId, String symbol, OrderSide side,
                                                                   BigDecimal referencePrice,
                                                                   Instant quoteObservedAt, UUID submitIntentId) {
        if (side != OrderSide.BUY) return null;
        if (marketRiskContext == null) throw blocked("Toss market risk assessment is unavailable");
        var decision = marketRiskContext.investmentBuyRiskGate(userId, symbol, referencePrice, quoteObservedAt);
        if (decision == null) throw blocked("Investment BUY risk assessment is unavailable");
        if (submitIntentId != null) {
            submissions.auditConnector(submitIntentId, "MCP_SUBMIT_RISK_GATE", "MCP:" + userId,
                    investmentBuyGateAuditPayload(decision, referencePrice, quoteObservedAt));
        }
        if ("BLOCKED".equals(decision.status())) {
            throw blocked("Investment BUY risk gate BLOCKED: " + String.join(",", decision.reasons()));
        }
        if (!"ELIGIBLE".equals(decision.status()) && !"REVIEW_REQUIRED".equals(decision.status())) {
            throw blocked("Investment BUY risk gate status is unavailable or unrecognized");
        }
        // REVIEW_REQUIRED proposals are inspectable; strategic submissions need the separate revalidation below.
        return decision;
    }

    private void validateStrategicReviewSubmission(UUID userId, OrderIntent intent, UUID buyDecisionId,
                                                   InvestmentBuyRiskGate.Decision gate,
                                                   BigDecimal currentMarketPrice,
                                                   com.jmj.trade.account.PortfolioReadService.PortfolioView portfolio) {
        if (buyDecisionId == null)
            throw blocked("Strategic BUY revalidation is required: proposal has no linked ADD decision");
        if (intent.getType() != OrderType.LIMIT || intent.getLimitPrice() == null)
            throw blocked("Strategic BUY revalidation requires a bounded LIMIT order");
        if (riskPreflight == null || buyRevalidation == null)
            throw blocked("Strategic BUY revalidation services are unavailable");
        var preflight = riskPreflight.preflight(userId,
                new InvestmentRiskPreflightService.PreflightInput(intent.getSymbol(), intent.getQuantity()));
        if (preflight == null || !preflight.blockers().isEmpty())
            throw blocked("Strategic BUY hard risk or account checks failed"
                    + (preflight == null ? "" : ": " + String.join(",", preflight.blockers())));
        var cash = buyingPower(portfolio);
        var requiredBuyingPower = intent.getLimitPrice().multiply(intent.getQuantity());
        if (cash == null || cash.compareTo(requiredBuyingPower) < 0)
            throw blocked("Strategic BUY account buying power is insufficient or unavailable");
        var validation = buyRevalidation.validateForSubmit(userId, buyDecisionId, intent.getSymbol(),
                intent.getQuantity(), currentMarketPrice, intent.getLimitPrice(), gate);
        if (validation == null || !validation.allowed())
            throw blocked("Strategic BUY revalidation is required: "
                    + (validation == null ? "REVALIDATION_UNAVAILABLE" : validation.reason()));
        submissions.auditConnector(intent.getId(), "MCP_SUBMIT_STRATEGIC_REVALIDATION", "MCP:" + userId,
                "{\"decisionId\":" + jsonString(buyDecisionId.toString())
                        + ",\"reviewId\":" + jsonString(validation.reviewId().toString())
                        + ",\"evidenceMode\":" + jsonString(gate.evidenceMode())
                        + ",\"outcome\":\"PASS\",\"approvalBoundary\":\"SEPARATE_ORDER_APPROVAL_REQUIRED\"}");
    }

    private static String investmentBuyGateAuditPayload(InvestmentBuyRiskGate.Decision decision,
                                                          BigDecimal marketPrice, Instant quoteObservedAt) {
        var fundamentals = decision.fundamentalVerification();
        return "{\"status\":" + jsonString(decision.status())
                + ",\"reasons\":" + jsonArray(decision.reasons())
                + ",\"investmentHorizon\":" + jsonString(decision.investmentHorizon())
                + ",\"marketDataStatus\":" + jsonString(decision.marketDataStatus())
                + ",\"marketHealth\":" + jsonString(decision.marketHealth())
                + ",\"priceRiskState\":" + jsonString(decision.priceRiskState())
                + ",\"marketPrice\":" + (marketPrice == null ? "null" : marketPrice.toPlainString())
                + ",\"quoteObservedAt\":" + jsonString(quoteObservedAt == null ? null : quoteObservedAt.toString())
                + ",\"evaluatedAt\":" + jsonString(decision.evaluatedAt() == null ? null : decision.evaluatedAt().toString())
                + ",\"evidenceMode\":" + jsonString(decision.evidenceMode())
                + ",\"approvalBoundary\":\"ORDER_APPROVAL_ONLY\""
                + ",\"fundamentalVerification\":" + (fundamentals == null ? "null"
                : "{\"status\":" + jsonString(fundamentals.status())
                + ",\"reasons\":" + jsonArray(fundamentals.reasons())
                + ",\"fundamentalsStatus\":" + jsonString(fundamentals.fundamentalsStatus())
                + ",\"source\":" + jsonString(fundamentals.source())
                + ",\"asOf\":" + jsonString(fundamentals.asOf() == null ? null : fundamentals.asOf().toString())
                + ",\"revisionStatus\":" + jsonString(fundamentals.revisionStatus())
                + ",\"consensusSource\":" + jsonString(fundamentals.consensusSource())
                + ",\"consensusAsOf\":" + jsonString(fundamentals.consensusAsOf() == null
                        ? null : fundamentals.consensusAsOf().toString()) + "}") + "}";
    }

    private static String jsonArray(java.util.List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        return values.stream().map(McpOrderExecutionService::jsonString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        var result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append("\\u%04x".formatted((int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }

    private static BigDecimal referencePrice(Quote quote, OrderSide side) {
        var value = side == OrderSide.BUY ? quote.askPrice() : quote.bidPrice();
        if (value == null || value.signum() <= 0) value = quote.lastPrice();
        if (value == null || value.signum() <= 0) throw blocked("quote price is unavailable");
        return value;
    }

    private static BigDecimal marketRiskPrice(Quote quote, BigDecimal referencePrice) {
        return quote != null && quote.lastPrice() != null && quote.lastPrice().signum() > 0
                ? quote.lastPrice() : referencePrice;
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
    private record DefensiveLink(UUID decisionId, UUID eventId, UUID buyDecisionId) {}
    public record PrepareResult(String proposalId, String status, Order order, BigDecimal estimatedAmount,
                                PreTrade preTrade, Instant expiresAt, UUID decisionId, UUID eventId,
                                com.jmj.trade.investment.InvestmentSellReadinessService.Readiness readiness,
                                InvestmentBuyRiskGate.Decision investmentBuyGate) {
        public PrepareResult(String proposalId, String status, Order order, BigDecimal estimatedAmount,
                            PreTrade preTrade, Instant expiresAt) {
            this(proposalId, status, order, estimatedAmount, preTrade, expiresAt, null, null, null, null);
        }

        public PrepareResult(String proposalId, String status, Order order, BigDecimal estimatedAmount,
                             PreTrade preTrade, Instant expiresAt, UUID decisionId, UUID eventId,
                             com.jmj.trade.investment.InvestmentSellReadinessService.Readiness readiness) {
            this(proposalId, status, order, estimatedAmount, preTrade, expiresAt, decisionId, eventId, readiness, null);
        }
    }
    public record SubmitResult(String proposalId, String status, String brokerOrderId,
                               InvestmentBuyRiskGate.Decision investmentBuyGate) {
        public SubmitResult(String proposalId, String status, String brokerOrderId) {
            this(proposalId, status, brokerOrderId, null);
        }
    }
    public record CancelResult(String brokerOrderId, String status) {}
    public record OrderResult(String brokerOrderId, String status) {}
    public record Order(String symbol, String side, String orderType, BigDecimal quantity, BigDecimal price) {}
    public record PreTrade(BigDecimal buyingPower, BigDecimal currentQuantity, BigDecimal projectedQuantity, String riskGate) {}
}
