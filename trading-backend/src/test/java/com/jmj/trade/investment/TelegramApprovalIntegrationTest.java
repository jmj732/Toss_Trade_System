package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.notification.TelegramInteractiveClient;
import com.jmj.trade.notification.TelegramInteractiveException;
import com.jmj.trade.notification.TelegramApprovalSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Telegram thesis approval workflow.
 * Tests request creation, callback handling, state transitions, and edge cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelegramApprovalIntegrationTest {

    private static final UUID USER = UUID.fromString("11990000-0000-7000-8000-000000000099");
    private static final Long CHAT_ID = 123456789L;
    private static final Long APPROVER_ID = 987654321L;

    @MockitoBean
    TelegramInteractiveClient interactiveClient;

    @MockitoBean
    ObjectProvider<TelegramInteractiveClient> interactiveClientProvider;

    @MockitoBean
    ObjectProvider<TelegramApprovalSettings> approvalSettingsProvider;

    @BeforeEach
    void setUp() {
        when(interactiveClientProvider.getIfAvailable()).thenReturn(interactiveClient);
        when(approvalSettingsProvider.getIfAvailable()).thenReturn(
                new TelegramApprovalSettings(true, CHAT_ID, APPROVER_ID, USER, null));
        when(interactiveClient.sendWithKeyboard(anyString(), any())).thenReturn(1L);
    }

    @Test
    void webhookWithMissingSecretReturns401() throws Exception {
        var mvc = getContext().getBean(MockMvc.class);
        var payload = Map.of("update_id", 1L, "callback_query", Map.of(
                "id", "cq1",
                "from", Map.of("id", APPROVER_ID),
                "message", Map.of("chat", Map.of("id", CHAT_ID)),
                "data", "A:test"
        ));

        mvc.perform(post("/api/v1/telegram/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookWithWrongSecretReturns403() throws Exception {
        var mvc = getContext().getBean(MockMvc.class);
        var payload = Map.of("update_id", 1L, "callback_query", Map.of(
                "id", "cq1",
                "from", Map.of("id", APPROVER_ID),
                "message", Map.of("chat", Map.of("id", CHAT_ID)),
                "data", "A:test"
        ));

        mvc.perform(post("/api/v1/telegram/webhook")
                .header("X-Telegram-Bot-Api-Secret-Token", "wrong-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(payload)))
                .andExpect(status().isForbidden());
    }

    @Test
    void webhookWithFeatureOffReturns404() throws Exception {
        when(approvalSettingsProvider.getIfAvailable()).thenReturn(null);
        var mvc = getContext().getBean(MockMvc.class);
        var payload = Map.of("update_id", 1L);

        mvc.perform(post("/api/v1/telegram/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    void webhookWithWrongChatIdDoesNotChangeState() throws Exception {
        var service = getContext().getBean(TelegramApprovalService.class);
        var jdbc = getContext().getBean(JdbcTemplate.class);

        // Setup
        var now = Instant.now();
        service.createApprovalRequest(USER, "AAPL", "EXISTING_PROPOSAL", BigDecimal.valueOf(100),
                Map.of("test", "data"), now, now);

        // Webhook with wrong chat ID
        handleCallback(999999999L, APPROVER_ID, "A:dummy-token");

        // Verify no state change and no outbound calls
        var state = jdbc.queryForList(
                "SELECT status FROM investment_thesis_approval_requests WHERE user_id=? AND ticker=?",
                String.class, USER, "AAPL");
        assertThat(state).containsExactly("PENDING");
        verify(interactiveClient, never()).answerCallbackQuery(anyString(), anyString(), anyBoolean());
    }

    @Test
    void webhookWithWrongFromIdDoesNotChangeState() throws Exception {
        var service = getContext().getBean(TelegramApprovalService.class);
        var jdbc = getContext().getBean(JdbcTemplate.class);

        var now = Instant.now();
        service.createApprovalRequest(USER, "AAPL", "EXISTING_PROPOSAL", BigDecimal.valueOf(100),
                Map.of("test", "data"), now, now);

        handleCallback(CHAT_ID, 999999999L, "A:dummy-token");

        var state = jdbc.queryForList(
                "SELECT status FROM investment_thesis_approval_requests WHERE user_id=? AND ticker=?",
                String.class, USER, "AAPL");
        assertThat(state).containsExactly("PENDING");
        verify(interactiveClient, never()).answerCallbackQuery(anyString(), anyString(), anyBoolean());
    }

    @Test
    void nonCallbackUpdateReturns200NoOp() throws Exception {
        var mvc = getContext().getBean(MockMvc.class);
        var payload = Map.of("update_id", 1L, "message", Map.of("text", "hello"));

        mvc.perform(post("/api/v1/telegram/webhook")
                .header("X-Telegram-Bot-Api-Secret-Token", "test-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(payload)))
                .andExpect(status().isOk());

        verify(interactiveClient, never()).answerCallbackQuery(anyString(), anyString(), anyBoolean());
    }

    @Test
    void duplicateUpdateIdIsIdempotent() throws Exception {
        var service = getContext().getBean(TelegramApprovalService.class);
        var jdbc = getContext().getBean(JdbcTemplate.class);

        var now = Instant.now();
        var token = "test-token-123";
        service.createApprovalRequest(USER, "AAPL", "EXISTING_PROPOSAL", BigDecimal.valueOf(100),
                Map.of("test", "data"), now, now);

        // Same update_id twice
        service.handleCallbackQuery(1L, CHAT_ID, APPROVER_ID, "A:" + token, "cq1");
        service.handleCallbackQuery(1L, CHAT_ID, APPROVER_ID, "A:" + token, "cq1");

        // Only one webhook_update row
        var count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM telegram_webhook_updates WHERE update_id=1",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void double승인Tap() throws Exception {
        var service = getContext().getBean(TelegramApprovalService.class);
        var jdbc = getContext().getBean(JdbcTemplate.class);

        var now = Instant.now();
        var token = "test-token-456";
        service.createApprovalRequest(USER, "AAPL", "EXISTING_PROPOSAL", BigDecimal.valueOf(100),
                Map.of("test", "data"), now, now);

        // First 승인
        service.handleCallbackQuery(1L, CHAT_ID, APPROVER_ID, "A:" + token, "cq1");
        var state1 = jdbc.queryForObject(
                "SELECT status FROM investment_thesis_approval_requests WHERE user_id=? AND ticker=?",
                String.class, USER, "AAPL");
        assertThat(state1).isEqualTo("AWAITING_CONFIRM");

        // Second 승인 with same token should have no effect (token already consumed)
        service.handleCallbackQuery(2L, CHAT_ID, APPROVER_ID, "A:" + token, "cq2");
        var state2 = jdbc.queryForObject(
                "SELECT status FROM investment_thesis_approval_requests WHERE user_id=? AND ticker=?",
                String.class, USER, "AAPL");
        assertThat(state2).isEqualTo("AWAITING_CONFIRM");
    }

    @Test
    void single승인DoesNotConfirm() throws Exception {
        var service = getContext().getBean(TelegramApprovalService.class);
        var jdbc = getContext().getBean(JdbcTemplate.class);

        var now = Instant.now();
        service.createApprovalRequest(USER, "AAPL", "EXISTING_PROPOSAL", BigDecimal.valueOf(100),
                Map.of("test", "data"), now, now);

        var token = "test-token-789";
        service.handleCallbackQuery(1L, CHAT_ID, APPROVER_ID, "A:" + token, "cq1");

        var state = jdbc.queryForObject(
                "SELECT status FROM investment_thesis_approval_requests WHERE user_id=? AND ticker=?",
                String.class, USER, "AAPL");
        assertThat(state).isEqualTo("AWAITING_CONFIRM");

        // Not APPROVED yet
    }

    @Test
    void webhookWithMissingChatMessageRejects() throws Exception {
        var mvc = getContext().getBean(MockMvc.class);
        var payload = Map.of("update_id", 1L, "callback_query", Map.of(
                "id", "cq1",
                "from", Map.of("id", APPROVER_ID),
                // Missing "message" field
                "data", "A:test"
        ));

        mvc.perform(post("/api/v1/telegram/webhook")
                .header("X-Telegram-Bot-Api-Secret-Token", "test-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    // Helper method to test callback handling with proper secret
    private void handleCallback(Long chatId, Long fromId, String callbackData) {
        var service = getContext().getBean(TelegramApprovalService.class);
        service.handleCallbackQuery(1L, chatId, fromId, callbackData, "cq1");
    }

    private org.springframework.context.ApplicationContext getContext() {
        return org.springframework.test.context.TestContextManager.getInstance().getTestContext().getApplicationContext();
    }

    @Configuration
    static class TestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2024-01-15T10:00:00Z"), ZoneId.of("UTC"));
        }

        @Bean
        TelegramApprovalService telegramApprovalService(
                JdbcTemplate jdbc,
                ObjectMapper mapper,
                TransactionTemplate tx,
                InvestmentContextService contextService,
                ObjectProvider<TelegramInteractiveClient> client,
                ObjectProvider<TelegramApprovalSettings> settings,
                Clock clock) {
            return new TelegramApprovalService(jdbc, mapper, tx, contextService, client, settings, clock,
                    "PT24H", "PT5M");
        }
    }
}
