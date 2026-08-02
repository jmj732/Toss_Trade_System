package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.SellableQuantitySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UNKNOWN 제출 시도 조정 절차 통합 테스트 (플랜 원장 E5; SPEC:1055 MVP 완료 기준 7번).
 *
 * <p>급소를 장애 주입으로 재현한다: 주문이 이미 체결돼 CLOSED 에만 있고 OPEN 에는 없을 때, 우리
 * 조정기는 이를 미접수로 오판해 재전송하지 않고({@code RETRY_SAME_KEY_ALLOWED} 아님)
 * {@code BROKER_ORDER_FOUND} 로 연결한다. 반대로 CLOSED 조회가 실패하면 재시도가 아니라
 * {@code MANUAL_REVIEW_REQUIRED} 로 가고 계좌 신규 주문을 잠근다. 잠금 해제는 운영자의 명시
 * 행위(E4 disengage, step-up 필요)로만 가능하다.
 *
 * <p>이 테스트가 따라가는 절차는 {@code docs/ops/order-reconciliation-runbook.md} 와 일치한다.
 */
@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import({
        UnknownAttemptReconciliationIntegrationTest.NoBrokerConfiguration.class,
        UnknownAttemptReconciliationIntegrationTest.ProbeConfiguration.class
})
class UnknownAttemptReconciliationIntegrationTest extends PostgresIntegrationTest {

    private static final String ACTOR = "reconcile-test";
    private static final Instant T0 = Instant.parse("2026-07-27T01:00:00Z");
    private static final UUID USER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CONNECTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final ControllableProbe PROBE = new ControllableProbe();

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UnknownAttemptReconciler reconciler;

    @Autowired
    private OrderSubmissionService submissionService;

    @Autowired
    private OrderIntentTransitionService transitionService;

    @Autowired
    private OrderIntentRepository orderIntentRepository;

    @Autowired
    private SubmissionAttemptRepository attemptRepository;

    @Autowired
    private BrokerOrderRepository brokerOrderRepository;

