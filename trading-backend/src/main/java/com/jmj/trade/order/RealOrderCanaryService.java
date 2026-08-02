package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderView;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One-shot operator canary orchestration. It never retries a broker mutation. */
public final class RealOrderCanaryService {

    private final ObjectProvider<BrokerAdapter> quotes;
    private final ObjectProvider<TossCredentialProvider> credentials;
    private final ObjectProvider<LiveOrderActivationService> activation;
    private final ObjectProvider<LiveOrderSafetyLedger> safety;
    private final ObjectProvider<KillSwitchStateReader> killSwitches;
    private final ObjectProvider<BrokerOrderPort> orders;
    private final ObjectProvider<OrderApprovalStepUpService> stepUps;
    private final ObjectProvider<OrderSubmissionService> submissions;
    private final ObjectProvider<BrokerOrderRepository> brokerOrderRepository;
    private final ObjectProvider<UnknownAttemptReconciler> reconciler;
    private final JdbcTemplate jdbc;
    private final RealOrderCanaryAuditLedger audit;
    private final RealOrderCanaryProperties properties;
    private final Clock clock;

    public RealOrderCanaryService(
            ObjectProvider<BrokerAdapter> quotes,
            ObjectProvider<TossCredentialProvider> credentials,
            ObjectProvider<LiveOrderActivationService> activation,
            ObjectProvider<LiveOrderSafetyLedger> safety,
            ObjectProvider<KillSwitchStateReader> killSwitches,
            ObjectProvider<BrokerOrderPort> orders,
            ObjectProvider<OrderApprovalStepUpService> stepUps,
            ObjectProvider<OrderSubmissionService> submissions,
            ObjectProvider<BrokerOrderRepository> brokerOrderRepository,
            ObjectProvider<UnknownAttemptReconciler> reconciler,
            JdbcTemplate jdbc,
            RealOrderCanaryAuditLedger audit,
            RealOrderCanaryProperties properties,
            Clock clock
    ) {
        this.quotes = Objects.requireNonNull(quotes, "quotes");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.safety = Objects.requireNonNull(safety, "safety");
        this.killSwitches = Objects.requireNonNull(killSwitches, "killSwitches");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.stepUps = Objects.requireNonNull(stepUps, "stepUps");
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.brokerOrderRepository = Objects.requireNonNull(brokerOrderRepository, "brokerOrderRepository");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PreflightResult preflight(UUID userId, CanaryOrder order, Instant authTime) {
        return preflightInternal(userId, order, authTime).publicResult();
    }

    public RunResult run(UUID userId, CanaryOrder order, Instant authTime, String actor, String runKey) {
        requireId(userId, "userId");
        requireText(actor, "actor");
        requireText(runKey, "runKey");

        // An invalid global canary configuration must stay a broker-free preflight path.
        if (!properties.validationErrors().isEmpty()) {
            var runId = UUID.randomUUID();
            var eventNumber = new EventNumber();
            var initial = preflightInternal(userId, order, authTime);
            auditPreflight(runId, eventNumber, userId, initial);
            return blocked(runId, null, null, initial.blockers());
        }

        var claim = claimRun(userId, order, runKey);
        if (!claim.owner()) {
            return claim.result();
        }
        var runId = claim.runId();
        var eventNumber = new EventNumber();
        UUID intentId = null;
        UUID attemptId = null;
        var orderSubmitted = false;
        var brokerWriteStarted = false;

        var live = activation.getIfAvailable();
        var ledger = safety.getIfAvailable();
        try {
            var initial = preflightInternal(userId, order, authTime);
            auditPreflight(runId, eventNumber, userId, initial);
            if (!initial.ready()) {
                return complete(blocked(runId, null, null, initial.blockers()));
            }

            ledger.revalidate(userId, properties.connectionId(), properties.brokerAccountId(),
                    null, order.currency(), initial.orderAmount(), clock.instant());
            intentId = live.propose(userId, new LiveOrderActivationService.Proposal(
                    properties.connectionId(), properties.brokerAccountId(), order.side(), order.type(),
                    order.symbol(), order.quantity(), order.limitPrice(), order.currency()));
            audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                    intentId, null, "PROPOSED", "READY", null, null, false, false, false, null, null, null, clock.instant());

            gate(userId, intentId, order, authTime);
            var approvalToken = issueStepUp(live, userId, intentId, authTime);
            live.approve(userId, intentId, approvalToken, actor, order.quantity(),
                    initial.quotePrice().multiply(order.quantity()), order.currency(), properties.quoteMaxAge());
            audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                    intentId, null, "APPROVED", "READY", null, null, false, false, false, null, null, null, clock.instant());

            var dispatchGate = gate(userId, intentId, order, authTime);
            var clientOrderId = clientOrderId();
            var dispatchToken = issueStepUp(live, userId, intentId, authTime);
            brokerWriteStarted = true;
            var dispatch = live.dispatch(userId, intentId, clientOrderId, dispatchToken, actor,
                    properties.quoteMaxAge());
            attemptId = dispatch.attemptId();
            orderSubmitted = dispatch.attemptStatus() == SubmissionAttemptStatus.ACKNOWLEDGED
                    || dispatch.attemptStatus() == SubmissionAttemptStatus.UNKNOWN;
            audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                    intentId, dispatch.attemptId(), "SUBMITTED", submissionOutcome(dispatch.attemptStatus()),
                    dispatch.attemptStatus().name(), null, false, false,
                    dispatch.attemptStatus() == SubmissionAttemptStatus.UNKNOWN,
                    clientOrderId, null, null, clock.instant());
            if (dispatch.intentStatus() == OrderIntentStatus.MANUAL_REVIEW_REQUIRED) {
                return complete(manual(runId, intentId, dispatch.attemptId(), true,
                        "CANARY_CLIENT_ORDER_ID_MAPPING_MISMATCH"));
            }
            if (dispatch.attemptStatus() == SubmissionAttemptStatus.UNKNOWN) {
                return complete(reconcileUnknown(runId, eventNumber, userId, intentId, dispatch,
                        dispatchGate.account(), clientOrderId, actor));
            }
            if (dispatch.attemptStatus() != SubmissionAttemptStatus.ACKNOWLEDGED
                    || dispatch.brokerOrderId() == null) {
                var rejected = dispatch.attemptStatus() == SubmissionAttemptStatus.BROKER_REJECTED;
                return complete(new RunResult(runId, rejected ? "REJECTED" : "MANUAL_REVIEW_REQUIRED",
                        orderSubmitted, !rejected, intentId, dispatch.attemptId(),
                        List.of("CANARY_DISPATCH_NOT_ACCEPTED")));
            }
            var persistedBrokerOrder = brokerOrderRepository.getIfAvailable().findById(dispatch.brokerOrderId())
                    .orElseThrow(() -> new CanaryBlockedException("CANARY_BROKER_ORDER_PROJECTION_MISSING"));
            var brokerOrderId = persistedBrokerOrder.getBrokerOrderId();

