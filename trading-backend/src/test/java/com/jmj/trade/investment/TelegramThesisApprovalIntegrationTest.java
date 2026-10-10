package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.investment.tactical.TacticalOverlayService;
import com.jmj.trade.notification.TelegramInteractiveClient;
import com.jmj.trade.notification.TelegramInteractiveClient.Button;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TradingBackendApplication.class, properties = {
        "notification.telegram.enabled=true",
        "notification.telegram.approval-enabled=true",
        "notification.telegram.bot-token=test-only-bot-token",
        "notification.telegram.chat-id=777000",
        "notification.telegram.user-id=" + TelegramThesisApprovalIntegrationTest.USER_ID,
        "notification.telegram.approver-id=4242",
        "notification.telegram.webhook-secret=" + TelegramThesisApprovalIntegrationTest.SECRET,
        // The delivery scheduler exists when telegram is enabled; keep it from ever polling during tests.
        "notification.telegram.initial-delay=PT24H",
        "spring.datasource.hikari.maximum-pool-size=4"
})
class TelegramThesisApprovalIntegrationTest extends PostgresIntegrationTest {

    static final String USER_ID = "11990000-0000-7000-8000-0000000000c1";
    static final String SECRET = "test_webhook-secret_01";
    private static final UUID USER = UUID.fromString(USER_ID);
    private static final UUID OTHER = UUID.fromString("11990000-0000-7000-8000-0000000000c2");
    private static final long CHAT = 777000L;
    private static final long APPROVER = 4242L;
    private static final String TICKER = "ACME";
    private static final String WEBHOOK = "/api/v1/telegram/webhook";
    private static final List<String> ORDER_TABLES = List.of("order_intents", "broker_orders",
            "order_intent_outbox_events", "order_submission_outbox_events", "paper_order_workflow_commands",
            "real_order_canary_runs");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InvestmentContextService investment;
    @Autowired private TelegramApprovalService approvals;
    @Autowired private TacticalOverlayService tactical;
    @MockitoBean private TelegramInteractiveClient client;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong messageIds = new AtomicLong(1000);
    private final AtomicLong updateIds = new AtomicLong(5000);
    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    private MockMvc mvc;

    record Sent(long messageId, String text, List<List<Button>> keyboard) {
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.execute("TRUNCATE telegram_webhook_updates, users CASCADE");
        jdbc.update("INSERT INTO users(id) VALUES (?), (?)", USER, OTHER);
        sent.clear();
        investment.putThesisProposal(USER, TICKER, thesisInput("Core thesis", "AI_PROPOSED", bd("90")), null);
        when(client.sendMessage(anyString(), anyList())).thenAnswer(invocation -> {
            var id = messageIds.incrementAndGet();
            @SuppressWarnings("unchecked")
            List<List<Button>> keyboard = invocation.getArgument(1);
            sent.add(new Sent(id, invocation.getArgument(0), keyboard));
            return id;
        });
    }

    // ------------------------------------------------------------------ webhook authentication

    @Test
    void webhookRejectsMissingSecretWith401AndWrongSecretWith403WithoutAnyEffect() throws Exception {
        var token = createProposalRequest();
        clearInvocations(client);
        var body = callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token);

        mvc.perform(post(WEBHOOK).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(WEBHOOK).header("X-Telegram-Bot-Api-Secret-Token", SECRET + "x")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());

