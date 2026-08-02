package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kill switch 원장 통합 테스트 (플랜 원장 E4). delta 스펙 "TDD와 검증" 항목 대응:
 * 전역/사용자/계좌 범위 차단, 좁은 범위가 넓은 범위를 해제 못 함, 이미 제출된 주문 불변,
 * engage step-up 불필요, disengage step-up 필수, 감사 레코드, 추가만(append-only).
 * fail-closed 는 {@link KillSwitchRevalidationCheckTest} 가 단위로 검증한다.
 */
@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "pre-trade-risk.max-order-amount.krw=100000000",
                "pre-trade-risk.max-order-amount.usd=1000000",
                "pre-trade-risk.max-quantity=100",
                "pre-trade-risk.max-concentration=0.99",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import(KillSwitchLedgerIntegrationTest.NoBrokerCallsConfiguration.class)
class KillSwitchLedgerIntegrationTest extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-02T03:00:00Z");

    @Autowired
    private PreTradeRiskEngine riskEngine;

    @Autowired
    private KillSwitchLedger killSwitch;

    @Autowired
    private OrderIntentRepository orderIntentRepository;

    @Autowired
    private SubmissionAttemptRepository attemptRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NoBrokerCallsAdapter brokerAdapter;

    @BeforeEach
    void cleanLedger() {
        jdbc.execute("""
                TRUNCATE kill_switch_ledger,
                         order_approval_step_up_tokens,
                         paper_order_workflow_commands,
                         pre_trade_risk_decisions,
                         order_submission_outbox_events,
                         order_submission_audit_logs,
                         reconciliation_checks,
                         submission_attempts,
                         submission_idempotency_keys,
                         order_intent_outbox_events,
                         order_intent_audit_logs,
                         execution_snapshots,
                         broker_orders,
                         real_order_daily_reservations, real_order_account_allowlist, order_intents,
                         broker_accounts,
                         risk_policy_history,
                         risk_policies,
                         account_capacity_snapshots,
                         position_snapshots,
                         account_snapshots,
                         account_sync_runs,
                         broker_connections,
                         users
                CASCADE
                """);
        brokerAdapter.calls.set(0);
    }

    @Test
    void globalEngageBlocksNewSubmissionWithoutCallingBroker() {
        var owner = owner();
        successfulSnapshot(owner, "100000");
        var intentId = approvedIntent(owner, "AAPL", "1");

        // engage 는 step-up 없이 즉시 가능(비상 정지).
        var engaged = killSwitch.engage(owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "market meltdown", "op");
        assertThat(engaged.engaged()).isTrue();
        assertThat(engaged.version()).isEqualTo(1);

        var result = submit(owner, intentId, "ks-global");

        assertThat(result.decision().approved()).isFalse();
        assertThat(result.decision().reasons())
                .contains(PreTradeRiskEngine.Reason.KILL_SWITCH_ENGAGED);
        assertThat(result.paperResult()).isNull();
        assertThat(attemptRepository.count()).isZero();
        assertThat(status(intentId)).isEqualTo(OrderIntentStatus.BLOCKED);
        assertThat(terminalReason(intentId)).isEqualTo("KILL_SWITCH_ENGAGED");
        assertThat(brokerAdapter.calls).hasValue(0);
    }

    @Test
    void userScopeBlocksOnlyThatUser() {
        var blocked = owner();
        var other = owner();
        successfulSnapshot(blocked, "100000");
        successfulSnapshot(other, "100000");
        var blockedIntent = approvedIntent(blocked, "AAPL", "1");
        var otherIntent = approvedIntent(other, "AAPL", "1");

        killSwitch.engage(blocked.userId(), KillSwitchLedger.Scope.USER, blocked.userId(), "user halt", "op");

        assertThat(submit(blocked, blockedIntent, "ks-user-blocked").decision().reasons())
                .contains(PreTradeRiskEngine.Reason.KILL_SWITCH_ENGAGED);
        var allowed = submit(other, otherIntent, "ks-user-allowed");
        assertThat(allowed.decision().approved()).isTrue();
        assertThat(allowed.paperResult()).isNotNull();
        assertThat(status(otherIntent)).isEqualTo(OrderIntentStatus.COMPLETED);
    }

    @Test
    void accountScopeBlocksOnlyThatAccount() {
        // 스키마상 사용자당 활성 계좌(연결)는 하나이므로, 계좌 격리는 다른 사용자의 계좌가 영향받지
        // 않음으로 보이고, 계좌 범위가 사용자 범위가 아님은 anyEngaged 프로브로 못박는다.
        var owner = owner();
        var other = owner();
        successfulSnapshot(owner, "100000");
        successfulSnapshot(other, "100000");
        var blockedIntent = approvedIntent(owner, "AAPL", "1");
        var otherIntent = approvedIntent(other, "AAPL", "1");

        killSwitch.engage(owner.userId(), KillSwitchLedger.Scope.ACCOUNT, owner.connectionId(), "account halt", "op");

        // 대상 계좌는 차단되지만, 같은 사용자라도 다른 계좌 ID 는 차단되지 않는다(계좌 범위 ≠ 사용자 범위).
        assertThat(killSwitch.anyEngaged(owner.userId(), owner.connectionId())).isTrue();
        assertThat(killSwitch.anyEngaged(owner.userId(), UUID.randomUUID())).isFalse();

        assertThat(submit(owner, blockedIntent, "ks-acct-blocked").decision().reasons())
                .contains(PreTradeRiskEngine.Reason.KILL_SWITCH_ENGAGED);
        var allowed = submit(other, otherIntent, "ks-acct-allowed");
        assertThat(allowed.decision().approved()).isTrue();
        assertThat(allowed.paperResult()).isNotNull();
    }

    @Test
    void narrowDisengageCannotOverrideBroaderEngage() {
        var owner = owner();
        successfulSnapshot(owner, "100000");
        var intentId = approvedIntent(owner, "AAPL", "1");

        killSwitch.engage(owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "all stop", "op");
        killSwitch.engage(owner.userId(), KillSwitchLedger.Scope.USER, owner.userId(), "user stop", "op");
        // 좁은 범위(USER)를 해제해도 넓은 범위(GLOBAL)의 정지는 유효하다.
        insertDisengageToken(owner.userId(), owner.userId(), "tok-user");
        var disengaged = killSwitch.disengage(
                owner.userId(), KillSwitchLedger.Scope.USER, owner.userId(), "user resume", "op", "tok-user");
        assertThat(disengaged.engaged()).isFalse();

        assertThat(killSwitch.anyEngaged(owner.userId(), owner.connectionId())).isTrue();
        assertThat(submit(owner, intentId, "ks-narrow").decision().reasons())
                .contains(PreTradeRiskEngine.Reason.KILL_SWITCH_ENGAGED);
    }

    @Test
    void alreadySubmittedOrderIsUnaffectedWhileNewSubmissionsBlock() {
        var owner = owner();
        successfulSnapshot(owner, "100000");
        var submitted = approvedIntent(owner, "AAPL", "1");
        var first = submit(owner, submitted, "ks-existing");
        assertThat(first.paperResult()).isNotNull();
        assertThat(status(submitted)).isEqualTo(OrderIntentStatus.COMPLETED);
        assertThat(attemptRepository.count()).isEqualTo(1);

        // 이미 제출·체결된 주문이 있는 상태에서 kill switch 를 켠다.
        killSwitch.engage(owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "halt after fill", "op");

        var fresh = approvedIntent(owner, "MSFT", "1");
        var blocked = submit(owner, fresh, "ks-fresh");

        assertThat(blocked.decision().approved()).isFalse();
        assertThat(blocked.decision().reasons())
                .contains(PreTradeRiskEngine.Reason.KILL_SWITCH_ENGAGED);
        // 신규 제출은 새 브로커 시도를 만들지 않는다.
        assertThat(attemptRepository.count()).isEqualTo(1);
        assertThat(status(fresh)).isEqualTo(OrderIntentStatus.BLOCKED);
        // 이미 제출된 주문은 kill switch 로 취소·변경되지 않는다.
        assertThat(status(submitted)).isEqualTo(OrderIntentStatus.COMPLETED);
    }

    @Test
    void disengageRequiresStepUpButEngageDoesNot() {
        var owner = owner();
        killSwitch.engage(owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "stop", "op");

        // step-up 토큰이 없으면 해제는 401(step-up 필요)로 거절된다.
        assertThatThrownBy(() -> killSwitch.disengage(
                owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "resume", "op", null))
                .isInstanceOf(PaperOrderWorkflowException.class)
                .satisfies(exception -> assertThat(((PaperOrderWorkflowException) exception).code())
                        .isEqualTo(PaperOrderWorkflowException.Code.STEP_UP_REQUIRED));
        assertThat(killSwitch.anyEngaged(owner.userId(), owner.connectionId())).isTrue();

        // 유효한 step-up 토큰이 있으면 해제된다.
        insertDisengageToken(owner.userId(), KillSwitchLedger.GLOBAL_TARGET, "tok-global");
        var disengaged = killSwitch.disengage(
                owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "resume", "op", "tok-global");
        assertThat(disengaged.engaged()).isFalse();
        assertThat(killSwitch.anyEngaged(owner.userId(), owner.connectionId())).isFalse();
        // 단일 사용: 같은 토큰으로 다시 해제 시도하면 거절된다.
        assertThatThrownBy(() -> killSwitch.disengage(
                owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "resume again", "op", "tok-global"))
                .isInstanceOf(PaperOrderWorkflowException.class);
    }

    @Test
    void ledgerIsAppendOnlyWithActorReasonAndTimestamp() {
        var owner = owner();
        var engaged = killSwitch.engage(owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "first stop", "actor-1");
        insertDisengageToken(owner.userId(), KillSwitchLedger.GLOBAL_TARGET, "tok");
        var disengaged = killSwitch.disengage(
                owner.userId(), KillSwitchLedger.Scope.GLOBAL, null, "then resume", "actor-2", "tok");

        assertThat(engaged.version()).isEqualTo(1);
        assertThat(disengaged.version()).isEqualTo(2);
        // 덮어쓰기가 아니라 추가: GLOBAL 대상에 두 개의 버전 행이 남는다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kill_switch_ledger WHERE scope = 'GLOBAL'", Long.class)).isEqualTo(2L);
        // 각 변경은 행위자·사유·시각을 감사로 남긴다.
        var audit = jdbc.queryForMap("""
                SELECT actor, reason, engaged, changed_at
                  FROM kill_switch_ledger WHERE scope = 'GLOBAL' AND version = 1
                """);
        assertThat(audit).containsEntry("actor", "actor-1").containsEntry("reason", "first stop");
        assertThat(audit.get("engaged")).isEqualTo(true);
        assertThat(audit.get("changed_at")).isNotNull();

        // 원장은 추가만 허용: 상태 덮어쓰기·삭제를 DB 경계에서 거부한다.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE kill_switch_ledger SET engaged = false WHERE scope = 'GLOBAL' AND version = 1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM kill_switch_ledger WHERE scope = 'GLOBAL' AND version = 1"))
                .isInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private PreTradeRiskEngine.SubmissionResult submit(Owner owner, UUID intentId, String clientOrderId) {
        return riskEngine.submitPaper(owner.userId(), owner.connectionId(), paperCommand(intentId, clientOrderId));
    }

    private PaperTradingBroker.SubmitCommand paperCommand(UUID intentId, String clientOrderId) {
        return new PaperTradingBroker.SubmitCommand(
                intentId,
                clientOrderId,
                new BigDecimal("100"),
                new BigDecimal("1"),
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED,
                T0.plusSeconds(1),
                "ks-test");
    }

    private UUID approvedIntent(Owner owner, String symbol, String quantity) {
        return approvedIntent(owner.userId(), owner.connectionId(), symbol, quantity);
    }

    private UUID approvedIntent(UUID userId, UUID connectionId, String symbol, String quantity) {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(
                intentId, accountId, userId, connectionId,
                OrderSide.BUY, OrderType.MARKET, symbol, new BigDecimal(quantity), null, Currency.USD));
        var approval = riskEngine.approve(new PreTradeRiskEngine.ApprovalCommand(
                userId, connectionId, intentId, new BigDecimal("100"), T0, "ks-test"));
        assertThat(approval.approved()).isTrue();
        return intentId;
    }

    private void insertDisengageToken(UUID userId, UUID subjectRef, String rawToken) {
        var now = Instant.now();
        jdbc.update("""
                INSERT INTO order_approval_step_up_tokens (
                    token_hash, user_id, order_intent_id, subject_kind, subject_ref,
                    issued_at, expires_at, consumed_at
                ) VALUES (?, ?, NULL, 'KILL_SWITCH_DISENGAGE', ?, ?, ?, NULL)
                """,
                OrderApprovalStepUpService.sha256Hex(rawToken),
                userId,
                subjectRef,
                at(now.minusSeconds(1)),
                at(now.plusSeconds(300)));
    }

    private OrderIntentStatus status(UUID intentId) {
        return orderIntentRepository.findById(intentId).orElseThrow().getStatus();
    }

    private String terminalReason(UUID intentId) {
        return jdbc.queryForObject(
                "SELECT terminal_reason FROM order_intents WHERE id = ?", String.class, intentId);
    }

    private Owner owner() {
        var userId = UUID.randomUUID();
        insertUser(userId);
        return new Owner(userId, connection(userId));
    }

    private void insertUser(UUID userId) {
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
    }

    private UUID connection(UUID userId) {
        var connectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], at(T0), at(T0));
        return connectionId;
    }

    private void successfulSnapshot(Owner owner, String usdBuyingPower) {
        successfulSnapshot(owner.userId(), owner.connectionId(), usdBuyingPower);
    }

    private void successfulSnapshot(UUID userId, UUID connectionId, String usdBuyingPower) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, userId, connectionId, at(T0), at(T0.plusSeconds(1)));
        jdbc.update("""
                INSERT INTO account_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, account_type,
                    display_account_number, total_purchase_amounts, market_value_amounts,
                    market_value_after_cost_amounts, profit_loss_amounts,
                    profit_loss_after_cost_amounts, daily_profit_loss_amounts,
                    profit_loss_rate, profit_loss_rate_after_cost, daily_profit_loss_rate,
                    cash_balance_status, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'GENERAL', '****5678',
                    '{}'::jsonb, jsonb_build_object('USD', CAST(? AS numeric)), '{}'::jsonb,
                    '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                    0, 0, 0, 'KNOWN', ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId, usdBuyingPower, at(T0), at(T0));
        for (var currency : List.of("KRW", "USD")) {
            jdbc.update("""
                    INSERT INTO account_capacity_snapshots (
                        id, sync_run_id, user_id, broker_connection_id, currency,
                        cash_buying_power, observed_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, CAST(? AS numeric), ?, ?)
                    """, UUID.randomUUID(), runId, userId, connectionId, currency, usdBuyingPower, at(T0), at(T0));
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Owner(UUID userId, UUID connectionId) {
    }

    @TestConfiguration
    static class NoBrokerCallsConfiguration {

        @Bean
        NoBrokerCallsAdapter brokerAdapter() {
            return new NoBrokerCallsAdapter();
        }
    }

    static final class NoBrokerCallsAdapter implements BrokerAdapter {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            throw called();
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            throw called();
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            throw called();
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            throw called();
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(
                BrokerAccountRef account,
                Currency currency
        ) {
            throw called();
        }

        @Override
        public BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol) {
            throw called();
        }

        private AssertionError called() {
            calls.incrementAndGet();
            return new AssertionError("kill switch path must not call a live broker");
        }
    }
}
