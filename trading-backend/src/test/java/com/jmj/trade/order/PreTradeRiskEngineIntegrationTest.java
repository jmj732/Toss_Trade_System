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
import com.jmj.trade.broker.connection.BrokerConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "pre-trade-risk.max-order-amount.krw=1000000",
                "pre-trade-risk.max-order-amount.usd=1000",
                "pre-trade-risk.max-quantity=5",
                "pre-trade-risk.max-concentration=0.50",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import(PreTradeRiskEngineIntegrationTest.NoBrokerCallsConfiguration.class)
class PreTradeRiskEngineIntegrationTest extends PostgresIntegrationTest {

    // 스냅샷 신선도(portfolio.snapshot.max-age, 기본 PT15M)가 실제 시각 기준으로 판정되므로,
    // 성공 스냅샷이 나이만으로 SNAPSHOT_TOO_OLD 처리되지 않도록 픽스처 기준 시각을 현재 기준
    // 상대값으로 고정한다. 나이 기반 stale 자체는 별도 테스트에서 명시적으로 검증한다.
    private static final Instant T0 = Instant.now().minusSeconds(30);

    @Autowired
    private PreTradeRiskEngine riskEngine;

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
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_risk_paper_outbox ON order_submission_outbox_events");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_risk_paper_outbox()");
        jdbc.execute("""
                TRUNCATE paper_order_workflow_commands,
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
                         broker_connections,
                         users
                CASCADE
                """);
        brokerAdapter.calls.set(0);
    }

    @Test
    void approvalAndFinalRevalidationRecordSnapshotAndSubmitPaperOrder() {
        var owner = owner();
        var snapshotId = successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "2", null);

        var approval = riskEngine.approve(command(owner, intentId, "100", T0));
        var submitted = riskEngine.submitPaper(
                owner.userId(),
                owner.connectionId(),
                new PaperTradingBroker.SubmitCommand(
                        intentId, "risk-paper-1", new BigDecimal("100"), new BigDecimal("2"),
                        PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, T0.plusSeconds(1), "risk-test"));

        assertThat(approval.approved()).isTrue();
        assertThat(approval.snapshotId()).isEqualTo(snapshotId);
        assertThat(submitted.decision().approved()).isTrue();
        assertThat(submitted.decision().snapshotId()).isEqualTo(snapshotId);
        assertThat(submitted.paperResult()).isNotNull();
        assertThat(submitted.paperResult().intentStatus()).isEqualTo(OrderIntentStatus.COMPLETED);
        assertThat(decisions(intentId)).containsExactlyInAnyOrder(
                "APPROVAL|APPROVED||" + snapshotId,
                "FINAL|APPROVED||" + snapshotId);
        assertThat(brokerAdapter.calls).hasValue(0);
    }

    @Test
    void staleAndPartialSnapshotsAreBlockedButAccountingCashStatusDoesNotOverrideBuyingPower() {
        var staleOwner = owner();
        successfulSnapshot(staleOwner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        failedRun(staleOwner, T0.plusSeconds(10));
        assertBlocked(staleOwner, "STALE_SNAPSHOT");

        var partialOwner = owner();
        successfulSnapshot(partialOwner, "KNOWN", false, "1000", "1000", "MSFT", "100");
        assertBlocked(partialOwner, "PARTIAL_SNAPSHOT");

        var accountingCashOwner = owner();
        successfulSnapshot(accountingCashOwner, "UNKNOWN", true, "1000", "1000", "MSFT", "100");
        var intentId = intent(accountingCashOwner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);
        assertThat(riskEngine.approve(command(accountingCashOwner, intentId, "100", T0)).approved()).isTrue();
    }

    @Test
    void snapshotOlderThanMaxAgeIsBlockedAsStaleEvenWithoutANewerRun() {
        var owner = owner();
        // 더 새로운 RUNNING/FAILED run 이 없어도, 선택된 성공 스냅샷의 completed_at 이
        // portfolio.snapshot.max-age(기본 PT15M)를 넘기면 나이만으로 SNAPSHOT_TOO_OLD 가 되어
        // 리스크 엔진이 주문을 차단한다.
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100",
                Instant.now().minus(Duration.ofMinutes(30)));
        assertBlocked(owner, "STALE_SNAPSHOT");
    }

    @Test
    void buyingPowerOrderAmountQuantityAndConcentrationLimitsAreRecorded() {
        var buyingPowerOwner = owner();
        successfulSnapshot(buyingPowerOwner, "KNOWN", true, "800", "1000", "MSFT", "1000");
        assertReason(buyingPowerOwner, "AAPL", "5", "200", "BUYING_POWER_EXCEEDED");

        var amountOwner = owner();
        successfulSnapshot(amountOwner, "KNOWN", true, "5000", "1000", "MSFT", "1000");
        assertReason(amountOwner, "AAPL", "5", "201", "MAX_ORDER_AMOUNT_EXCEEDED");

        var quantityOwner = owner();
        successfulSnapshot(quantityOwner, "KNOWN", true, "5000", "1000", "MSFT", "1000");
        assertReason(quantityOwner, "AAPL", "6", "1", "MAX_QUANTITY_EXCEEDED");

        var concentrationOwner = owner();
        successfulSnapshot(concentrationOwner, "KNOWN", true, "5000", "1000", "NVDA", "900");
        assertReason(concentrationOwner, "NVDA", "1", "10", "CONCENTRATION_EXCEEDED");
    }

    @Test
    void finalRevalidationBlocksWhenSnapshotBecomesStaleWithoutCallingPaper() {
        var owner = owner();
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);
        assertThat(riskEngine.approve(command(owner, intentId, "100", T0)).approved()).isTrue();
        failedRun(owner, T0.plusSeconds(10));

        var result = riskEngine.submitPaper(
                owner.userId(),
                owner.connectionId(),
                paperCommand(intentId, "risk-stale-final", "100"));

        assertThat(result.decision().approved()).isFalse();
        assertThat(result.decision().reasons())
                .contains(PreTradeRiskEngine.Reason.STALE_SNAPSHOT);
        assertThat(result.paperResult()).isNull();
        assertThat(attemptRepository.count()).isZero();
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.BLOCKED);
    }

    @Test
    void concurrentOrdersCannotReserveMoreThanOneSnapshotBuyingPower() throws Exception {
        var owner = owner();
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "1000");
        var firstIntent = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "3", null);
        var secondIntent = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "3", null);
        assertThat(riskEngine.approve(command(owner, firstIntent, "200", T0)).approved()).isTrue();
        assertThat(riskEngine.approve(command(owner, secondIntent, "200", T0)).approved()).isTrue();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> submitAfterGate(
                    owner, paperCommand(firstIntent, "risk-concurrent-1", "200"), ready, start));
            var second = executor.submit(() -> submitAfterGate(
                    owner, paperCommand(secondIntent, "risk-concurrent-2", "200"), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(20, TimeUnit.SECONDS).paperResult() != null,
                    second.get(20, TimeUnit.SECONDS).paperResult() != null))
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(attemptRepository.count()).isEqualTo(1);
        assertThat(orderIntentRepository.findAll().stream()
                .map(OrderIntent::getStatus)
                .toList()).containsExactlyInAnyOrder(OrderIntentStatus.COMPLETED, OrderIntentStatus.BLOCKED);
    }

    @Test
    void paperLedgerFailureRollsBackFinalDecisionAndLeavesApproval() {
        var owner = owner();
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);
        assertThat(riskEngine.approve(command(owner, intentId, "100", T0)).approved()).isTrue();
        jdbc.execute("""
                CREATE FUNCTION fail_risk_paper_outbox()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced risk paper failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_fail_risk_paper_outbox
                BEFORE INSERT ON order_submission_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_risk_paper_outbox()
                """);

        assertThatThrownBy(() -> riskEngine.submitPaper(
                owner.userId(),
                owner.connectionId(),
                paperCommand(intentId, "risk-rollback", "100")))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("""
                SELECT count(*) FROM pre_trade_risk_decisions
                 WHERE order_intent_id = ? AND phase = 'APPROVAL'
                """, intentId)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM pre_trade_risk_decisions
                 WHERE order_intent_id = ? AND phase = 'FINAL'
                """, intentId)).isZero();
        assertThat(attemptRepository.count()).isZero();
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.APPROVED);
    }

    @Test
    void riskDecisionRowsAreAppendOnlyAtPostgresBoundary() {
        var owner = owner();
        successfulSnapshot(owner, "UNKNOWN", true, "1000", "1000", "MSFT", "100");
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);
        riskEngine.approve(command(owner, intentId, "100", T0));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE pre_trade_risk_decisions SET outcome = 'APPROVED' WHERE order_intent_id = ?",
                intentId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM pre_trade_risk_decisions WHERE order_intent_id = ?",
                intentId)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void crossOwnerCannotApproveOrSubmitAnotherOwnersIntent() {
        var owner = owner();
        var attacker = owner();
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        successfulSnapshot(attacker, "KNOWN", true, "1000", "1000", "MSFT", "100");
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);

        assertThatThrownBy(() -> riskEngine.approve(command(attacker, intentId, "100", T0)))
                .isInstanceOf(BrokerConnectionException.class);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(count("""
                SELECT count(*) FROM pre_trade_risk_decisions WHERE order_intent_id = ?
                """, intentId)).isZero();

        assertThat(riskEngine.approve(command(owner, intentId, "100", T0)).approved()).isTrue();
        assertThatThrownBy(() -> riskEngine.submitPaper(
                attacker.userId(),
                attacker.connectionId(),
                paperCommand(intentId, "risk-cross-owner", "100")))
                .isInstanceOf(BrokerConnectionException.class);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.APPROVED);
        assertThat(attemptRepository.count()).isZero();
    }

    @Test
    void perUserPolicyOverridesGlobalDefaultsAndIsRecordedOnTheDecision() {
        var owner = owner();
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        // Global default max-quantity is 5 (test class properties); a tighter per-user
        // policy of 2 must block a quantity-of-3 order the global default would approve.
        seedPolicy(owner.userId(), 1, "1000000", "1000", "2", "0.50");

        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "3", null);
        var decision = riskEngine.approve(command(owner, intentId, "1", T0));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reasons()).extracting(Enum::name).contains("MAX_QUANTITY_EXCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT risk_policy_version FROM pre_trade_risk_decisions WHERE order_intent_id = ?",
                Long.class, intentId)).isEqualTo(1L);
    }

    @Test
    void existingDecisionsKeepThePolicyVersionThatWasActiveWhenTheyWereMade() {
        var owner = owner();
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        // v1 is permissive enough to approve a quantity-1 order.
        seedPolicy(owner.userId(), 1, "1000000", "1000", "5", "0.50");
        var firstIntentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);
        var firstDecision = riskEngine.approve(command(owner, firstIntentId, "1", T0));
        assertThat(firstDecision.approved()).isTrue();

        // v2 is tight enough that the SAME quantity-1 order would now be blocked — proving
        // this is a real threshold change, not just a version-number bump.
        seedPolicy(owner.userId(), 2, "1000000", "1000", "0.5", "0.50");
        var secondIntentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);
        var secondDecision = riskEngine.approve(command(owner, secondIntentId, "1", T0));
        assertThat(secondDecision.approved()).isFalse();

        // The policy edit must not retroactively alter what was already decided under v1.
        assertThat(jdbc.queryForMap("""
                SELECT outcome, risk_policy_version FROM pre_trade_risk_decisions
                 WHERE order_intent_id = ?
                """, firstIntentId))
                .containsEntry("outcome", "APPROVED")
                .containsEntry("risk_policy_version", 1L);
        assertThat(jdbc.queryForMap("""
                SELECT outcome, risk_policy_version FROM pre_trade_risk_decisions
                 WHERE order_intent_id = ?
                """, secondIntentId))
                .containsEntry("outcome", "BLOCKED")
                .containsEntry("risk_policy_version", 2L);
    }

    @Test
    void decisionsWithNoCustomPolicyRecordVersionZero() {
        var owner = owner();
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);

        riskEngine.approve(command(owner, intentId, "1", T0));

        assertThat(jdbc.queryForObject(
                "SELECT risk_policy_version FROM pre_trade_risk_decisions WHERE order_intent_id = ?",
                Long.class, intentId)).isEqualTo(0L);
    }

    @Test
    void sellableInsufficientBlocksSellSubmissionWithoutCallingPaper() {
        var owner = owner();
        var runId = successfulSnapshot(owner, "KNOWN", true, "1000", "1000", null, null);
        insertSellablePosition(runId, owner, "AAPL", "1");
        var intentId = intent(owner, OrderSide.SELL, OrderType.MARKET, "AAPL", "2", null);
        assertThat(riskEngine.approve(command(owner, intentId, "100", T0)).approved()).isTrue();

        var result = riskEngine.submitPaper(
                owner.userId(), owner.connectionId(),
                paperCommand(intentId, "risk-sellable-low", "100"));

        assertThat(result.decision().approved()).isFalse();
        assertThat(result.decision().reasons())
                .contains(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_INSUFFICIENT);
        assertThat(result.paperResult()).isNull();
        assertThat(attemptRepository.count()).isZero();
        assertThat(status(intentId)).isEqualTo(OrderIntentStatus.BLOCKED);
        assertThat(terminalReason(intentId)).isEqualTo("SELLABLE_QUANTITY_INSUFFICIENT");
        assertThat(brokerAdapter.calls).hasValue(0);
    }

    @Test
    void sellableUnknownBlocksSellSubmissionAndIsNotTreatedAsZero() {
        var owner = owner();
        var runId = successfulSnapshot(owner, "KNOWN", true, "1000", "1000", null, null);
        // NULL sellable_quantity means the broker did not report it — UNKNOWN, not a confirmed zero.
        insertSellablePosition(runId, owner, "AAPL", null);
        var intentId = intent(owner, OrderSide.SELL, OrderType.MARKET, "AAPL", "2", null);
        assertThat(riskEngine.approve(command(owner, intentId, "100", T0)).approved()).isTrue();

        var result = riskEngine.submitPaper(
                owner.userId(), owner.connectionId(),
                paperCommand(intentId, "risk-sellable-unknown", "100"));

        assertThat(result.decision().approved()).isFalse();
        assertThat(result.decision().reasons())
                .contains(PreTradeRiskEngine.Reason.SELLABLE_QUANTITY_UNKNOWN);
        assertThat(result.paperResult()).isNull();
        assertThat(attemptRepository.count()).isZero();
        assertThat(status(intentId)).isEqualTo(OrderIntentStatus.BLOCKED);
        assertThat(terminalReason(intentId)).isEqualTo("SELLABLE_QUANTITY_UNKNOWN");
        assertThat(brokerAdapter.calls).hasValue(0);
    }

    @Test
    void sellableSufficientAllowsSellSubmissionWithNoRegression() {
        var owner = owner();
        var runId = successfulSnapshot(owner, "KNOWN", true, "1000", "1000", null, null);
        insertSellablePosition(runId, owner, "AAPL", "5");
        var intentId = intent(owner, OrderSide.SELL, OrderType.MARKET, "AAPL", "2", null);
        assertThat(riskEngine.approve(command(owner, intentId, "100", T0)).approved()).isTrue();

        var result = riskEngine.submitPaper(
                owner.userId(), owner.connectionId(),
                paperCommand(intentId, "risk-sellable-ok", "100"));

        assertThat(result.decision().approved()).isTrue();
        assertThat(result.paperResult()).isNotNull();
        assertThat(status(intentId)).isEqualTo(OrderIntentStatus.COMPLETED);
        assertThat(brokerAdapter.calls).hasValue(0);
    }

    @Test
    void sameSymbolOpenOrderBlocksNewSubmissionWithoutCallingPaper() {
        var owner = owner();
        successfulSnapshot(owner, "KNOWN", true, "1000", "1000", "MSFT", "100");
        insertOpenOrderIntent(owner, "AAPL");
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);
        assertThat(riskEngine.approve(command(owner, intentId, "100", T0)).approved()).isTrue();

        var result = riskEngine.submitPaper(
                owner.userId(), owner.connectionId(),
                paperCommand(intentId, "risk-open-order", "100"));

        assertThat(result.decision().approved()).isFalse();
        assertThat(result.decision().reasons())
                .contains(PreTradeRiskEngine.Reason.OPEN_ORDER_EXISTS);
        assertThat(result.paperResult()).isNull();
        assertThat(attemptRepository.count()).isZero();
        assertThat(status(intentId)).isEqualTo(OrderIntentStatus.BLOCKED);
        assertThat(terminalReason(intentId)).isEqualTo("OPEN_ORDER_EXISTS");
        assertThat(brokerAdapter.calls).hasValue(0);
    }

    private void insertSellablePosition(UUID runId, Owner owner, String symbol, String sellable) {
        jdbc.update("""
                INSERT INTO position_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, symbol, name,
                    market_country, quantity, currency, average_price, last_price,
                    purchase_amount, market_value_amount, market_value_after_cost,
                    profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                    profit_loss_rate_after_cost, daily_profit_loss_amount,
                    daily_profit_loss_rate, commission, tax, sellable_quantity,
                    observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, 'US', 1, 'USD', 1, 1,
                    1, 1, 1, 0, 0, 0, 0, 0, 0, 0, NULL, CAST(? AS numeric), ?, ?
                )
                """, UUID.randomUUID(), runId, owner.userId(), owner.connectionId(),
                symbol, symbol, sellable, at(T0), at(T0));
    }

    private void insertOpenOrderIntent(Owner owner, String symbol) {
        var accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        jdbc.update("""
                INSERT INTO order_intents (
                    id, broker_account_id, user_id, broker_connection_id, quantity,
                    side, order_type, symbol, trading_currency, status, version
                ) VALUES (?, ?, ?, ?, 1, 'BUY', 'MARKET', ?, 'USD', 'ACTIVE', 0)
                """, UUID.randomUUID(), accountId, owner.userId(), owner.connectionId(), symbol);
    }

    private OrderIntentStatus status(UUID intentId) {
        return orderIntentRepository.findById(intentId).orElseThrow().getStatus();
    }

    private String terminalReason(UUID intentId) {
        return jdbc.queryForObject(
                "SELECT terminal_reason FROM order_intents WHERE id = ?", String.class, intentId);
    }

    private void seedPolicy(
            UUID userId,
            long version,
            String maxOrderAmountKrw,
            String maxOrderAmountUsd,
            String maxQuantity,
            String maxConcentration
    ) {
        jdbc.update("""
                INSERT INTO risk_policies (
                    user_id, version, max_order_amount_krw, max_order_amount_usd,
                    max_quantity, max_concentration, updated_at, updated_by
                ) VALUES (?, ?, CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric),
                          CAST(? AS numeric), ?, 'test')
                ON CONFLICT (user_id) DO UPDATE SET
                    version = EXCLUDED.version,
                    max_order_amount_krw = EXCLUDED.max_order_amount_krw,
                    max_order_amount_usd = EXCLUDED.max_order_amount_usd,
                    max_quantity = EXCLUDED.max_quantity,
                    max_concentration = EXCLUDED.max_concentration,
                    updated_at = EXCLUDED.updated_at
                """, userId, version, maxOrderAmountKrw, maxOrderAmountUsd, maxQuantity,
                maxConcentration, at(T0));
    }

    private void assertBlocked(Owner owner, String reason) {
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, "AAPL", "1", null);
        var decision = riskEngine.approve(command(owner, intentId, "100", T0));
        assertThat(decision.approved()).isFalse();
        assertThat(decision.reasons()).extracting(Enum::name).contains(reason);
        assertThat(orderIntentRepository.findById(intentId).orElseThrow().getStatus())
                .isEqualTo(OrderIntentStatus.PROPOSED);
    }

    private void assertReason(Owner owner, String symbol, String quantity, String price, String reason) {
        var intentId = intent(owner, OrderSide.BUY, OrderType.MARKET, symbol, quantity, null);
        var decision = riskEngine.approve(command(owner, intentId, price, T0));
        assertThat(decision.approved()).isFalse();
        assertThat(decision.reasons()).extracting(Enum::name).contains(reason);
    }

    private PreTradeRiskEngine.SubmissionResult submitAfterGate(
            Owner owner,
            PaperTradingBroker.SubmitCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return riskEngine.submitPaper(owner.userId(), owner.connectionId(), command);
    }

    private PreTradeRiskEngine.ApprovalCommand command(
            Owner owner,
            UUID intentId,
            String referencePrice,
            Instant evaluatedAt
    ) {
        return new PreTradeRiskEngine.ApprovalCommand(
                owner.userId(),
                owner.connectionId(),
                intentId,
                new BigDecimal(referencePrice),
                evaluatedAt,
                "risk-test");
    }

    private PaperTradingBroker.SubmitCommand paperCommand(UUID intentId, String clientOrderId, String price) {
        return new PaperTradingBroker.SubmitCommand(
                intentId,
                clientOrderId,
                new BigDecimal(price),
                new BigDecimal("3"),
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED,
                T0.plusSeconds(1),
                "risk-test");
    }

    private Owner owner() {
        var owner = new Owner(UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO users (id) VALUES (?)", owner.userId());
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, owner.connectionId(), owner.userId(), new byte[17], new byte[12],
                at(T0), at(T0));
        return owner;
    }

    private UUID successfulSnapshot(
            Owner owner,
            String cashStatus,
            boolean includeUsd,
            String usdBuyingPower,
            String totalUsdValue,
            String positionSymbol,
            String positionValue
    ) {
        return successfulSnapshot(owner, cashStatus, includeUsd, usdBuyingPower,
                totalUsdValue, positionSymbol, positionValue, T0);
    }

    private UUID successfulSnapshot(
            Owner owner,
            String cashStatus,
            boolean includeUsd,
            String usdBuyingPower,
            String totalUsdValue,
            String positionSymbol,
            String positionValue,
            Instant syncBase
    ) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, owner.userId(), owner.connectionId(),
                at(syncBase), at(syncBase.plusSeconds(1)));
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
                    0, 0, 0, ?, ?, ?
                )
                """, UUID.randomUUID(), runId, owner.userId(), owner.connectionId(),
                totalUsdValue, cashStatus, at(T0), at(T0));
        if (positionSymbol != null) {
            jdbc.update("""
                    INSERT INTO position_snapshots (
                        id, sync_run_id, user_id, broker_connection_id, symbol, name,
                        market_country, quantity, currency, average_price, last_price,
                        purchase_amount, market_value_amount, market_value_after_cost,
                        profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                        profit_loss_rate_after_cost, daily_profit_loss_amount,
                        daily_profit_loss_rate, commission, tax, observed_at, created_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, 'US', 1, 'USD', 1, 1,
                        1, CAST(? AS numeric), CAST(? AS numeric), 0, 0, 0, 0, 0, 0, 0, NULL, ?, ?
                    )
                    """, UUID.randomUUID(), runId, owner.userId(), owner.connectionId(),
                    positionSymbol, positionSymbol, positionValue, positionValue, at(T0), at(T0));
        }
        capacity(runId, owner, "KRW", "1000000");
        if (includeUsd) {
            capacity(runId, owner, "USD", usdBuyingPower);
        }
        return runId;
    }

    private void failedRun(Owner owner, Instant startedAt) {
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, error_code, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'FAILED', 'BROKER_TEMPORARY', ?, ?)
                """, UUID.randomUUID(), owner.userId(), owner.connectionId(),
                at(startedAt), at(startedAt.plusSeconds(1)));
    }

    private void capacity(UUID runId, Owner owner, String currency, String amount) {
        jdbc.update("""
                INSERT INTO account_capacity_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, currency,
                    cash_buying_power, observed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS numeric), ?, ?)
                """, UUID.randomUUID(), runId, owner.userId(), owner.connectionId(),
                currency, amount, at(T0), at(T0));
    }

    private UUID intent(
            Owner owner,
            OrderSide side,
            OrderType type,
            String symbol,
            String quantity,
            String limitPrice
    ) {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(
                intentId,
                accountId,
                owner.userId(),
                owner.connectionId(),
                side,
                type,
                symbol,
                new BigDecimal(quantity),
                limitPrice == null ? null : new BigDecimal(limitPrice),
                Currency.USD));
        return intentId;
    }

    private List<String> decisions(UUID intentId) {
        return jdbc.query("""
                SELECT phase, outcome, reason_codes, snapshot_id
                  FROM pre_trade_risk_decisions
                 WHERE order_intent_id = ?
                """, (resultSet, rowNumber) -> String.join("|",
                resultSet.getString("phase"),
                resultSet.getString("outcome"),
                resultSet.getString("reason_codes"),
                resultSet.getObject("snapshot_id", UUID.class).toString()), intentId);
    }

    private long count(String sql, UUID intentId) {
        return jdbc.queryForObject(sql, Long.class, intentId);
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
            return new AssertionError("risk engine must not call Toss or another live broker");
        }
    }
}