            var firstGate = gate(userId, intentId, order, authTime);
            consumeReadStepUp(userId, intentId, authTime, live);
            var first = observe(firstGate.account(), brokerOrderId);
            audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                    intentId, dispatch.attemptId(), "OPEN_CLOSED_OBSERVED", first.outcome(), null,
                    first.lifecycleName(), first.openComplete(), first.closedComplete(), first.unknown(),
                    clientOrderId, brokerOrderId, first.reasonCode(), clock.instant());
            if (!first.resolved()) {
                audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                        intentId, dispatch.attemptId(), "FINAL_RECONCILIATION", "MANUAL_REVIEW_REQUIRED", null,
                        first.lifecycleName(), first.openComplete(), first.closedComplete(), true,
                        clientOrderId, brokerOrderId, "CANARY_LOOKUP_UNKNOWN", clock.instant());
                return complete(manual(runId, intentId, dispatch.attemptId(), true, "CANARY_LOOKUP_UNKNOWN"));
            }

            var cancelNeedsManual = false;
            var cancelUnknown = false;
            var open = first.openOrder(brokerOrderId);
            if (open != null && cancellable(open)) {
                gate(userId, intentId, order, authTime);
                var cancelToken = issueStepUp(live, userId, intentId, authTime);
                var cancel = live.cancel(userId, intentId, cancelToken, actor);
                audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                        intentId, dispatch.attemptId(), "CANCEL_REQUESTED", cancel.status().name(),
                        cancel.status().name(), open.status().name(), true, true,
                        cancel.status() == com.jmj.trade.broker.BrokerOrderDispatchStatus.UNKNOWN,
                        clientOrderId, brokerOrderId, null, clock.instant());
                cancelNeedsManual = cancel.status() != com.jmj.trade.broker.BrokerOrderDispatchStatus.ACCEPTED;
                cancelUnknown = cancel.status() == com.jmj.trade.broker.BrokerOrderDispatchStatus.UNKNOWN;
            }

            var finalGate = gate(userId, intentId, order, authTime);
            consumeReadStepUp(userId, intentId, authTime, live);
            var finalObservation = observe(finalGate.account(), brokerOrderId);
            var finalView = finalObservation.match(brokerOrderId);
            var finalOutcome = finalizeBrokerProjection(
                    userId, intentId, dispatch.attemptId(), brokerOrderId, finalObservation, finalView, actor);
            if (cancelNeedsManual) {
                finalOutcome = "MANUAL_REVIEW_REQUIRED";
            }
            audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                    intentId, dispatch.attemptId(), "FINAL_RECONCILIATION", finalOutcome,
                    null, finalObservation.lifecycleName(), finalObservation.openComplete(),
                    finalObservation.closedComplete(), finalObservation.unknown(), clientOrderId,
                    brokerOrderId, finalObservation.reasonCode(), clock.instant());
            return complete(new RunResult(runId, finalOutcome, true,
                    finalObservation.unknown() || cancelUnknown, intentId, dispatch.attemptId(), List.of()));
        } catch (CanaryBlockedException exception) {
            audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                    intentId, attemptId, "BLOCKED", "BLOCKED", null, null, false, false, false, null, null,
                    exception.code, clock.instant());
            var submitted = orderSubmitted || brokerWriteStarted;
            return complete(new RunResult(runId, submitted ? "MANUAL_REVIEW_REQUIRED" : "PREFLIGHT_ONLY",
                    submitted, submitted, intentId, attemptId, List.of(exception.code)));
        } catch (RuntimeException exception) {
            audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                    intentId, attemptId, "BLOCKED", "MANUAL_REVIEW_REQUIRED", null, null, false, false, true,
                    null, null, "CANARY_EXECUTION_UNKNOWN", clock.instant());
            var submitted = orderSubmitted || brokerWriteStarted;
            return complete(new RunResult(runId, "MANUAL_REVIEW_REQUIRED", submitted, true,
                    intentId, attemptId, List.of("CANARY_EXECUTION_UNKNOWN")));
        }
    }

    private RunClaim claimRun(UUID userId, CanaryOrder order, String runKey) {
        var runId = UUID.randomUUID();
        var keyHash = RealOrderCanaryAuditLedger.hash(runKey);
        var fingerprint = requestFingerprint(order);
        var inserted = jdbc.update("""
                INSERT INTO real_order_canary_runs (
                    run_id, user_id, broker_connection_id, broker_account_id,
                    request_key_hash, request_fingerprint_hash, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, runId, userId, properties.connectionId(), properties.brokerAccountId(),
                keyHash, fingerprint, at(clock.instant()));
        if (inserted == 1) {
            return new RunClaim(runId, true, null);
        }
        var state = jdbc.query("""
                SELECT run_id, request_key_hash, request_fingerprint_hash, outcome, order_submitted, unknown,
                       order_intent_id, submission_attempt_id, result_blockers
                  FROM real_order_canary_runs
                 WHERE user_id = ? AND broker_connection_id = ? AND broker_account_id = ?
                   AND (request_key_hash = ? OR outcome = 'RUNNING')
                 ORDER BY CASE WHEN request_key_hash = ? THEN 0 ELSE 1 END, started_at
                 LIMIT 1
                """, (rs, row) -> new RunState(
                rs.getObject("run_id", UUID.class),
                rs.getString("request_key_hash"),
                rs.getString("request_fingerprint_hash"),
                rs.getString("outcome"),
                rs.getBoolean("order_submitted"),
                rs.getBoolean("unknown"),
                rs.getObject("order_intent_id", UUID.class),
                rs.getObject("submission_attempt_id", UUID.class),
                rs.getString("result_blockers")),
                userId, properties.connectionId(), properties.brokerAccountId(), keyHash, keyHash)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("canary run claim lost"));
        if ("RUNNING".equals(state.outcome())) {
            return new RunClaim(state.runId(), false, new RunResult(state.runId(), "PREFLIGHT_ONLY",
                    false, false, state.orderIntentId(), state.submissionAttemptId(),
                    List.of("CANARY_RUN_IN_PROGRESS")));
        }
        if (!keyHash.equals(state.requestKeyHash()) || !fingerprint.equals(state.requestFingerprintHash())) {
            return new RunClaim(state.runId(), false, new RunResult(state.runId(), "PREFLIGHT_ONLY",
                    false, false, null, null, List.of("CANARY_RUN_KEY_REUSED_WITH_DIFFERENT_ORDER")));
        }
        return new RunClaim(state.runId(), false, state.result());
    }

    private RunResult complete(RunResult result) {
        jdbc.update("""
                UPDATE real_order_canary_runs
                   SET outcome = ?, order_submitted = ?, unknown = ?, order_intent_id = ?,
                       submission_attempt_id = ?, result_blockers = ?, finished_at = ?
                 WHERE run_id = ? AND outcome = 'RUNNING'
                """, result.outcome(), result.orderSubmitted(), result.unknown(), result.orderIntentId(),
                result.attemptId(), String.join(",", result.blockers()), at(clock.instant()), result.runId());
        return result;
    }

    private static String requestFingerprint(CanaryOrder order) {
        if (order == null) {
            return RealOrderCanaryAuditLedger.hash("null");
        }
        return RealOrderCanaryAuditLedger.hash(String.join("|",
                field(order.side()), field(order.type()), field(order.symbol()), field(order.quantity()),
                field(order.limitPrice()), field(order.currency())));
    }

    private static String field(Object value) {
        var text = String.valueOf(value);
        return text.length() + ":" + text;
    }

    private Preflight preflightInternal(UUID userId, CanaryOrder order, Instant authTime) {
        var blockers = new ArrayList<String>(properties.validationErrors());
        var checks = new ArrayList<Check>();
        if (!blockers.isEmpty()) {
            return new Preflight(false, checks, blockers, null, null, null);
        }
        if (order == null) {
            return failed("CANARY_ORDER_INVALID", checks);
        }
        try {
            OrderIntent.proposed(UUID.randomUUID(), properties.brokerAccountId(), order.side(), order.type(),
                    order.symbol(), order.quantity(), order.limitPrice(), order.currency());
        } catch (RuntimeException exception) {
            return failed("CANARY_ORDER_INVALID", checks);
        }
        var connection = jdbc.query("""
                SELECT status, credential_ciphertext, credential_nonce, credential_key_version
                  FROM broker_connections
                 WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """, (rs, row) -> new ConnectionRow(
                rs.getString("status"),
                rs.getBytes("credential_ciphertext") != null,
                rs.getBytes("credential_nonce") != null,
                rs.getObject("credential_key_version") != null),
                properties.connectionId(), userId).stream().findFirst();
        if (connection.isEmpty() || !"ACTIVE".equals(connection.get().status())) {
            blockers.add("CANARY_CONNECTION_NOT_ACTIVE");
        }
        var credentialsReady = connection.isPresent() && connection.get().credentialsPresent()
                && credentials.getIfAvailable() != null;
        if (credentialsReady) {
            try {
                credentials.getIfAvailable().current(properties.connectionId());
            } catch (RuntimeException exception) {
                credentialsReady = false;
            }
        }
        if (!credentialsReady) {
            blockers.add("CANARY_CREDENTIALS_UNAVAILABLE");
        }
        var allowlisted = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM real_order_account_allowlist
                     WHERE user_id = ? AND broker_connection_id = ?
                       AND broker_account_id = ? AND enabled = TRUE
                )
                """, Boolean.class, userId, properties.connectionId(), properties.brokerAccountId()));
        if (!allowlisted) {
            blockers.add("CANARY_ALLOWLIST_MISSING");
        }
        if (activation.getIfAvailable() == null || safety.getIfAvailable() == null
                || killSwitches.getIfAvailable() == null || orders.getIfAvailable() == null
                || stepUps.getIfAvailable() == null || submissions.getIfAvailable() == null
                || brokerOrderRepository.getIfAvailable() == null || reconciler.getIfAvailable() == null) {
            blockers.add("CANARY_LIVE_DEPENDENCIES_UNAVAILABLE");
        }
        var killSwitchReady = false;
        if (killSwitches.getIfAvailable() != null && !blockers.contains("CANARY_CONNECTION_NOT_ACTIVE")
                && credentialsReady) {
            try {
                killSwitchReady = !killSwitches.getIfAvailable().anyEngaged(userId, properties.connectionId());
            } catch (RuntimeException exception) {
                blockers.add("CANARY_KILL_SWITCH_UNAVAILABLE");
            }
            if (!killSwitchReady && !blockers.contains("CANARY_KILL_SWITCH_UNAVAILABLE")) {
                blockers.add("CANARY_KILL_SWITCH_ENGAGED");
            }
        }
        if (authTime == null) {
            blockers.add("CANARY_STEP_UP_AUTH_TIME_MISSING");
        } else if (stepUps.getIfAvailable() != null && !stepUps.getIfAvailable().accepts(authTime)) {
            blockers.add("CANARY_STEP_UP_REAUTH_REQUIRED");
        }
        if (!blockers.isEmpty()) {
            return new Preflight(false, checks, List.copyOf(blockers), null, null, null);
        }
        var quote = quote(order);
        if (quote == null) {
            blockers.add("CANARY_QUOTE_UNAVAILABLE");
        } else if (!properties.connectionId().equals(quote.quote().connection().brokerConnectionId())
                || !order.symbol().equals(quote.quote().symbol())
                || order.currency() != quote.quote().currency()) {
            blockers.add("CANARY_QUOTE_MISMATCH");
        } else if (stale(quote.quote().observedAt(), properties.quoteMaxAge())) {
            blockers.add("CANARY_QUOTE_STALE");
        }
        BigDecimal price = quote == null ? null : price(order, quote.quote());
        BigDecimal amount = price == null ? null : price.multiply(order.quantity());
        if (order.quantity() == null || properties.maxQuantity() == null
                || order.quantity().compareTo(properties.maxQuantity()) > 0) {
            blockers.add("CANARY_QUANTITY_LIMIT_EXCEEDED");
        }
        if (amount == null || properties.maxOrderAmount(order.currency()) == null
                || amount.compareTo(properties.maxOrderAmount(order.currency())) > 0) {
            blockers.add("CANARY_ORDER_LIMIT_EXCEEDED");
        }
        return new Preflight(blockers.isEmpty(), checks, List.copyOf(blockers), quote, price, amount);
    }

    private Gate gate(UUID userId, UUID intentId, CanaryOrder order, Instant authTime) {
        var current = preflightInternal(userId, order, authTime);
        if (!current.ready()) {
            throw new CanaryBlockedException(current.blockers().getFirst());
        }
        var account = safety.getIfAvailable().revalidate(
                userId, properties.connectionId(), properties.brokerAccountId(), intentId,
                order.currency(), current.orderAmount(), clock.instant());
        return new Gate(current, account);
    }

    private RunResult reconcileUnknown(
            UUID runId,
            EventNumber eventNumber,
            UUID userId,
            UUID intentId,
            LiveOrderActivationService.DispatchResult dispatch,
            BrokerAccountRef account,
            String clientOrderId,
            String actor
    ) {
        var outcome = reconciler.getIfAvailable().reconcile(new UnknownAttemptReconciler.Command(
                dispatch.attemptId(), account, userId, clock.instant(), actor, "REAL_ORDER_CANARY_UNKNOWN"));
        var reason = outcome.decision() == ReconciliationDecision.MANUAL_REVIEW_REQUIRED
                ? "CANARY_UNKNOWN_MANUAL_REVIEW" : "CANARY_UNKNOWN_NO_RETRY";
        audit.record(runId, eventNumber.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                intentId, dispatch.attemptId(), "FINAL_RECONCILIATION", "MANUAL_REVIEW_REQUIRED",
                null, null, false, false, true, clientOrderId, null, reason, clock.instant());
        return new RunResult(runId, "MANUAL_REVIEW_REQUIRED", true, true, intentId,
                dispatch.attemptId(), List.of(reason));
    }

    private String finalizeBrokerProjection(
            UUID userId,
            UUID intentId,
            UUID attemptId,
            String brokerOrderId,
            Observation observation,
            BrokerOrderView view,
            String actor
    ) {
        if (!observation.resolved() || view == null) {
            return "MANUAL_REVIEW_REQUIRED";
        }
        var brokerOrder = brokerOrderRepository.getIfAvailable()
                .findByBrokerAccountIdAndBrokerOrderId(properties.brokerAccountId(), brokerOrderId)
                .orElse(null);
        if (brokerOrder == null) {
            return "MANUAL_REVIEW_REQUIRED";
        }
        submissions.getIfAvailable().recordBrokerOrderUpdate(
                brokerOrder.getId(), BrokerOrderStatus.valueOf(view.status().name()), view.filledQuantity(),
                OrderSubmissionService.Execution.unknown(), clock.instant(), actor);
        return switch (view.status()) {
            case FILLED, CANCELED -> "FINAL_RECONCILED";
            case REJECTED -> "REJECTED";
            default -> "MANUAL_REVIEW_REQUIRED";
        };
    }

    private void consumeReadStepUp(UUID userId, UUID intentId, Instant authTime,
                                   LiveOrderActivationService live) {
        var token = issueStepUp(live, userId, intentId, authTime);
        stepUps.getIfAvailable().consume(userId, intentId, token);
    }

    private String issueStepUp(LiveOrderActivationService live, UUID userId, UUID intentId, Instant authTime) {
        return live.issueStepUp(userId, intentId, authTime).stepUpToken();
    }

    private Observation observe(BrokerAccountRef account, String brokerOrderId) {
        var open = read(account, BrokerOrderGroup.OPEN, brokerOrderId);
        var closed = read(account, BrokerOrderGroup.CLOSED, brokerOrderId);
        return new Observation(open.views(), closed.views(), open.complete(), closed.complete(),
                open.reason() != null || closed.reason() != null,
                open.reason() != null ? open.reason() : closed.reason());
    }

    private GroupRead read(BrokerAccountRef account, BrokerOrderGroup group, String brokerOrderId) {
        try {
            var response = orders.getIfAvailable().getOrders(account, group);
            var views = response == null || response.value() == null ? List.<BrokerOrderView>of() : response.value();
            return new GroupRead(views.stream().filter(view -> brokerOrderId.equals(view.brokerOrderId())).toList(),
                    true, null);
        } catch (RuntimeException exception) {
            return new GroupRead(List.of(), false, "CANARY_" + group.name() + "_LOOKUP_UNKNOWN");
        }
    }

    private QuoteResponse quote(CanaryOrder order) {
        try {
            var quoteSource = quotes.getIfAvailable();
            if (quoteSource == null) {
                return null;
            }
            var response = quoteSource.getQuote(new BrokerConnectionRef(properties.connectionId()), order.symbol());
            return response == null || response.value() == null ? null : new QuoteResponse(response.value());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static BigDecimal price(CanaryOrder order, Quote quote) {
        var value = order.side() == OrderSide.BUY ? quote.askPrice() : quote.bidPrice();
        return value == null ? quote.lastPrice() : value;
    }

    private boolean stale(Instant observedAt, Duration maxAge) {
        var now = clock.instant();
        return observedAt == null || maxAge == null || observedAt.isAfter(now.plusSeconds(60))
                || Duration.between(observedAt, now).compareTo(maxAge) > 0;
    }

    private static boolean cancellable(BrokerOrderView view) {
        return (view.status() == BrokerOrderLifecycle.PENDING
                || view.status() == BrokerOrderLifecycle.PARTIALLY_FILLED)
                && view.filledQuantity().compareTo(view.quantity()) < 0;
    }

    private void auditPreflight(UUID runId, EventNumber number, UUID userId, Preflight preflight) {
        audit.record(runId, number.next(), userId, properties.connectionId(), properties.brokerAccountId(),
                null, null, "PREFLIGHT", preflight.ready() ? "READY" : "PREFLIGHT_ONLY", null, null,
                false, false, false, null, null,
                preflight.blockers().isEmpty() ? null : preflight.blockers().getFirst(), clock.instant());
    }

    private RunResult blocked(UUID runId, UUID intentId, UUID attemptId, List<String> blockers) {
        return new RunResult(runId, "PREFLIGHT_ONLY", false, false, intentId, attemptId, List.copyOf(blockers));
    }

    private RunResult manual(UUID runId, UUID intentId, UUID attemptId, boolean unknown, String reason) {
        return new RunResult(runId, "MANUAL_REVIEW_REQUIRED", true, unknown, intentId, attemptId, List.of(reason));
    }

    private String clientOrderId() {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var maxSuffix = 36 - properties.clientOrderIdPrefix().length() - 1;
        return properties.clientOrderIdPrefix() + "-" + suffix.substring(0, Math.min(maxSuffix, suffix.length()));
    }

    private static Preflight failed(String blocker, List<Check> checks) {
        return new Preflight(false, checks, List.of(blocker), null, null, null);
    }

    private static String submissionOutcome(SubmissionAttemptStatus status) {
        return switch (status) {
            case ACKNOWLEDGED -> "ACCEPTED";
            case BROKER_REJECTED -> "REJECTED";
            case UNKNOWN -> "UNKNOWN";
            default -> status.name();
        };
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }

    private record ConnectionRow(String status, boolean ciphertext, boolean nonce, boolean keyVersion) {
        boolean credentialsPresent() {
            return ciphertext && nonce && keyVersion;
        }
    }

    private record RunClaim(UUID runId, boolean owner, RunResult result) {
    }

    private record RunState(
            UUID runId,
            String requestKeyHash,
            String requestFingerprintHash,
            String outcome,
            boolean orderSubmitted,
            boolean unknown,
            UUID orderIntentId,
            UUID submissionAttemptId,
            String resultBlockers
    ) {
        RunResult result() {
            var blockers = resultBlockers == null || resultBlockers.isBlank()
                    ? List.<String>of() : List.of(resultBlockers.split(","));
            return new RunResult(runId, outcome, orderSubmitted, unknown,
                    orderIntentId, submissionAttemptId, blockers);
        }
    }

    private record QuoteResponse(Quote quote) {
    }

    private record GroupRead(List<BrokerOrderView> views, boolean complete, String reason) {
    }

    private record Gate(Preflight preflight, BrokerAccountRef account) {
    }

    private static final class EventNumber {
        private int value;

        int next() {
            return ++value;
        }
    }

    private record Observation(
            List<BrokerOrderView> open,
            List<BrokerOrderView> closed,
            boolean openComplete,
            boolean closedComplete,
            boolean unknown,
            String reasonCode
    ) {
        boolean resolved() {
            return openComplete && closedComplete && !unknown;
        }

        BrokerOrderView openOrder(String id) {
            return open.stream().filter(view -> id.equals(view.brokerOrderId())).findFirst().orElse(null);
        }

        BrokerOrderView match(String id) {
            var closedMatch = closed.stream().filter(view -> id.equals(view.brokerOrderId())).findFirst();
            return closedMatch.orElseGet(() -> open.stream().filter(view -> id.equals(view.brokerOrderId()))
                    .findFirst().orElse(null));
        }

        String outcome() {
            if (unknown) {
                return "UNKNOWN";
            }
            var view = closed.isEmpty() ? open.stream().findFirst().orElse(null) : closed.getFirst();
            return view == null ? "MANUAL_REVIEW_REQUIRED" : view.status().name();
        }

        String lifecycleName() {
            var view = closed.isEmpty() ? open.stream().findFirst().orElse(null) : closed.getFirst();
            return view == null ? null : view.status().name();
        }
    }

    private static void requireId(UUID value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public record CanaryOrder(
            OrderSide side,
            OrderType type,
            String symbol,
            BigDecimal quantity,
            BigDecimal limitPrice,
            Currency currency
    ) {
    }

    public record Check(String code, boolean passed) {
    }

    public record PreflightResult(boolean ready, List<Check> checks, List<String> blockers) {
    }

    public record RunResult(
            UUID runId,
            String outcome,
            boolean orderSubmitted,
            boolean unknown,
            UUID orderIntentId,
            UUID attemptId,
            List<String> blockers
    ) {
    }

    private record Preflight(
            boolean ready,
            List<Check> checks,
            List<String> blockers,
            QuoteResponse quote,
            BigDecimal quotePrice,
            BigDecimal orderAmount
    ) {
        PreflightResult publicResult() {
            return new PreflightResult(ready, checks, blockers);
        }
    }

    private static final class CanaryBlockedException extends RuntimeException {
        private final String code;

        private CanaryBlockedException(String code) {
            this.code = code;
        }
    }
}
