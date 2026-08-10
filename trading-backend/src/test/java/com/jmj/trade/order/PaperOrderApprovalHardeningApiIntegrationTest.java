package com.jmj.trade.order;

import com.jayway.jsonpath.JsonPath;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 승인 계약 강화 통합 테스트 (플랜 원장 E2): 표시값 대조, step-up 재인증, 철회.
 */
@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "pre-trade-risk.max-order-amount.usd=1000",
                "pre-trade-risk.max-quantity=10",
                "pre-trade-risk.max-concentration=0.75",
                "paper-order.step-up.reauth-freshness=PT5M",
                "paper-order.step-up.token-ttl=PT2M",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import(PaperOrderWorkflowApiIntegrationTest.WorkflowBrokerConfiguration.class)
class PaperOrderApprovalHardeningApiIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant T0 = Instant.parse("2026-07-28T06:00:00Z");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PaperOrderWorkflowService workflow;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbc.execute("""
                TRUNCATE order_approval_step_up_tokens,
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
                         real_order_daily_reservations, real_order_account_allowlist,
                         live_order_operation_idempotency, order_intents,
                         broker_accounts,
                         account_capacity_snapshots,
                         position_snapshots,
                         account_snapshots,
                         account_sync_runs,
                         broker_connections,
                         users
                CASCADE
                """);
    }

    // TDD 1: 표시값 불일치(수량) → 409 + 서버 계산값 반환 + intent 상태 불변.
    @Test
    void quantityMismatchReturnsConflictWithServerValuesAndLeavesIntentUntouched() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");
        insertFreshToken("stepup", USER_ID, intentId);

        var body = mockMvc.perform(approve(intentId, "approve-qty", "stepup", "WEB", "2", "100"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_DISPLAY_MISMATCH"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andReturn().getResponse().getContentAsString();
        assertThat(numberOf(body, "serverQuantity")).isEqualByComparingTo("1");
        assertThat(numberOf(body, "serverMaxLoss")).isEqualByComparingTo("100");

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(count("SELECT count(*) FROM pre_trade_risk_decisions")).isZero();
        assertThat(count("""
                SELECT count(*) FROM paper_order_workflow_commands WHERE action = 'APPROVE'
                """)).isZero();
        // 부작용 없음: step-up 토큰도 소비되지 않았다.
        assertThat(count("""
                SELECT count(*) FROM order_approval_step_up_tokens WHERE consumed_at IS NOT NULL
                """)).isZero();
    }

    // TDD 2: 표시값 불일치(최대손실) → 409.
    @Test
    void maxLossMismatchReturnsConflict() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");
        insertFreshToken("stepup", USER_ID, intentId);

        var body = mockMvc.perform(approve(intentId, "approve-loss", "stepup", "WEB", "1", "90"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_DISPLAY_MISMATCH"))
                .andReturn().getResponse().getContentAsString();
        assertThat(numberOf(body, "serverMaxLoss")).isEqualByComparingTo("100");

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
    }

    // TDD 3: 표시값 누락 → 승인 거절(422), 기본값 대체 없음.
    @Test
    void missingDisplayedValuesAreRejectedWithoutDefaulting() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");
        insertFreshToken("stepup", USER_ID, intentId);

        mockMvc.perform(post("/api/v1/paper-orders/{id}/approve", intentId)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "approve-missing")
                        .header("X-Step-Up-Token", "stepup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WEB\",\"displayedCurrency\":\"USD\"}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(count("SELECT count(*) FROM pre_trade_risk_decisions")).isZero();
    }

    // TDD 4: 만료 토큰 → 401.
    @Test
    void expiredStepUpTokenIsUnauthorized() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");
        var now = Instant.now();
        insertToken("stepup", USER_ID, intentId, now.minusSeconds(600), now.minusSeconds(1), null);

        mockMvc.perform(approve(intentId, "approve-expired", "stepup", "WEB", "1", "100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_STEP_UP_REQUIRED"));

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
    }

    // TDD 5: 재사용 토큰 → 401.
    @Test
    void reusedStepUpTokenIsUnauthorized() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");
        var now = Instant.now();
        insertToken("stepup", USER_ID, intentId, now.minusSeconds(30), now.plusSeconds(120), now.minusSeconds(5));

        mockMvc.perform(approve(intentId, "approve-reused", "stepup", "WEB", "1", "100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_STEP_UP_REQUIRED"));

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
    }

    // TDD 6: 타 사용자 토큰 → 401.
    @Test
    void otherUserStepUpTokenIsUnauthorized() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var otherConnection = owner(OTHER_USER_ID);
        var intentId = propose(connectionId, "AAPL");
        var otherIntent = workflow.propose(
                OTHER_USER_ID, "other-proposal",
                PaperOrderWorkflowService.Channel.WEB, proposal(otherConnection, "AAPL")).id();
        var now = Instant.now();
        // 토큰은 OTHER_USER 의 주문에 바인딩된다. USER 가 자기 주문 승인에 쓰면 소비 조건 불일치.
        insertToken("stepup", OTHER_USER_ID, otherIntent, now.minusSeconds(30), now.plusSeconds(120), null);

        mockMvc.perform(approve(intentId, "approve-otheruser", "stepup", "WEB", "1", "100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_STEP_UP_REQUIRED"));

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
    }

    // TDD 7: 다른 주문에 바인딩된 토큰 → 401.
    @Test
    void tokenBoundToDifferentOrderIsUnauthorized() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");
        var otherIntent = propose(connectionId, "MSFT");
        var now = Instant.now();
        insertToken("stepup", USER_ID, otherIntent, now.minusSeconds(30), now.plusSeconds(120), null);

        mockMvc.perform(approve(intentId, "approve-otherorder", "stepup", "WEB", "1", "100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_STEP_UP_REQUIRED"));

        assertThat(workflow.read(USER_ID, intentId).status()).isEqualTo(OrderIntentStatus.PROPOSED);
    }

    // TDD 9: SUBMITTING 전 철회 성공, 전이 후 철회 실패.
    @Test
    void withdrawSucceedsBeforeSubmissionAndFailsAfter() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);

        var withdrawable = propose(connectionId, "AAPL");
        mockMvc.perform(post("/api/v1/paper-orders/{id}/withdraw", withdrawable)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "withdraw-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WEB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.terminalReason").value("USER_WITHDRAWN"));

        var submitted = propose(connectionId, "MSFT");
        insertFreshToken("stepup", USER_ID, submitted);
        mockMvc.perform(approve(submitted, "approve-submitted", "stepup", "WEB", "1", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/paper-orders/{id}/withdraw", submitted)
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", "withdraw-late")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WEB\"}"))
                .andExpect(status().isConflict());
    }

    // TDD 10: 승인 성공 경로가 B3 재검증(FINAL 단계)을 그대로 거친다.
    @Test
    void successfulApprovalPassesThroughFinalRevalidation() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");
        insertFreshToken("stepup", USER_ID, intentId);

        mockMvc.perform(approve(intentId, "approve-final", "stepup", "WEB", "1", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(jdbc.queryForList("""
                SELECT phase FROM pre_trade_risk_decisions
                 WHERE order_intent_id = ? ORDER BY created_at
                """, String.class, intentId)).containsExactly("APPROVAL", "FINAL");
    }

    // TDD 11: 토큰 원문이 저장(해시만)·명령·감사 어디에도 남지 않는다(SPEC:1151).
    @Test
    void rawStepUpTokenNeverAppearsInStorageCommandsOrAudit() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");
        var raw = "super-secret-step-up-value";
        insertFreshToken(raw, USER_ID, intentId);

        mockMvc.perform(approve(intentId, "approve-secret", raw, "WEB", "1", "100"))
                .andExpect(status().isOk());

        var stored = jdbc.queryForObject(
                "SELECT token_hash FROM order_approval_step_up_tokens WHERE order_intent_id = ?",
                String.class, intentId);
        assertThat(stored)
                .isEqualTo(OrderApprovalStepUpService.sha256Hex(raw))
                .isNotEqualTo(raw);
        assertThat(count("""
                SELECT count(*) FROM paper_order_workflow_commands
                 WHERE request_fingerprint LIKE ? OR actor LIKE ? OR idempotency_key LIKE ?
                """, "%" + raw + "%", "%" + raw + "%", "%" + raw + "%")).isZero();
        assertThat(count("""
                SELECT count(*) FROM order_intent_audit_logs
                 WHERE actor LIKE ? OR COALESCE(terminal_reason, '') LIKE ?
                """, "%" + raw + "%", "%" + raw + "%")).isZero();
    }

    // step-up 발급: 최근 재인증(auth_time)만 근거. 신선하면 토큰이 나오고 그 토큰으로 승인된다.
    @Test
    void freshReauthenticationIssuesUsableStepUpToken() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");

        var body = mockMvc.perform(post("/api/v1/paper-orders/{id}/step-up", intentId)
                        .with(oidcLogin().oidcUser(oidcUser(USER_ID, Instant.now())))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepUpToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        var token = body.substring(
                body.indexOf("\"stepUpToken\":\"") + 15,
                body.indexOf("\"", body.indexOf("\"stepUpToken\":\"") + 15));

        mockMvc.perform(approve(intentId, "approve-issued", token, "WEB", "1", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // step-up 발급: auth_time 이 신선도 창을 벗어나면 401(재인증 요구).
    @Test
    void staleReauthenticationCannotIssueStepUpToken() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");

        mockMvc.perform(post("/api/v1/paper-orders/{id}/step-up", intentId)
                        .with(oidcLogin().oidcUser(oidcUser(USER_ID, Instant.now().minus(Duration.ofMinutes(10)))))
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_STEP_UP_REQUIRED"));

        assertThat(count("SELECT count(*) FROM order_approval_step_up_tokens")).isZero();
    }

    // step-up 발급: auth_time 자체가 없으면(증명 불가) fail-closed 401.
    @Test
    void absentAuthTimeCannotIssueStepUpToken() throws Exception {
        var connectionId = owner(USER_ID);
        successfulSnapshot(USER_ID, connectionId);
        var intentId = propose(connectionId, "AAPL");

        mockMvc.perform(post("/api/v1/paper-orders/{id}/step-up", intentId)
                        .with(oidcLogin().oidcUser(oidcUser(USER_ID, null)))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(count("SELECT count(*) FROM order_approval_step_up_tokens")).isZero();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private OidcUser oidcUser(UUID userId, Instant authTime) {
        var issuedAt = Instant.now().minusSeconds(30);
        var claims = new HashMap<String, Object>();
        claims.put("sub", userId.toString());
        if (authTime != null) {
            claims.put("auth_time", authTime.getEpochSecond());
        }
        var idToken = new OidcIdToken("id-token", issuedAt, issuedAt.plusSeconds(3600), claims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, "sub");
    }

    private org.springframework.test.web.servlet.RequestBuilder approve(
            UUID intentId,
            String key,
            String token,
            String channel,
            String quantity,
            String maxLoss
    ) {
        return post("/api/v1/paper-orders/{id}/approve", intentId)
                .with(user(USER_ID.toString()))
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("X-Step-Up-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "channel":"%s",
                          "displayedQuantity":%s,
                          "displayedMaxLoss":%s,
                          "displayedCurrency":"USD",
                          "proposalVersion":null
                        }
                        """.formatted(channel, quantity, maxLoss));
    }

    private UUID propose(UUID connectionId, String symbol) {
        return workflow.propose(
                USER_ID,
                "proposal-" + symbol + "-" + UUID.randomUUID(),
                PaperOrderWorkflowService.Channel.WEB,
                proposal(connectionId, symbol)).id();
    }

    private PaperOrderWorkflowService.ProposeCommand proposal(UUID connectionId, String symbol) {
        return new PaperOrderWorkflowService.ProposeCommand(
                connectionId, OrderSide.BUY, OrderType.MARKET, symbol,
                BigDecimal.ONE, null, Currency.USD);
    }

    private void insertFreshToken(String raw, UUID userId, UUID intentId) {
        var now = Instant.now();
        insertToken(raw, userId, intentId, now.minusSeconds(30), now.plusSeconds(120), null);
    }

    private void insertToken(
            String raw,
            UUID userId,
            UUID intentId,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt
    ) {
        jdbc.update("""
                INSERT INTO order_approval_step_up_tokens (
                    token_hash, user_id, order_intent_id, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                OrderApprovalStepUpService.sha256Hex(raw),
                userId,
                intentId,
                at(issuedAt),
                at(expiresAt),
                consumedAt == null ? null : at(consumedAt));
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
        var observedAt = Instant.now();
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, userId, connectionId, at(observedAt), at(observedAt.plusSeconds(1)));
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
                """, UUID.randomUUID(), runId, userId, connectionId, at(observedAt), at(observedAt));
        for (var currency : List.of("KRW", "USD")) {
            jdbc.update("""
                    INSERT INTO account_capacity_snapshots (
                        id, sync_run_id, user_id, broker_connection_id, currency,
                        cash_buying_power, observed_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, 1000, ?, ?)
                    """, UUID.randomUUID(), runId, userId, connectionId, currency,
                    at(observedAt), at(observedAt));
        }
    }

    private static BigDecimal numberOf(String jsonBody, String field) {
        return new BigDecimal(JsonPath.parse(jsonBody).read("$." + field).toString());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