        assertThat(requestStatus()).isEqualTo("PENDING");
        assertThat(count("telegram_webhook_updates")).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void wrongChatOrWrongSenderChangesNothingAndMakesNoOutboundCall() throws Exception {
        var token = createProposalRequest();
        clearInvocations(client);

        webhook(callbackBody(nextUpdate(), 999L, APPROVER, "A:" + token));
        webhook(callbackBody(nextUpdate(), CHAT, 1L, "A:" + token));
        webhook(callbackBody(nextUpdate(), -CHAT, APPROVER, "F:" + token));

        assertThat(requestStatus()).isEqualTo("PENDING");
        assertThat(count("telegram_webhook_updates")).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void nonCallbackAndMalformedUpdatesAreAcknowledgedAsNoOps() throws Exception {
        createProposalRequest();
        clearInvocations(client);

        webhook("{\"update_id\":" + nextUpdate() + ",\"message\":{\"chat\":{\"id\":777000},\"text\":\"hi\"}}");
        webhook("{not json");
        webhook("{\"update_id\":" + nextUpdate() + ",\"callback_query\":{\"id\":\"q\",\"from\":{\"id\":4242},"
                + "\"data\":\"A:x\"}}"); // no message -> rejected

        assertThat(requestStatus()).isEqualTo("PENDING");
        assertThat(count("telegram_webhook_updates")).isZero();
        verifyNoInteractions(client);
    }

    // ------------------------------------------------------------------ 2-step approval

    @Test
    void singleApprovalOnlyAwaitsConfirmationAndLeavesThesisUntouched() throws Exception {
        var before = investment.currentThesis(USER, TICKER);
        var token = createProposalRequest();

        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));