    @Autowired
    private KillSwitchLedger killSwitchLedger;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        PROBE.reset();
        jdbc.execute("""
                TRUNCATE order_reconciliation_actions, order_approval_step_up_tokens,
                         notification_outbox_events,
                         order_submission_outbox_events, order_submission_audit_logs,
                         reconciliation_checks, submission_attempts, submission_idempotency_keys,
                         order_intent_outbox_events, order_intent_audit_logs,
                         execution_snapshots, broker_orders, real_order_daily_reservations, real_order_account_allowlist, order_intents,
                         kill_switch_ledger, broker_connections, broker_accounts, users
                    RESTART IDENTITY CASCADE
                """);
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status,
                    credential_ciphertext, credential_nonce, credential_key_version,
                    credential_revision, created_at, updated_at
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?)
                """,
                CONNECTION_ID, USER_ID,
                new byte[20], new byte[12],
                at(T0), at(T0));
    }

    // --- TDD: OPEN 없음 + CLOSED 있음 → BROKER_ORDER_FOUND (장애 주입으로 MVP 완료 기준 7번) ---
    @Test
    void closedOnlyMatchIsFoundNotResent_reproducesMvpCriterionSeven() {
        var intentId = submissionPendingIntent();
        var attemptId = unknownAttempt(intentId, "client-found");
        // 장애 주입: 주문이 이미 체결돼 CLOSED 그룹에만 존재하고 OPEN 에는 없다.
        PROBE.open = new ReconciliationGroupOutcome.Absent();
        PROBE.closed = new ReconciliationGroupOutcome.Matched(
                "broker-found", "client-found", BrokerOrderStatus.FILLED, new BigDecimal("10"));

        var outcome = reconciler.reconcile(command(attemptId, T0.plusSeconds(30)));

        assertThat(outcome.decision()).isEqualTo(ReconciliationDecision.BROKER_ORDER_FOUND);
        assertThat(outcome.accountLocked()).isFalse();
        // 재전송 아님: 정확히 하나의 브로커 주문이 연결되고 intent 는 체결로 종결.
        assertThat(brokerOrderRepository.findByBrokerAccountIdAndBrokerOrderId(accountId(intentId), "broker-found"))
                .isPresent();
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.COMPLETED);
        assertThat(killSwitchLedger.anyEngaged(USER_ID, CONNECTION_ID)).isFalse();
        assertThat(actionCount(attemptId, "RECONCILIATION_DECIDED")).isEqualTo(1);
        assertThat(decisionActionOpenClosed(attemptId)).containsExactly("ABSENT", "MATCHED");
    }

    // --- TDD: OPEN·CLOSED 모두 성공 + 없음 → RETRY_SAME_KEY_ALLOWED, 잠금 없음 ---
    @Test
    void bothAbsentWithinWindowAllowsRetryWithoutLock() {
        var intentId = submissionPendingIntent();
        var attemptId = unknownAttempt(intentId, "client-retry");
        PROBE.open = new ReconciliationGroupOutcome.Absent();
        PROBE.closed = new ReconciliationGroupOutcome.Absent();

        var outcome = reconciler.reconcile(command(attemptId, T0.plusSeconds(30)));

        assertThat(outcome.decision()).isEqualTo(ReconciliationDecision.RETRY_SAME_KEY_ALLOWED);
        assertThat(outcome.accountLocked()).isFalse();
        assertThat(attemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(SubmissionAttemptStatus.RECONCILED_NO_MATCH);
        assertThat(killSwitchLedger.anyEngaged(USER_ID, CONNECTION_ID)).isFalse();
        // 재시도가 실제로 가능(도메인이 허용).
        var childId = submissionService.retrySameKey(attemptId, "internal-retry-2", T0.plusSeconds(40), ACTOR);
        assertThat(attemptRepository.findById(childId).orElseThrow().getRetryOfAttemptId()).isEqualTo(attemptId);
    }

    // --- TDD: CLOSED 조회 실패 → MANUAL_REVIEW_REQUIRED (미접수로 오판하지 않음) + 계좌 잠금 + 알림 ---
    @Test
    void closedQueryFailureRequiresManualReviewAndLocksAccountAndNotifies() {
        var intentId = submissionPendingIntent();
        var attemptId = unknownAttempt(intentId, "client-manual");
        // 장애 주입: OPEN 은 없음이지만 CLOSED 조회가 실패(미확정)한다. 재시도로 오판하면 안 된다.
        PROBE.open = new ReconciliationGroupOutcome.Absent();
        PROBE.closed = new ReconciliationGroupOutcome.Unavailable("CLOSED query unavailable");

        var outcome = reconciler.reconcile(command(attemptId, T0.plusSeconds(30)));

        assertThat(outcome.decision()).isEqualTo(ReconciliationDecision.MANUAL_REVIEW_REQUIRED);
        assertThat(outcome.accountLocked()).isTrue();
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
        // 계좌별 신규 주문 잠금: 제출 FINAL 관문이 읽는 바로 그 술어가 참이 된다(E4 재사용).
        assertThat(killSwitchLedger.anyEngaged(USER_ID, CONNECTION_ID)).isTrue();
        // 운영 알림: 기존 notification outbox 재사용.
        assertThat(notificationCount(attemptId)).isEqualTo(1);
        // 감사: 진입·판정·잠금.
        assertThat(actionCount(attemptId, "RECONCILIATION_ENTERED")).isEqualTo(1);
        assertThat(actionCount(attemptId, "RECONCILIATION_DECIDED")).isEqualTo(1);
        assertThat(actionCount(attemptId, "ACCOUNT_LOCK_ENGAGED")).isEqualTo(1);
        assertThat(decisionActionOpenClosed(attemptId)).containsExactly("ABSENT", "UNAVAILABLE");
    }

    // --- TDD: OPEN 만 확정된 상태로는 재시도 불가(구조적) ---
    @Test
    void openResolvedButClosedUnavailableNeverRetries() {
        var intentId = submissionPendingIntent();
        var attemptId = unknownAttempt(intentId, "client-openonly");
        PROBE.open = new ReconciliationGroupOutcome.Absent();
        PROBE.closed = new ReconciliationGroupOutcome.Unavailable("CLOSED query unavailable");

        var outcome = reconciler.reconcile(command(attemptId, T0.plusSeconds(30)));

        assertThat(outcome.decision()).isNotEqualTo(ReconciliationDecision.RETRY_SAME_KEY_ALLOWED);
        assertThat(outcome.decision()).isEqualTo(ReconciliationDecision.MANUAL_REVIEW_REQUIRED);
    }

    // --- TDD: 조정 멱등 — 반복 조정이 브로커 주문을 늘리지 않음 ---
    @Test
    void repeatedReconciliationDoesNotGrowBrokerOrders() {
        var intentId = submissionPendingIntent();
        var attemptId = unknownAttempt(intentId, "client-idem");
        PROBE.open = new ReconciliationGroupOutcome.Absent();
        PROBE.closed = new ReconciliationGroupOutcome.Matched(
                "broker-idem", "client-idem", BrokerOrderStatus.FILLED, new BigDecimal("10"));

        reconciler.reconcile(command(attemptId, T0.plusSeconds(30)));
        var brokerOrdersAfterFirst = brokerOrderCount(accountId(intentId));

        // 두 번째 조정은 attempt 가 이미 종결(UNKNOWN 아님)이라 거부되고 브로커 주문은 늘지 않는다.
        assertThatThrownBy(() -> reconciler.reconcile(command(attemptId, T0.plusSeconds(60))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(brokerOrderCount(accountId(intentId))).isEqualTo(brokerOrdersAfterFirst).isEqualTo(1);
    }

    // --- TDD: 자동 해제 없음 / 해제 step-up 필요 / 해제 후 신규 주문 재개 ---
    @Test
    void lockPersistsUntilOperatorReleasesWithStepUp() {
        var intentId = submissionPendingIntent();
        var attemptId = unknownAttempt(intentId, "client-release");
        PROBE.open = new ReconciliationGroupOutcome.Unavailable("OPEN query unavailable");
        PROBE.closed = new ReconciliationGroupOutcome.Absent();
        reconciler.reconcile(command(attemptId, T0.plusSeconds(30)));
        assertThat(killSwitchLedger.anyEngaged(USER_ID, CONNECTION_ID)).isTrue();

        // 자동 해제 없음: 시간 경과/재조회로는 풀리지 않는다(같은 술어를 다시 읽어도 여전히 잠김).
        assertThat(killSwitchLedger.anyEngaged(USER_ID, CONNECTION_ID)).isTrue();

        // 해제에 step-up 필요: 토큰 없으면 거부(원장에 해제 행이 생기지 않음).
        assertThatThrownBy(() -> killSwitchLedger.disengage(
                USER_ID, KillSwitchLedger.Scope.ACCOUNT, CONNECTION_ID, "resume", ACTOR, null))
                .isInstanceOf(PaperOrderWorkflowException.class);
        assertThat(killSwitchLedger.anyEngaged(USER_ID, CONNECTION_ID)).isTrue();

        // 유효한 step-up 으로만 해제 → 신규 주문 재개.
        insertDisengageToken("release-token");
        killSwitchLedger.disengage(
                USER_ID, KillSwitchLedger.Scope.ACCOUNT, CONNECTION_ID, "resume", ACTOR, "release-token");
        assertThat(killSwitchLedger.anyEngaged(USER_ID, CONNECTION_ID)).isFalse();
        // 해제 감사는 kill switch 원장에 남는다(disengage 행).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kill_switch_ledger WHERE engaged = false AND target_id = ?",
                Long.class, CONNECTION_ID)).isEqualTo(1);
    }

    // --- 수동 조정 API (운영자 엔드포인트) ---
    @Test
    void manualReconciliationApiReturnsDecision() throws Exception {
        var intentId = submissionPendingIntent();
        var attemptId = unknownAttempt(intentId, "client-api");
        PROBE.open = new ReconciliationGroupOutcome.Absent();
        PROBE.closed = new ReconciliationGroupOutcome.Matched(
                "broker-api", "client-api", BrokerOrderStatus.FILLED, new BigDecimal("10"));

        mockMvc.perform(post("/api/v1/trading/order-reconciliation")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reconcileBody(attemptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("BROKER_ORDER_FOUND"))
                .andExpect(jsonPath("$.accountLocked").value(false));
    }

    @Test
    void manualReconciliationApiConflictsForNonUnknownAttempt() throws Exception {
        var intentId = submissionPendingIntent();
        var attemptId = unknownAttempt(intentId, "client-conflict");
        PROBE.open = new ReconciliationGroupOutcome.Absent();
        PROBE.closed = new ReconciliationGroupOutcome.Absent();
        reconciler.reconcile(command(attemptId, T0.plusSeconds(30))); // attempt 종결

        mockMvc.perform(post("/api/v1/trading/order-reconciliation")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reconcileBody(attemptId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_RECONCILIATION_STATE_INVALID"));
    }

    @Test
    void liveReconciliationAccountMismatchIsManualReviewBeforeBrokerProbe() {
        var accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        jdbc.update("""
                INSERT INTO real_order_account_allowlist (
                    id, user_id, broker_connection_id, broker_account_id, toss_account_seq,
                    display_account_number, enabled, daily_limit_krw, daily_limit_usd, created_at
                ) VALUES (?, ?, ?, ?, '01', '******0001', TRUE, 1000, 200, ?)
                """, UUID.randomUUID(), USER_ID, CONNECTION_ID, accountId, at(T0));
        var intentId = UUID.randomUUID();
        orderIntentRepository.saveAndFlush(OrderIntent.proposedLive(
                intentId, accountId, USER_ID, CONNECTION_ID, OrderSide.BUY, OrderType.LIMIT,
                "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD));
        transitionService.approve(intentId, ACTOR);
        transitionService.startRevalidation(intentId, ACTOR);
        transitionService.markSubmissionPending(intentId, ACTOR);
        var attemptId = unknownAttempt(intentId, "client-live-mismatch");
        PROBE.open = new ReconciliationGroupOutcome.Absent();
        PROBE.closed = new ReconciliationGroupOutcome.Absent();

        var outcome = reconciler.reconcile(new UnknownAttemptReconciler.Command(
                attemptId,
                new BrokerAccountRef(CONNECTION_ID, "02", "LIVE", "******0002"),
                USER_ID,
                T0.plusSeconds(30),
                ACTOR,
                "wrong account supplied"));

        assertThat(outcome.decision()).isEqualTo(ReconciliationDecision.MANUAL_REVIEW_REQUIRED);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(killSwitchLedger.anyEngaged(USER_ID, CONNECTION_ID)).isTrue();
        assertThat(actionCount(attemptId, "ACCOUNT_MAPPING_MISMATCH")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private UnknownAttemptReconciler.Command command(UUID attemptId, Instant checkedAt) {
        return new UnknownAttemptReconciler.Command(
                attemptId,
                new BrokerAccountRef(CONNECTION_ID, "acct-native", "PAPER", "****1234"),
                USER_ID,
                checkedAt,
                ACTOR,
                "operator reconciliation");
    }

    private String reconcileBody(UUID attemptId) {
        return """
                {"attemptId":"%s","brokerConnectionId":"%s","brokerAccountId":"acct-native",
                 "accountType":"PAPER","displayAccountNumber":"****1234","reason":"operator reconciliation"}
                """.formatted(attemptId, CONNECTION_ID);
    }

    private UUID submissionPendingIntent() {
        var intentId = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?) ON CONFLICT DO NOTHING", accountId);
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(intentId, accountId, new BigDecimal("10")));
        transitionService.approve(intentId, ACTOR);
        transitionService.startRevalidation(intentId, ACTOR);
        transitionService.markSubmissionPending(intentId, ACTOR);
        return intentId;
    }

    private UUID unknownAttempt(UUID intentId, String clientOrderId) {
        var attemptId = submissionService.createInitialAttempt(
                intentId, clientOrderId, "hash-" + clientOrderId, "internal-" + clientOrderId, T0, ACTOR);
        submissionService.startDispatch(
                attemptId, T0.plusSeconds(1), new DispatchEvidence(clientOrderId, "sent"), ACTOR);
        submissionService.markUnknown(
                attemptId, T0.plusSeconds(2), new DispatchEvidence(clientOrderId, "timeout"), ACTOR);
        return attemptId;
    }

    private UUID accountId(UUID intentId) {
        return orderIntentRepository.findById(intentId).orElseThrow().getBrokerAccountId();
    }

    private long brokerOrderCount(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM broker_orders WHERE broker_account_id = ?", Long.class, accountId);
    }

    private long actionCount(UUID attemptId, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM order_reconciliation_actions WHERE submission_attempt_id = ? AND action = ?",
                Long.class, attemptId, action);
    }

    private List<String> decisionActionOpenClosed(UUID attemptId) {
        var pair = jdbc.queryForObject("""
                SELECT open_query_status || ',' || closed_query_status
                  FROM order_reconciliation_actions
                 WHERE submission_attempt_id = ? AND action = 'RECONCILIATION_DECIDED'
                """, String.class, attemptId);
        return List.of(pair.split(","));
    }

    private long notificationCount(UUID attemptId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox_events
                 WHERE source_id = ? AND event_type = 'ORDER_RECONCILIATION_MANUAL_REVIEW'
                """, Long.class, attemptId);
    }

    private void insertDisengageToken(String rawToken) {
        var now = Instant.now();
        jdbc.update("""
                INSERT INTO order_approval_step_up_tokens (
                    token_hash, user_id, order_intent_id, subject_kind, subject_ref,
                    issued_at, expires_at, consumed_at
                ) VALUES (?, ?, NULL, 'KILL_SWITCH_DISENGAGE', ?, ?, ?, NULL)
                """,
                OrderApprovalStepUpService.sha256Hex(rawToken),
                USER_ID,
                CONNECTION_ID,
                at(now.minusSeconds(1)),
                at(now.plusSeconds(300)));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static final class ControllableProbe implements ReconciliationBrokerProbe {
        volatile ReconciliationGroupOutcome open;
        volatile ReconciliationGroupOutcome closed;

        void reset() {
            open = null;
            closed = null;
        }

        @Override
        public ReconciliationGroupOutcome probe(BrokerAccountRef account, BrokerOrderGroup group, String clientOrderId) {
            return group == BrokerOrderGroup.OPEN ? open : closed;
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        @Primary
        ReconciliationBrokerProbe controllableReconciliationBrokerProbe() {
            return PROBE;
        }
    }

    @TestConfiguration
    static class NoBrokerConfiguration {
        @Bean
        BrokerAdapter brokerAdapter() {
            return new UnusedBrokerAdapter();
        }
    }

    static final class UnusedBrokerAdapter implements BrokerAdapter {
        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            throw new AssertionError("reconciliation must not call the read broker adapter");
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            throw new AssertionError("reconciliation must not call the read broker adapter");
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            throw new AssertionError("reconciliation must not call the read broker adapter");
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            throw new AssertionError("reconciliation must not call the read broker adapter");
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(BrokerAccountRef account, Currency currency) {
            throw new AssertionError("reconciliation must not call the read broker adapter");
        }

        @Override
        public BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol) {
            throw new AssertionError("reconciliation must not call the read broker adapter");
        }
    }
}
