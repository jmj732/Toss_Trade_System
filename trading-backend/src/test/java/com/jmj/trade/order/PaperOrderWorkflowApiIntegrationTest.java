package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.SellableQuantitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "pre-trade-risk.max-order-amount.usd=1000",
                "pre-trade-risk.max-quantity=10",
                "pre-trade-risk.max-concentration=0.75",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import(PaperOrderWorkflowApiIntegrationTest.WorkflowBrokerConfiguration.class)
class PaperOrderWorkflowApiIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    // 스냅샷 신선도(portfolio.snapshot.max-age, 기본 PT15M)가 실제 시각 기준으로 판정되므로,
    // 성공 스냅샷이 나이만으로 STALE_SNAPSHOT 처리되지 않도록 픽스처 기준 시각을 현재 기준
    // 상대값으로 고정한다.
    private static final Instant T0 = Instant.now().minusSeconds(30);

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PaperOrderWorkflowService workflow;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkflowBrokerAdapter broker;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_workflow_paper_outbox ON order_submission_outbox_events");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_workflow_paper_outbox()");
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
                         account_capacity_snapshots,
                         position_snapshots,
                         account_snapshots,
                         account_sync_runs,
                         broker_connections,
                         users
                CASCADE
                """);
        broker.reset();
    }

    @Test
    void webAndMessengerShareProposalCommandWithOwnedIdempotentReadModel() throws Exception {
        var connectionId = owner(USER_ID);
        owner(OTHER_USER_ID);
        var request = proposalJson(connectionId, "AAPL", "MESSENGER");

        var created = mockMvc.perform(post("/api/v1/paper-orders")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "message-100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.commands[0].channel").value("MESSENGER"))
                .andExpect(jsonPath("$.commands[0].actor").value(USER_ID.toString()))
                .andReturn().getResponse().getContentAsString();
        var intentId = UUID.fromString(created.substring(
                created.indexOf("\"id\":\"") + 6,
                created.indexOf("\"", created.indexOf("\"id\":\"") + 6)));

        mockMvc.perform(post("/api/v1/paper-orders")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "message-100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(intentId.toString()));

        mockMvc.perform(get("/api/v1/paper-orders/{id}", intentId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.riskDecisions").isEmpty())
                .andExpect(jsonPath("$.statusAudit").isEmpty());
        mockMvc.perform(get("/api/v1/paper-orders/{id}", intentId)
                        .with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isNotFound());

        assertThat(count("SELECT count(*) FROM order_intents")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM paper_order_workflow_commands")).isEqualTo(1);
    }

    @Test
    void approvalUsesServerQuoteThenRiskRevalidatesAndExecutesPaperWithoutDirectSubmitApi() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-approve",
                PaperOrderWorkflowService.Channel.WEB,
                proposal(connectionId, "AAPL")).id();
        insertStepUpToken(intentId, "stepup-approve-100");

        mockMvc.perform(post("/api/v1/paper-orders/{id}/approve", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "approve-100")
                        .header("X-Step-Up-Token", "stepup-approve-100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveJson("MESSENGER", "1", "100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.riskDecisions.length()").value(2))
                .andExpect(jsonPath("$.riskDecisions[0].snapshotId").isNotEmpty())
                .andExpect(jsonPath("$.statusAudit.length()").value(5));

        mockMvc.perform(post("/api/v1/paper-orders/{id}/approve", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "approve-100")
                        .header("X-Step-Up-Token", "stepup-approve-100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveJson("MESSENGER", "1", "100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(post("/api/v1/paper-orders/{id}/submit", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(broker.quoteCalls).hasValue(1);
        assertThat(jdbc.queryForList("""
                SELECT phase FROM pre_trade_risk_decisions
                 WHERE order_intent_id = ? ORDER BY created_at
                """, String.class, intentId)).containsExactly("APPROVAL", "FINAL");
        assertThat(jdbc.queryForObject("""
                SELECT actor FROM order_intent_audit_logs
                 WHERE order_intent_id = ? ORDER BY occurred_at LIMIT 1
                """, String.class, intentId)).isEqualTo("MESSENGER:" + USER_ID);
    }

    @Test
    void messengerRejectionMapsToProposalCancelAndBlocksApproval() throws Exception {
        var connectionId = owner(USER_ID);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-cancel",
                PaperOrderWorkflowService.Channel.MESSENGER,
                proposal(connectionId, "AAPL")).id();

        mockMvc.perform(post("/api/v1/paper-orders/{id}/cancel", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "reject-100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"MESSENGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.terminalReason").value("USER_REJECTED"));

        mockMvc.perform(post("/api/v1/paper-orders/{id}/approve", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "approve-canceled")
                        .header("X-Step-Up-Token", "unused-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveJson("WEB", "1", "100")))
                .andExpect(status().isConflict());
        assertThat(broker.quoteCalls).hasValue(0);
    }

    @Test
    void inactiveConnectionIsRejectedBeforeServerQuote() {
        var connectionId = owner(USER_ID);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-inactive",
                PaperOrderWorkflowService.Channel.WEB,
                proposal(connectionId, "AAPL")).id();
        jdbc.update("""
                UPDATE broker_connections
                   SET status = 'DELETED',
                       credential_ciphertext = NULL,
                       credential_nonce = NULL,
                       credential_key_version = NULL,
                       deleted_at = ?
                 WHERE id = ?
                """, at(T0.plusSeconds(1)), connectionId);

        assertThatThrownBy(() -> workflow.approve(
                USER_ID,
                intentId,
                "approve-inactive",
                approveCommand(PaperOrderWorkflowService.Channel.WEB, "unused-token")))
                .isInstanceOf(PaperOrderWorkflowException.class)
                .satisfies(exception -> assertThat(((PaperOrderWorkflowException) exception).code())
                        .isEqualTo(PaperOrderWorkflowException.Code.NOT_FOUND));
        assertThat(broker.quoteCalls).hasValue(0);
    }

    @Test
    void commandLedgerRejectsMismatchedIntentOwner() {
        var connectionId = owner(USER_ID);
        owner(OTHER_USER_ID);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-owner",
                PaperOrderWorkflowService.Channel.WEB,
                proposal(connectionId, "AAPL")).id();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO paper_order_workflow_commands (
                    user_id, idempotency_key, order_intent_id, action,
                    channel, actor, request_fingerprint, created_at
                ) VALUES (?, 'wrong-owner', ?, 'APPROVE', 'WEB', ?, 'approve', ?)
                """, OTHER_USER_ID, intentId, OTHER_USER_ID.toString(), at(T0)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentCommand() throws Exception {
        var connectionId = owner(USER_ID);

        mockMvc.perform(post("/api/v1/paper-orders")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalJson(connectionId, "AAPL", "WEB")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/paper-orders")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalJson(connectionId, "MSFT", "WEB")))
                .andExpect(status().isConflict());

        assertThat(count("SELECT count(*) FROM order_intents")).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsExecuteExactlyOnce() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-concurrent",
                PaperOrderWorkflowService.Channel.API,
                proposal(connectionId, "AAPL")).id();
        insertStepUpToken(intentId, "stepup-a");
        insertStepUpToken(intentId, "stepup-b");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> approveAfterGate(intentId, "approve-a", "stepup-a", ready, start));
            var second = executor.submit(() -> approveAfterGate(intentId, "approve-b", "stepup-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var outcomes = List.of(outcome(first), outcome(second));
            assertThat(outcomes).containsExactlyInAnyOrder("COMPLETED", "CONFLICT");
        }

        assertThat(count("SELECT count(*) FROM submission_attempts")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM paper_order_workflow_commands WHERE action = 'APPROVE'
                """)).isEqualTo(1);
    }

    @Test
    void paperFailureRollsBackApprovalCommandRiskAndState() {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-rollback",
                PaperOrderWorkflowService.Channel.WEB,
                proposal(connectionId, "AAPL")).id();
        jdbc.execute("""
                CREATE FUNCTION fail_workflow_paper_outbox()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced workflow paper failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_fail_workflow_paper_outbox
                BEFORE INSERT ON order_submission_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_workflow_paper_outbox()
                """);

        insertStepUpToken(intentId, "stepup-rollback");
        assertThatThrownBy(() -> workflow.approve(
                USER_ID,
                intentId,
                "approve-rollback",
                approveCommand(PaperOrderWorkflowService.Channel.WEB, "stepup-rollback")))
                .isInstanceOf(RuntimeException.class);

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(count("""
                SELECT count(*) FROM pre_trade_risk_decisions WHERE order_intent_id = ?
                """, intentId)).isZero();
        assertThat(count("""
                SELECT count(*) FROM paper_order_workflow_commands
                 WHERE order_intent_id = ? AND action = 'APPROVE'
                """, intentId)).isZero();
        assertThat(count("SELECT count(*) FROM submission_attempts")).isZero();
    }

    @Test
    void proposalStampsCreatedAtAndExpiryFromConfiguredTtl() {
        var connectionId = owner(USER_ID);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-ttl",
                PaperOrderWorkflowService.Channel.WEB,
                proposal(connectionId, "AAPL")).id();

        var createdAt = instantColumn(intentId, "created_at");
        var expiresAt = instantColumn(intentId, "expires_at");
        assertThat(createdAt).isNotNull();
        assertThat(expiresAt).isNotNull();
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void approvingAfterExpiryIsRejectedButCancelStillSucceeds() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-expired",
                PaperOrderWorkflowService.Channel.WEB,
                proposal(connectionId, "AAPL")).id();
        jdbc.update("UPDATE order_intents SET expires_at = ? WHERE id = ?",
                at(Instant.now().minusSeconds(5)), intentId);
        insertStepUpToken(intentId, "stepup-expired");

        mockMvc.perform(post("/api/v1/paper-orders/{id}/approve", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "approve-expired")
                        .header("X-Step-Up-Token", "stepup-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveJson("WEB", "1", "100")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_PROPOSAL_EXPIRED"));

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(count("""
                SELECT count(*) FROM paper_order_workflow_commands
                 WHERE order_intent_id = ? AND action = 'APPROVE'
                """, intentId)).isZero();

        mockMvc.perform(post("/api/v1/paper-orders/{id}/cancel", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "cancel-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WEB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void legacyProposalWithNullExpiryRemainsApprovable() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = workflow.propose(
                USER_ID,
                "proposal-null-expiry",
                PaperOrderWorkflowService.Channel.WEB,
                proposal(connectionId, "AAPL")).id();
        jdbc.update("UPDATE order_intents SET expires_at = NULL, created_at = NULL WHERE id = ?", intentId);
        insertStepUpToken(intentId, "stepup-null-expiry");

        mockMvc.perform(post("/api/v1/paper-orders/{id}/approve", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "approve-null-expiry")
                        .header("X-Step-Up-Token", "stepup-null-expiry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveJson("WEB", "1", "100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    private Instant instantColumn(UUID intentId, String column) {
        return jdbc.query(
                        "SELECT " + column + " AS value FROM order_intents WHERE id = ?",
                        (resultSet, rowNumber) -> resultSet.getObject("value", OffsetDateTime.class),
                        intentId)
                .stream().findFirst().map(OffsetDateTime::toInstant).orElse(null);
    }

    // --- BC-6: 비영속 사전 위험 미리보기 ---------------------------------------------------

    @Test
    void previewEvaluatesServerRiskWithoutPersistingAnything() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);

        mockMvc.perform(post("/api/v1/paper-orders/preview")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewJson(connectionId, "AAPL", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.reasons").isEmpty())
                .andExpect(jsonPath("$.orderAmount").value(100))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.riskPolicyVersion").value(0))
                .andExpect(jsonPath("$.snapshotId").isNotEmpty())
                .andExpect(jsonPath("$.evaluatedAt").isNotEmpty());

        assertPreviewPersistedNothing();
    }

    @Test
    void previewBlocksOverLimitOrderWithServerReasonsAndPersistsNothing() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);

        // 정책 한도: 수량 10, USD 주문금액 1000, 집중도 0.75. 11주 × 100 = 1100 은 네 항목 모두 위반한다.
        mockMvc.perform(post("/api/v1/paper-orders/preview")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewJson(connectionId, "AAPL", "11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.orderAmount").value(1100))
                .andExpect(jsonPath("$.reasons", containsInAnyOrder(
                        "MAX_QUANTITY_EXCEEDED",
                        "MAX_ORDER_AMOUNT_EXCEEDED",
                        "BUYING_POWER_EXCEEDED",
                        "CONCENTRATION_EXCEEDED")));

        assertPreviewPersistedNothing();
    }

    @Test
    void previewRefusesAnotherUsersConnectionBeforeTouchingTheBroker() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        owner(OTHER_USER_ID);

        mockMvc.perform(post("/api/v1/paper-orders/preview")
                        .with(user(OTHER_USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewJson(connectionId, "AAPL", "1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_NOT_FOUND"));

        // 소유권 검사는 시세 조회보다 먼저다 — 남의 연결로 브로커를 호출하지 않는다.
        assertThat(broker.quoteCalls()).isZero();
        assertPreviewPersistedNothing();
    }

    @Test
    void previewNeverIssuesOrRequiresStepUpToken() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);

        mockMvc.perform(post("/api/v1/paper-orders/preview")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewJson(connectionId, "AAPL", "1")))
                .andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM order_approval_step_up_tokens")).isZero();
    }

    private void assertPreviewPersistedNothing() {
        assertThat(count("SELECT count(*) FROM order_intents")).isZero();
        assertThat(count("SELECT count(*) FROM pre_trade_risk_decisions")).isZero();
        assertThat(count("SELECT count(*) FROM order_intent_audit_logs")).isZero();
        assertThat(count("SELECT count(*) FROM order_intent_outbox_events")).isZero();
        assertThat(count("SELECT count(*) FROM order_submission_outbox_events")).isZero();
        assertThat(count("SELECT count(*) FROM order_submission_audit_logs")).isZero();
        assertThat(count("SELECT count(*) FROM paper_order_workflow_commands")).isZero();
        assertThat(count("SELECT count(*) FROM submission_attempts")).isZero();
        assertThat(count("SELECT count(*) FROM submission_idempotency_keys")).isZero();
        assertThat(count("SELECT count(*) FROM broker_orders")).isZero();
        assertThat(count("SELECT count(*) FROM broker_accounts")).isZero();
        assertThat(count("SELECT count(*) FROM notification_outbox_events")).isZero();
    }

    private String previewJson(UUID connectionId, String symbol, String quantity) {
        return """
                {
                  "connectionId":"%s",
                  "side":"BUY",
                  "type":"MARKET",
                  "symbol":"%s",
                  "quantity":%s,
                  "limitPrice":null,
                  "currency":"USD"
                }
                """.formatted(connectionId, symbol, quantity);
    }

    private PaperOrderWorkflowService.OrderView approveAfterGate(
            UUID intentId,
            String key,
            String stepUpToken,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return workflow.approve(
                USER_ID,
                intentId,
                key,
                approveCommand(PaperOrderWorkflowService.Channel.API, stepUpToken));
    }

    private PaperOrderWorkflowService.ApproveCommand approveCommand(
            PaperOrderWorkflowService.Channel channel,
            String stepUpToken
    ) {
        return new PaperOrderWorkflowService.ApproveCommand(
                channel, BigDecimal.ONE, new BigDecimal("100"), Currency.USD, stepUpToken, null);
    }

    private String approveJson(String channel, String quantity, String maxLoss) {
        return """
                {
                  "channel":"%s",
                  "displayedQuantity":%s,
                  "displayedMaxLoss":%s,
                  "displayedCurrency":"USD",
                  "proposalVersion":null
                }
                """.formatted(channel, quantity, maxLoss);
    }

    private void insertStepUpToken(UUID intentId, String rawToken) {
        var now = Instant.now();
        jdbc.update("""
                INSERT INTO order_approval_step_up_tokens (
                    token_hash, user_id, order_intent_id, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, ?, NULL)
                """,
                OrderApprovalStepUpService.sha256Hex(rawToken),
                USER_ID,
                intentId,
                at(now.minusSeconds(1)),
                at(now.plusSeconds(300)));
    }

    private static String outcome(java.util.concurrent.Future<PaperOrderWorkflowService.OrderView> future)
            throws Exception {
        try {
            return future.get(20, TimeUnit.SECONDS).status().name();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof PaperOrderWorkflowException workflowException
                    && workflowException.code() == PaperOrderWorkflowException.Code.CONFLICT) {
                return "CONFLICT";
            }
            throw exception;
        }
    }

    private PaperOrderWorkflowService.ProposeCommand proposal(UUID connectionId, String symbol) {
        return new PaperOrderWorkflowService.ProposeCommand(
                connectionId,
                OrderSide.BUY,
                OrderType.MARKET,
                symbol,
                BigDecimal.ONE,
                null,
                Currency.USD);
    }

    private String proposalJson(UUID connectionId, String symbol, String channel) {
        return """
                {
                  "connectionId":"%s",
                  "side":"BUY",
                  "type":"MARKET",
                  "symbol":"%s",
                  "quantity":1,
                  "limitPrice":null,
                  "currency":"USD",
                  "channel":"%s"
                }
                """.formatted(connectionId, symbol, channel);
    }

    private UUID owner(UUID userId) {
        var connectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], at(T0), at(T0));
        return connectionId;
    }

    private void successfulSnapshot(UUID userId, UUID connectionId) {
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
                    '{}'::jsonb, '{"USD":100}'::jsonb, '{}'::jsonb,
                    '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                    0, 0, 0, 'KNOWN', ?, ?
                )
                """, UUID.randomUUID(), runId, userId, connectionId, at(T0), at(T0));
        for (var currency : List.of("KRW", "USD")) {
            jdbc.update("""
                    INSERT INTO account_capacity_snapshots (
                        id, sync_run_id, user_id, broker_connection_id, currency,
                        cash_buying_power, observed_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, 1000, ?, ?)
                    """, UUID.randomUUID(), runId, userId, connectionId, currency, at(T0), at(T0));
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration
    static class WorkflowBrokerConfiguration {

        @Bean
        WorkflowBrokerAdapter brokerAdapter() {
            return new WorkflowBrokerAdapter();
        }
    }

    static final class WorkflowBrokerAdapter implements BrokerAdapter {
        private final AtomicInteger quoteCalls = new AtomicInteger();

        void reset() {
            quoteCalls.set(0);
        }

        int quoteCalls() {
            return quoteCalls.get();
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            quoteCalls.incrementAndGet();
            var metadata = new BrokerCallMetadata("quote-1", T0, Optional.empty());
            return new BrokerResponse<>(
                    new Quote(
                            connection,
                            symbol,
                            Currency.USD,
                            new BigDecimal("100"),
                            new BigDecimal("99"),
                            new BigDecimal("100"),
                            T0,
                            T0),
                    metadata);
        }

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            throw new AssertionError("workflow must not list live accounts");
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            throw new AssertionError("workflow must not read a live account");
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            throw new AssertionError("workflow must not read live positions");
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(
                BrokerAccountRef account,
                Currency currency
        ) {
            throw new AssertionError("workflow must not read live buying power");
        }

        @Override
        public BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol) {
            throw new AssertionError("workflow must not read live sellable quantity");
        }
    }
}