        assertThat(requestStatus()).isEqualTo("AWAITING_CONFIRM");
        assertThat(investment.currentThesis(USER, TICKER)).isEqualTo(before);
        assertThat(count("investment_thesis_revisions")).isEqualTo(1);
        var prompt = sent.getLast();
        assertThat(prompt.keyboard().getFirst()).extracting(Button::text).containsExactly("최종 승인", "취소");
        verify(client).editMessageText(eq(sent.getFirst().messageId()), anyString());
    }

    @Test
    void duplicateUpdateIdIsIdempotent() throws Exception {
        var token = createProposalRequest();
        var update = nextUpdate();

        webhook(callbackBody(update, CHAT, APPROVER, "A:" + token));
        var confirmHash = jdbc.queryForObject(
                "SELECT confirm_token_sha256 FROM investment_thesis_approval_requests", String.class);
        clearInvocations(client);
        webhook(callbackBody(update, CHAT, APPROVER, "A:" + token));

        assertThat(requestStatus()).isEqualTo("AWAITING_CONFIRM");
        assertThat(jdbc.queryForObject("SELECT confirm_token_sha256 FROM investment_thesis_approval_requests",
                String.class)).isEqualTo(confirmHash);
        assertThat(count("telegram_webhook_updates")).isEqualTo(1);
        verifyNoInteractions(client);
    }

    @Test
    void finalApprovalConfirmsThesisWithStoredTriggerAndTelegramRevisionOnly() throws Exception {
        var before = investment.currentThesis(USER, TICKER);
        var token = createProposalRequest();
        var request = jdbc.queryForMap("SELECT id, candidate_trigger, source_as_of FROM investment_thesis_approval_requests");

        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "F:" + confirmToken()));

        assertThat(requestStatus()).isEqualTo("APPROVED");
        var after = investment.currentThesis(USER, TICKER);
        assertThat(after.invalidationStatus()).isEqualTo("CONFIRMED");
        assertThat(after.priceRiskTriggerPrice()).isEqualByComparingTo((BigDecimal) request.get("candidate_trigger"));
        assertThat(after).usingRecursiveComparison()
                .ignoringFields("invalidationStatus", "priceRiskTriggerPrice", "updatedAt").isEqualTo(before);
        assertThat(after.priceRiskTrigger()).isEqualTo(before.priceRiskTrigger());

        var revisions = investment.thesisRevisions(USER, TICKER, 10);
        assertThat(revisions).hasSize(2);
        var confirm = revisions.getFirst();
        assertThat(confirm.actorType()).isEqualTo("TELEGRAM");
        assertThat(confirm.previousStatus()).isEqualTo("AI_PROPOSED");
        assertThat(confirm.newStatus()).isEqualTo("CONFIRMED");
        assertThat(confirm.actorSessionId()).isNull();
        assertThat(confirm.sourceAsOf()).isEqualTo(((java.sql.Timestamp) request.get("source_as_of")).toInstant());
        assertThat(confirm.reason()).isEqualTo("TELEGRAM_APPROVAL request=" + request.get("id"));

        // Post-commit reply re-reads context; ACME is neither held nor watched.
        assertThat(sent.getLast().text()).contains("CONFIRMED").contains("리스크 미산출(보유·관심 대상 아님)");
        assertThat(sent.getLast().keyboard()).isEmpty();
        verify(client, atLeastOnce()).answerCallbackQuery(anyString(), anyString());
        assertNoOrders();
    }

    @Test
    void concurrentFinalApprovalsApproveExactlyOnce() throws Exception {
        var token = createProposalRequest();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));
        var confirm = confirmToken();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                var update = nextUpdate();
                futures.add(pool.submit(() -> {
                    start.await();
                    approvals.handleCallback(new TelegramApprovalService.Callback(
                            update, CHAT, APPROVER, "cbq-" + update, "F:" + confirm));
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(requestStatus()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions WHERE new_status='CONFIRMED'",
                Integer.class)).isEqualTo(1);
        assertThat(count("telegram_webhook_updates")).isEqualTo(3);
        assertThat(investment.currentThesis(USER, TICKER).invalidationStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void holdAtEitherStageEndsHeldWithoutTouchingThesis() throws Exception {
        var before = investment.currentThesis(USER, TICKER);
        var first = createProposalRequest();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "H:" + first));
        assertThat(requestStatus()).isEqualTo("HELD");
        // A later approval of the held request changes nothing.
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + first));
        assertThat(requestStatus()).isEqualTo("HELD");

        var second = createProposalRequest();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + second));
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "X:" + confirmToken()));
        assertThat(jdbc.queryForList("SELECT status FROM investment_thesis_approval_requests ORDER BY created_at",
                String.class)).containsExactly("HELD", "HELD");
        assertThat(jdbc.queryForList("SELECT status_reason FROM investment_thesis_approval_requests",
                String.class)).containsOnly("CANCELLED");
        assertThat(investment.currentThesis(USER, TICKER)).isEqualTo(before);
        assertThat(count("investment_thesis_revisions")).isEqualTo(1);
    }

    @Test
    void expiredRequestTransitionsToExpiredWithoutWrite() throws Exception {
        var before = investment.currentThesis(USER, TICKER);
        var token = createProposalRequest();
        jdbc.update("UPDATE investment_thesis_approval_requests SET expires_at = now() - interval '1 minute'");

        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));

        assertThat(requestStatus()).isEqualTo("EXPIRED");
        assertThat(investment.currentThesis(USER, TICKER)).isEqualTo(before);
    }

    @Test
    void expiredConfirmTokenNeverWritesThesis() throws Exception {
        var before = investment.currentThesis(USER, TICKER);
        var token = createProposalRequest();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));
        jdbc.update("UPDATE investment_thesis_approval_requests SET confirm_expires_at = now() - interval '1 second'");

        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "F:" + confirmToken()));

        assertThat(requestStatus()).isEqualTo("EXPIRED");
        assertThat(investment.currentThesis(USER, TICKER)).isEqualTo(before);
        assertThat(count("investment_thesis_revisions")).isEqualTo(1);
    }

    @Test
    void thesisChangedAfterRequestEndsInPersistedConflictWithoutWrite() throws Exception {
        var token = createProposalRequest();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));
        var current = investment.currentThesis(USER, TICKER);
        investment.putThesisProposal(USER, TICKER, thesisInput("Revised core thesis", "UNVERIFIED", bd("88")),
                current.updatedAt());
        var changed = investment.currentThesis(USER, TICKER);

        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "F:" + confirmToken()));

        assertThat(requestStatus()).isEqualTo("CONFLICT");
        assertThat(jdbc.queryForObject("SELECT status_reason FROM investment_thesis_approval_requests", String.class))
                .isEqualTo("THESIS_CHANGED");
        assertThat(investment.currentThesis(USER, TICKER)).isEqualTo(changed);
        assertThat(count("investment_thesis_revisions")).isEqualTo(2);
        assertThat(count("telegram_webhook_updates")).isEqualTo(2);
    }

    @Test
    void newRequestSupersedesOpenRequestAndOldButtonsDie() throws Exception {
        var first = createProposalRequest();
        var firstMessage = sent.getLast().messageId();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + first));
        var oldConfirm = confirmToken();
        var confirmMessage = sent.getLast().messageId();
        clearInvocations(client);
        var second = createProposalRequest();

        assertThat(jdbc.queryForList("SELECT status FROM investment_thesis_approval_requests ORDER BY created_at",
                String.class)).containsExactly("SUPERSEDED", "PENDING");
        verify(client).editMessageText(eq(firstMessage), org.mockito.ArgumentMatchers.contains("대체"));
        verify(client).editMessageText(eq(confirmMessage), org.mockito.ArgumentMatchers.contains("대체"));

        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "F:" + oldConfirm));
        assertThat(investment.currentThesis(USER, TICKER).invalidationStatus()).isEqualTo("AI_PROPOSED");
        assertThat(jdbc.queryForList("SELECT status FROM investment_thesis_approval_requests ORDER BY created_at",
                String.class)).containsExactly("SUPERSEDED", "PENDING");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void detailReviewIsReadOnlyAndUnknownTokensRevealNothing() throws Exception {
        var token = createProposalRequest();
        clearInvocations(client);

        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "D:" + token));
        assertThat(requestStatus()).isEqualTo("PENDING");
        var detail = sent.getLast().text();
        assertThat(detail).contains("[상세 검토]").contains("EXISTING_PROPOSAL").contains("PROPOSAL_RECORDED_AT")
                .contains("COMPUTED_ATR: UNVERIFIED (INSUFFICIENT_HISTORY)")
                .contains("COMPUTED_SUPPORT: UNVERIFIED (INSUFFICIENT_HISTORY)")
                .contains("[가정 리스크").contains("priceRiskTrigger 문구는 바꾸지 않습니다");
        assertThat(sent.getLast().keyboard()).isEmpty();

        clearInvocations(client);
        var sentBefore = sent.size();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "D:" + TelegramApprovalService.newToken()));
        verify(client).answerCallbackQuery(anyString(), eq("이미 처리되었거나 유효하지 않은 요청입니다. 변경 없음."));
        verify(client, never()).sendMessage(anyString(), anyList());
        assertThat(sent).hasSize(sentBefore);
    }

    @Test
    void callbackDataStaysWithinTelegramLimitAndStoresOnlyHashes() throws Exception {
        var token = createProposalRequest();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));

        var buttons = sent.stream().flatMap(item -> item.keyboard().stream()).flatMap(List::stream).toList();
        assertThat(buttons).hasSize(5);
        assertThat(buttons).allSatisfy(button ->
                assertThat(button.callbackData().getBytes(StandardCharsets.UTF_8).length).isEqualTo(45));
        var stored = jdbc.queryForMap("SELECT token_sha256, confirm_token_sha256 FROM investment_thesis_approval_requests");
        assertThat(stored.get("token_sha256")).isEqualTo(TelegramApprovalService.sha256Hex(token));
        assertThat(stored.values()).noneMatch(value -> value.toString().contains(token));
    }

    // ------------------------------------------------------------------ creation REST

    @Test
    void creationTakesTriggerFromStoredProposalNeverFromBodyAndListsWithoutTokens() throws Exception {
        var thesis = investment.currentThesis(USER, TICKER);
        mvc.perform(post("/investment/securities/acme/thesis/approval-requests").with(user(USER_ID))
                        .contentType("application/json")
                        .content("{\"candidateSource\":\"EXISTING_PROPOSAL\",\"candidateTrigger\":1,"
                                + "\"priceRiskTriggerPrice\":1,\"expectedThesisUpdatedAt\":\"" + thesis.updatedAt() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.candidateTrigger").value(90))
                .andExpect(jsonPath("$.inputs.sourceAsOfBasis").value("PROPOSAL_RECORDED_AT"));
        assertThat(jdbc.queryForObject("SELECT source_as_of FROM investment_thesis_approval_requests",
                OffsetDateTime.class).toInstant()).isEqualTo(thesis.updatedAt());
        assertThat(sent.getLast().text()).contains("[투자 논리(thesis) 확정 승인 요청]").contains("[가정 리스크")
                .doesNotContain(USER_ID);

        var listed = mvc.perform(get("/investment/thesis/approval-requests").with(user(USER_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var root = mapper.readTree(listed);
        assertThat(root).hasSize(1);
        assertThat(root.get(0).has("tokenSha256")).isFalse();
        assertThat(listed).doesNotContain("token");
    }

    @Test
    void creationPreconditionsAreEnforced() throws Exception {
        // Stale expected version.
        create("EXISTING_PROPOSAL", Instant.parse("2020-01-01T00:00:00Z"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("THESIS_APPROVAL_THESIS_CHANGED"));
        // Unknown source.
        create("MANUAL_PRICE", null).andExpect(status().isBadRequest());
        // Computed candidate without stored bars.
        create("COMPUTED_ATR", null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("THESIS_APPROVAL_CANDIDATE_UNVERIFIED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_HISTORY"));
        // No thesis row.
        mvc.perform(post("/investment/securities/NOPE/thesis/approval-requests").with(user(USER_ID))
                        .contentType("application/json").content("{\"candidateSource\":\"EXISTING_PROPOSAL\"}"))
                .andExpect(status().isNotFound());
        // A user other than the configured Telegram target cannot create requests.
        mvc.perform(post("/investment/securities/ACME/thesis/approval-requests").with(user(OTHER.toString()))
                        .contentType("application/json").content("{\"candidateSource\":\"EXISTING_PROPOSAL\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("THESIS_APPROVAL_NOT_READY"));
        // Proposal without a numeric trigger.
        var current = investment.currentThesis(USER, TICKER);
        investment.putThesisProposal(USER, TICKER, thesisInput("Core", "AI_PROPOSED", null), current.updatedAt());
        create("EXISTING_PROPOSAL", null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("THESIS_APPROVAL_TRIGGER_MISSING"));
        // Unauthenticated callers never reach the controller.
        mvc.perform(post("/investment/securities/ACME/thesis/approval-requests")
                        .contentType("application/json").content("{\"candidateSource\":\"EXISTING_PROPOSAL\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(count("investment_thesis_approval_requests")).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void alreadyConfirmedWithSameTriggerIsRejected() throws Exception {
        var token = createProposalRequest();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "F:" + confirmToken()));
        assertThat(requestStatus()).isEqualTo("APPROVED");

        create("EXISTING_PROPOSAL", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THESIS_APPROVAL_ALREADY_CONFIRMED"));
    }

    @Test
    void computedAtrCandidateIsCreatedFromStoredCompletedBars() throws Exception {
        var today = seedCompletedBars();

        var response = create("COMPUTED_ATR", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var created = mapper.readTree(response);
        // Every completed bar has H-L = 2 around a constant close: ATR = 2, candidate = 100 - 4.
        assertThat(created.path("candidateTrigger").decimalValue()).isEqualByComparingTo("96.0000");
        assertThat(created.path("inputs").path("method").asText()).isEqualTo("ATR14_WILDER_X2");
        assertThat(created.path("inputs").path("windowEnd").asText()).isEqualTo(today.minusDays(1).toString());
        assertThat(Instant.parse(created.path("sourceAsOf").asText()))
                .isEqualTo(today.minusDays(1).atTime(21, 0).toInstant(ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ /review and /pending bot commands

    @Test
    void reviewCommandCreatesPendingRequestForTargetUserAndLeavesThesisUntouched() throws Exception {
        var before = investment.currentThesis(USER, TICKER);

        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/review acme"));

        var request = jdbc.queryForMap("SELECT user_id, ticker, candidate_source, candidate_trigger, status, "
                + "thesis_expected_updated_at FROM investment_thesis_approval_requests");
        assertThat(request.get("user_id")).isEqualTo(USER);
        assertThat(request.get("ticker")).isEqualTo(TICKER);
        assertThat(request.get("candidate_source")).isEqualTo("EXISTING_PROPOSAL");
        assertThat((BigDecimal) request.get("candidate_trigger")).isEqualByComparingTo("90");
        assertThat(request.get("status")).isEqualTo("PENDING");
        assertThat(((java.sql.Timestamp) request.get("thesis_expected_updated_at")).toInstant())
                .isEqualTo(before.updatedAt());
        assertThat(sent).hasSize(1);
        assertThat(sent.getLast().text()).contains("[투자 논리(thesis) 확정 승인 요청]");
        assertThat(sent.getLast().keyboard().getFirst()).extracting(Button::text)
                .containsExactly("상세 검토", "승인", "보류");
        assertThat(investment.currentThesis(USER, TICKER)).isEqualTo(before);
        assertThat(count("investment_thesis_revisions")).isEqualTo(1);
        assertThat(count("telegram_webhook_updates")).isEqualTo(1);
        assertNoOrders();
    }

    @Test
    void reviewCommandAddressedToBotMapsAtrSourceCaseInsensitively() throws Exception {
        seedCompletedBars();

        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/review@AnyTrade_Bot acme atr"));

        var request = jdbc.queryForMap("SELECT candidate_source, candidate_trigger FROM investment_thesis_approval_requests");
        assertThat(request.get("candidate_source")).isEqualTo("COMPUTED_ATR");
        assertThat((BigDecimal) request.get("candidate_trigger")).isEqualByComparingTo("96.0000");
        assertThat(sent.getLast().text()).contains("후보 출처: COMPUTED_ATR");
    }

    @Test
    void reviewCommandRejectionRepliesWithReasonCodeOnlyAndCreatesNothing() throws Exception {
        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/review ACME SUPPORT"));
        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/review NOPE"));

        assertThat(count("investment_thesis_approval_requests")).isZero();
        assertThat(sent).extracting(Sent::text).containsExactly(
                "요청을 만들지 못했습니다: 후보 UNVERIFIED "
                        + "(THESIS_APPROVAL_CANDIDATE_UNVERIFIED/INSUFFICIENT_HISTORY)\n투자 논리(thesis)는 변경되지 않았습니다.",
                "요청을 만들지 못했습니다: 투자 논리(thesis) 없음 (THESIS_APPROVAL_THESIS_NOT_FOUND)"
                        + "\n투자 논리(thesis)는 변경되지 않았습니다.");
        assertThat(sent).allSatisfy(message -> assertThat(message.keyboard()).isEmpty());
    }

    @Test
    void reviewCommandFromWrongChatOrSenderCreatesNothingAndMakesNoOutboundCall() throws Exception {
        webhook(messageBody(nextUpdate(), 999L, APPROVER, "/review ACME"));
        webhook(messageBody(nextUpdate(), CHAT, 1L, "/review ACME"));
        webhook(messageBody(nextUpdate(), -CHAT, APPROVER, "/review"));
        webhook(messageBody(nextUpdate(), CHAT, 1L, "/pending"));

        assertThat(count("investment_thesis_approval_requests")).isZero();
        assertThat(count("telegram_webhook_updates")).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void duplicateReviewUpdateCreatesOneRequestAndOneMessage() throws Exception {
        var update = nextUpdate();

        webhook(messageBody(update, CHAT, APPROVER, "/review ACME"));
        webhook(messageBody(update, CHAT, APPROVER, "/review ACME"));

        assertThat(count("investment_thesis_approval_requests")).isEqualTo(1);
        assertThat(requestStatus()).isEqualTo("PENDING");
        assertThat(count("telegram_webhook_updates")).isEqualTo(1);
        verify(client, times(1)).sendMessage(anyString(), anyList());
    }

    @Test
    void reviewCommandWithBadArgumentsRepliesUsageAndCreatesNothing() throws Exception {
        for (var text : List.of("/review", "/review ACME FOO", "/review ACME ATR 90", "/review $$$",
                "/review@AnyBot")) {
            webhook(messageBody(nextUpdate(), CHAT, APPROVER, text));
        }

        assertThat(count("investment_thesis_approval_requests")).isZero();
        assertThat(sent).hasSize(5).allSatisfy(message -> {
            assertThat(message.text()).isEqualTo(TelegramApprovalService.REVIEW_USAGE);
            assertThat(message.keyboard()).isEmpty();
        });
    }

    @Test
    void nonCommandTextAndUnknownCommandsAreIgnoredWithoutReply() throws Exception {
        for (var text : List.of("hello", "ACME 승인", "/start", "/reviewer ACME", "please /review ACME")) {
            webhook(messageBody(nextUpdate(), CHAT, APPROVER, text));
        }
        webhook("{\"update_id\":" + nextUpdate() + ",\"message\":{\"chat\":{\"id\":777000},"
                + "\"from\":{\"id\":4242},\"sticker\":{}}}"); // no text

        assertThat(count("investment_thesis_approval_requests")).isZero();
        assertThat(count("telegram_webhook_updates")).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void reviewCommandThenTwoStepButtonsStillConfirmAndRepeatIsAlreadyConfirmed() throws Exception {
        var before = investment.currentThesis(USER, TICKER);
        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/review ACME PROPOSAL"));
        var token = token(sent.getLast(), "A:");

        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));
        assertThat(requestStatus()).isEqualTo("AWAITING_CONFIRM");
        assertThat(investment.currentThesis(USER, TICKER)).isEqualTo(before);
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "F:" + confirmToken()));

        assertThat(requestStatus()).isEqualTo("APPROVED");
        var after = investment.currentThesis(USER, TICKER);
        assertThat(after.invalidationStatus()).isEqualTo("CONFIRMED");
        assertThat(after.priceRiskTriggerPrice()).isEqualByComparingTo("90");
        assertThat(investment.thesisRevisions(USER, TICKER, 10).getFirst().actorType()).isEqualTo("TELEGRAM");

        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/review ACME"));
        assertThat(count("investment_thesis_approval_requests")).isEqualTo(1);
        assertThat(sent.getLast().text()).contains("THESIS_APPROVAL_ALREADY_CONFIRMED");
        assertNoOrders();
    }

    @Test
    void pendingCommandListsOpenRequestsReadOnly() throws Exception {
        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/pending"));
        assertThat(sent.getLast().text()).isEqualTo("[대기 중인 승인 요청] 없음");

        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/review ACME"));
        var before = jdbc.queryForMap("SELECT status, updated_at FROM investment_thesis_approval_requests");
        webhook(messageBody(nextUpdate(), CHAT, APPROVER, "/pending@AnyBot"));

        var listing = sent.getLast();
        assertThat(listing.keyboard()).isEmpty();
        assertThat(listing.text()).startsWith("[대기 중인 승인 요청]\n- ACME | EXISTING_PROPOSAL | PENDING | 만료 ")
                .doesNotContain("무효화 가격");
        assertThat(jdbc.queryForMap("SELECT status, updated_at FROM investment_thesis_approval_requests"))
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------ hard boundaries

    @Test
    void connectorProposalPathStillCannotConfirmAndNothingConfirmsWithoutFinalApproval() throws Exception {
        var current = investment.currentThesis(USER, TICKER);
        assertThatThrownBy(() -> investment.putThesisProposal(USER, TICKER, thesisInput("Core", "CONFIRMED", bd("90")),
                current.updatedAt()))
                .isInstanceOf(InvestmentException.class)
                .extracting(failure -> ((InvestmentException) failure).code())
                .isEqualTo(InvestmentException.Code.INVALID_INPUT);

        var token = createProposalRequest();
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "D:" + token));
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));
        webhook(callbackBody(nextUpdate(), CHAT, APPROVER, "A:" + token));
        assertThat(investment.currentThesis(USER, TICKER).invalidationStatus()).isEqualTo("AI_PROPOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions WHERE actor_type='TELEGRAM'",
                Integer.class)).isZero();
        assertNoOrders();
    }

    // ------------------------------------------------------------------ helpers

    /** Creates an EXISTING_PROPOSAL request for the seeded AI_PROPOSED thesis; returns the stage-1 token. */
    private String createProposalRequest() throws Exception {
        create("EXISTING_PROPOSAL", null).andExpect(status().isCreated());
        return token(sent.getLast(), "A:");
    }

    private org.springframework.test.web.servlet.ResultActions create(String source, Instant expected)
            throws Exception {
        var body = mapper.createObjectNode().put("candidateSource", source);
        if (expected != null) body.put("expectedThesisUpdatedAt", expected.toString());
        return mvc.perform(post("/investment/securities/" + TICKER + "/thesis/approval-requests")
                .with(user(USER_ID)).contentType("application/json").content(mapper.writeValueAsString(body)));
    }

    private String confirmToken() {
        return token(sent.getLast(), "F:");
    }

    private static String token(Sent message, String prefix) {
        return message.keyboard().stream().flatMap(List::stream).map(Button::callbackData)
                .filter(data -> data.startsWith(prefix)).findFirst().orElseThrow().substring(2);
    }

    private void webhook(String body) throws Exception {
        mvc.perform(post(WEBHOOK).header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                .contentType("application/json").content(body)).andExpect(status().isOk());
    }

    private String messageBody(long updateId, long chatId, long fromId, String text) {
        var root = mapper.createObjectNode().put("update_id", updateId);
        var message = root.putObject("message").put("message_id", 1).put("date", 1).put("text", text);
        message.putObject("from").put("id", fromId).put("is_bot", false);
        message.putObject("chat").put("id", chatId).put("type", "private");
        return mapper.writeValueAsString(root);
    }

    /** 20 completed daily bars (H-L = 2 around close 100) plus today's ignored bar and a fresh OK price. */
    private LocalDate seedCompletedBars() {
        var now = Instant.now();
        var today = LocalDate.ofInstant(now, ZoneId.of("America/New_York"));
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 20; i >= 1; i--) {
            var date = today.minusDays(i);
            rows.add(barRow(date, "100", date.atTime(21, 0).toInstant(ZoneOffset.UTC)));
        }
        rows.add(barRow(today, "300", now.minusSeconds(30))); // today's bar never counts
        tactical.recordTossBars(USER, TICKER, mapper.valueToTree(rows), now, null);
        insertOkPriceSnapshot(now.minusSeconds(60));
        return today;
    }

    private String callbackBody(long updateId, long chatId, long fromId, String data) {
        var root = mapper.createObjectNode().put("update_id", updateId);
        var query = root.putObject("callback_query").put("id", "cbq-" + updateId).put("data", data);
        query.putObject("from").put("id", fromId).put("is_bot", false);
        query.putObject("message").put("message_id", 1).putObject("chat").put("id", chatId);
        return mapper.writeValueAsString(root);
    }

    private long nextUpdate() {
        return updateIds.incrementAndGet();
    }

    private String requestStatus() {
        return jdbc.queryForObject("SELECT status FROM investment_thesis_approval_requests ORDER BY created_at DESC LIMIT 1",
                String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertNoOrders() {
        for (var table : ORDER_TABLES) assertThat(count(table)).as(table).isZero();
    }

    private void insertOkPriceSnapshot(Instant asOf) {
        var snapshot = mapper.createObjectNode().put("asOf", asOf.toString());
        snapshot.putObject("price").put("status", "OK").put("latestPrice", 101)
                .put("latestPriceAsOf", asOf.toString());
        var timestamp = OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), USER, TICKER, timestamp, mapper.writeValueAsString(snapshot), timestamp);
    }

    private static Map<String, Object> barRow(LocalDate date, String close, Instant sourceAsOf) {
        var price = bd(close);
        return Map.of("date", date.toString(), "timestamp", sourceAsOf.toString(), "session", "REGULAR_CLOSE",
                "currency", "USD", "open", price, "high", price.add(BigDecimal.ONE),
                "low", price.subtract(BigDecimal.ONE), "close", price, "volume", bd("1000"));
    }

    private static InvestmentContextService.ThesisInput thesisInput(String core, String status, BigDecimal trigger) {
        return new InvestmentContextService.ThesisInput(core, "Upside driver", "Expectations gap",
                "Fundamental invalidation", "Revision invalidation", "Close below the proposed level",
                trigger, status, "Expand trigger", "Exit trigger", "CORE");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
