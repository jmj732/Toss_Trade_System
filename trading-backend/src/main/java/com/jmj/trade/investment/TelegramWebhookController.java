package com.jmj.trade.investment;

import com.jmj.trade.notification.TelegramApprovalSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webhook endpoint for Telegram callback queries (inline button presses).
 * Validates secret, parses callback_query updates, delegates to TelegramApprovalService.
 */
@RestController
@RequestMapping("/api/v1/telegram")
public class TelegramWebhookController {

    private final TelegramApprovalService approvalService;
    private final ObjectProvider<TelegramApprovalSettings> approvalSettings;
    private final String botToken;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramWebhookController(
            TelegramApprovalService approvalService,
            ObjectProvider<TelegramApprovalSettings> approvalSettings,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${notification.telegram.bot-token:}") String botTokenValue) {
        this.approvalService = approvalService;
        this.approvalSettings = approvalSettings;
        this.botToken = botTokenValue;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretHeader,
            @RequestBody byte[] rawBody) {

        var settings = approvalSettings.getIfAvailable();
        if (settings == null || !settings.isReady()) {
            return ResponseEntity.notFound().build();
        }

        // Validate secret
        var expectedSecret = settings.webhookSecret();
        if (expectedSecret == null || expectedSecret.isBlank()) {
            // Derive from bot token
            expectedSecret = TelegramApprovalSettings.deriveWebhookSecret(botToken);
        }

        if (secretHeader == null || !constantTimeEquals(secretHeader.getBytes(StandardCharsets.UTF_8), expectedSecret.getBytes(StandardCharsets.UTF_8))) {
            if (secretHeader == null) {
                return ResponseEntity.status(401).build();
            } else {
                return ResponseEntity.status(403).build();
            }
        }

        // Parse JSON
        JsonNode update;
        try {
            update = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.ok().build();
        }

        // Check for callback_query (non-callback updates are ignored)
        var callbackQuery = update.path("callback_query");
        if (!callbackQuery.isObject()) {
            return ResponseEntity.ok().build();
        }

        // Extract fields
        var updateId = update.path("update_id").asLong();
        var message = callbackQuery.path("message");
        var chatId = message.path("chat").path("id").asLong();
        var fromId = callbackQuery.path("from").path("id").asLong();
        var callbackData = callbackQuery.path("data").asText();
        var callbackQueryId = callbackQuery.path("id").asText();

        if (updateId == 0 || chatId == 0 || fromId == 0 || callbackData.isEmpty() || callbackQueryId.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        // Delegate to approval service
        try {
            approvalService.handleCallbackQuery(updateId, chatId, fromId, callbackData, callbackQueryId);
        } catch (Exception e) {
            // Log but return 200 to Telegram
            System.err.println("Error handling callback: " + e.getMessage());
        }

        return ResponseEntity.ok().build();
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
