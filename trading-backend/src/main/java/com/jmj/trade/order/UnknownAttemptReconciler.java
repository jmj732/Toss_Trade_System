package com.jmj.trade.order;

import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.notification.NotificationEventType;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * UNKNOWN 제출 시도 조정 오케스트레이터 (플랜 원장 E5; SPEC:1055/1099-1100/1121).
 *
 * <p>운영자의 명시 행위로만 실행된다. 절차는 한 트랜잭션에서:
 * <ol>
 *   <li>조정 진입을 감사 기록한다.</li>
 *   <li>브로커 OPEN·CLOSED 그룹을 각각 조회해({@link ReconciliationBrokerProbe}) 타입 결과를 얻는다.
 *       CLOSED 결과가 반드시 있어야 {@link ReconciliationEvidence} 를 만들 수 있어, OPEN 만으로는
 *       재시도 판정에 도달할 수 없다(SPEC:1055 급소).</li>
 *   <li>도메인 {@link OrderSubmissionService#recordReconciliation} 에 판정을 위임한다(멱등·같은 tx).</li>
 *   <li>판정이 {@link ReconciliationDecision#MANUAL_REVIEW_REQUIRED} 이면 <b>E4 의 ACCOUNT 범위
 *       kill switch 를 engage</b> 해 해당 계좌 신규 주문을 잠그고, 기존 notification outbox 로 운영
 *       알림을 발행한다. 새 잠금·알림 인프라를 만들지 않는다.</li>
 * </ol>
 *
 * <p>해제 경로는 여기에 없다. 잠금 해제는 오직 운영자가 E4 kill switch 를 disengage(step-up 필요)
 * 할 때만 일어난다 — 시간 경과·재시도·스케줄러 자동 해제는 존재하지 않는다.
 *
 * <p>조정은 읽기 전용 브로커 조회만 하며 주문을 전송하지 않으므로 반복 조정이 브로커 주문을 늘리지
 * 않는다. 또한 첫 조정으로 attempt 가 종결되면 이후 조정은 UNKNOWN 아님으로 거부된다.
 */
public class UnknownAttemptReconciler {

    /** MANUAL_REVIEW_REQUIRED 진입 시 계좌 잠금 사유. */
    private static final String ACCOUNT_LOCK_REASON =
            "UNKNOWN submission attempt requires manual review; new orders locked pending operator resolution";

    private final SubmissionAttemptRepository attemptRepository;
    private final ReconciliationCheckRepository reconciliationCheckRepository;
    private final OrderSubmissionService submissionService;
    private final ReconciliationBrokerProbe brokerProbe;
    private final KillSwitchLedger killSwitchLedger;
    private final NotificationOutboxWriter notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UnknownAttemptReconciler(
            SubmissionAttemptRepository attemptRepository,
            ReconciliationCheckRepository reconciliationCheckRepository,
            OrderSubmissionService submissionService,
            ReconciliationBrokerProbe brokerProbe,
            KillSwitchLedger killSwitchLedger,
            NotificationOutboxWriter notifications,
            JdbcTemplate jdbc,
            Clock clock
    ) {
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.reconciliationCheckRepository =
                Objects.requireNonNull(reconciliationCheckRepository, "reconciliationCheckRepository");
        this.submissionService = Objects.requireNonNull(submissionService, "submissionService");
        this.brokerProbe = Objects.requireNonNull(brokerProbe, "brokerProbe");
        this.killSwitchLedger = Objects.requireNonNull(killSwitchLedger, "killSwitchLedger");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public Outcome reconcile(Command command) {
        Objects.requireNonNull(command, "command");
        var attempt = attemptRepository.loadForReconciliation(command.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "submission attempt not found: " + command.attemptId()));
        if (attempt.getStatus() != SubmissionAttemptStatus.UNKNOWN) {
            // 이미 조정돼 종결된(또는 UNKNOWN 이 아닌) attempt 는 재조정하지 않는다 — 멱등 보장.
            throw new IllegalStateException("reconciliation requires UNKNOWN attempt");
        }
        var checkedAt = command.checkedAt();
        recordAction(attempt, "RECONCILIATION_ENTERED", null, null, null,
                command.actor(), command.reason(), checkedAt);

        var persistedAccount = persistedAccount(attempt.getId());
        if (persistedAccount.live() && !persistedAccount.matches(command)) {
            submissionService.markManualReview(attempt.getId(), checkedAt,
                    new DispatchEvidence(attempt.getClientOrderId(), "reconciliation-account-mismatch"),
                    command.actor());
            killSwitchLedger.engage(
                    persistedAccount.userId(),
                    KillSwitchLedger.Scope.ACCOUNT,
                    persistedAccount.connectionId(),
                    ACCOUNT_LOCK_REASON,
                    command.actor());
            recordAction(attempt, "ACCOUNT_MAPPING_MISMATCH", ReconciliationDecision.MANUAL_REVIEW_REQUIRED,
                    "UNAVAILABLE", "UNAVAILABLE", command.actor(),
                    "reconciliation account does not match persisted live account", checkedAt);
            notifications.emit(
                    persistedAccount.userId(),
                    NotificationEventType.ORDER_RECONCILIATION_MANUAL_REVIEW,
                    attempt.getId(),
                    manualReviewPayload(attempt, persistedAccount.connectionId(),
                            ReconciliationDecision.MANUAL_REVIEW_REQUIRED, "UNAVAILABLE", "UNAVAILABLE"),
                    checkedAt);
            return new Outcome(attempt.getId(), attempt.getOrderIntentId(),
                    ReconciliationDecision.MANUAL_REVIEW_REQUIRED, true);
        }

        var open = brokerProbe.probe(command.account(), BrokerOrderGroup.OPEN, attempt.getClientOrderId());
        var closed = brokerProbe.probe(command.account(), BrokerOrderGroup.CLOSED, attempt.getClientOrderId());
        var evidence = ReconciliationEvidence.of(open, closed);

        submissionService.recordReconciliation(
                attempt.getId(), evidence.toResult(attempt.getClientOrderId(), checkedAt), command.actor());

        var decision = reconciliationCheckRepository
                .findTopBySubmissionAttemptIdOrderByCheckNumberDesc(attempt.getId())
                .orElseThrow(() -> new IllegalStateException("reconciliation check was not recorded"))
                .getDecision();
        recordAction(attempt, "RECONCILIATION_DECIDED", decision,
                status(open), status(closed), command.actor(), command.reason(), checkedAt);

        if (decision == ReconciliationDecision.MANUAL_REVIEW_REQUIRED) {
            // 계좌별 신규 주문 잠금: E4 의 ACCOUNT 범위 kill switch 를 그대로 재사용한다. 같은 tx 에서
            // engage 되어 판정·전이와 함께 커밋된다.
            killSwitchLedger.engage(
                    command.userId(),
                    KillSwitchLedger.Scope.ACCOUNT,
                    command.account().brokerConnectionId(),
                    ACCOUNT_LOCK_REASON,
                    command.actor());
            recordAction(attempt, "ACCOUNT_LOCK_ENGAGED", decision,
                    status(open), status(closed), command.actor(), ACCOUNT_LOCK_REASON, checkedAt);
            // 운영 알림: 기존 notification outbox 재사용. (event_type, source_id) 중복 방지로 멱등.
            notifications.emit(
                    command.userId(),
                    NotificationEventType.ORDER_RECONCILIATION_MANUAL_REVIEW,
                    attempt.getId(),
                    manualReviewPayload(attempt, command.account().brokerConnectionId(), decision,
                            status(open), status(closed)),
                    checkedAt);
        }

        return new Outcome(attempt.getId(), attempt.getOrderIntentId(), decision,
                decision == ReconciliationDecision.MANUAL_REVIEW_REQUIRED);
    }

    private Map<String, Object> manualReviewPayload(
            SubmissionAttempt attempt,
            UUID connectionId,
            ReconciliationDecision decision,
            String openStatus,
            String closedStatus
    ) {
        // 브로커 원문 식별자·자격증명은 담지 않는다(SPEC:1151). 내부 UUID·판정·조회 상태만.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("attemptId", attempt.getId().toString());
        payload.put("orderIntentId", attempt.getOrderIntentId().toString());
        payload.put("connectionId", connectionId.toString());
        payload.put("decision", decision.name());
        payload.put("openQueryStatus", openStatus);
        payload.put("closedQueryStatus", closedStatus);
        payload.put("accountLocked", true);
        return payload;
    }

    private PersistedAccount persistedAccount(UUID attemptId) {
        var rows = jdbc.query("""
                SELECT i.execution_mode, i.user_id, i.broker_connection_id, a.broker_account_id,
                       l.toss_account_seq, l.display_account_number
                  FROM submission_attempts a
                  JOIN order_intents i ON i.id = a.order_intent_id
                  LEFT JOIN real_order_account_allowlist l
                    ON l.user_id = i.user_id
                   AND l.broker_connection_id = i.broker_connection_id
                   AND l.broker_account_id = i.broker_account_id
                   AND l.enabled = TRUE
                 WHERE a.id = ?
                """, (rs, row) -> new PersistedAccount(
                OrderExecutionMode.valueOf(rs.getString("execution_mode")),
                rs.getObject("user_id", UUID.class),
                rs.getObject("broker_connection_id", UUID.class),
                rs.getObject("broker_account_id", UUID.class),
                rs.getString("toss_account_seq"),
                rs.getString("display_account_number")), attemptId);
        if (rows.size() != 1) {
            throw new IllegalStateException("persisted reconciliation account is missing or ambiguous");
        }
        return rows.getFirst();
    }

    private record PersistedAccount(
            OrderExecutionMode executionMode,
            UUID userId,
            UUID connectionId,
            UUID internalAccountId,
            String tossAccountSeq,
            String displayAccountNumber
    ) {
        boolean live() {
            return executionMode == OrderExecutionMode.LIVE;
        }

        boolean matches(Command command) {
            return userId.equals(command.userId())
                    && connectionId.equals(command.account().brokerConnectionId())
                    && "LIVE".equals(command.account().accountType())
                    && tossAccountSeq != null
                    && tossAccountSeq.equals(command.account().brokerAccountId())
                    && displayAccountNumber != null
                    && displayAccountNumber.equals(command.account().displayAccountNumber());
        }
    }

    private void recordAction(
            SubmissionAttempt attempt,
            String action,
            ReconciliationDecision decision,
            String openStatus,
            String closedStatus,
            String actor,
            String reason,
            Instant occurredAt
    ) {
        jdbc.update("""
                INSERT INTO order_reconciliation_actions (
                    id, submission_attempt_id, order_intent_id, action, decision,
                    open_query_status, closed_query_status, actor, reason, occurred_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                attempt.getId(),
                attempt.getOrderIntentId(),
                action,
                decision == null ? null : decision.name(),
                openStatus,
                closedStatus,
                actor,
                reason,
                at(occurredAt),
                at(clock.instant()));
    }

    private static String status(ReconciliationGroupOutcome outcome) {
        if (outcome instanceof ReconciliationGroupOutcome.Matched) {
            return "MATCHED";
        }
        if (outcome instanceof ReconciliationGroupOutcome.Absent) {
            return "ABSENT";
        }
        return "UNAVAILABLE";
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 운영자 조정 요청. {@code account} 는 브로커 조회 및 (필요 시) ACCOUNT kill switch 대상
     * ({@code brokerConnectionId})을 제공한다. {@code userId} 는 그 연결의 소유자여야 하며 잠금
     * 소유권 검증(E4)에 쓰인다.
     */
    public record Command(
            UUID attemptId,
            BrokerAccountRef account,
            UUID userId,
            Instant checkedAt,
            String actor,
            String reason
    ) {
        public Command {
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(checkedAt, "checkedAt");
            if (actor == null || actor.isBlank()) {
                throw new IllegalArgumentException("actor is required");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason is required");
            }
        }
    }

    public record Outcome(
            UUID attemptId,
            UUID orderIntentId,
            ReconciliationDecision decision,
            boolean accountLocked
    ) {
    }
}
