package com.jmj.trade.investment;

import com.jmj.trade.notification.TelegramApprovalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Telegram webhook for inline-button callbacks and text-message bot commands ({@code /review}, {@code /pending})
 * of the thesis approval workflow. Unauthenticated at the HTTP layer (permitAll); authenticated by Telegram's
 * secret_token header. Order of checks:
 * <ol>
 *   <li>workflow not ready: 404</li>
 *   <li>secret header missing: 401; mismatch (constant time) or no usable secret configured: 403</li>
 *   <li>neither callback_query nor a text message, or malformed: 200, no-op</li>
 *   <li>chat.id / from.id not the configured approver: 200, no state change, no outbound call</li>
 *   <li>non-command text or unknown command: 200, no-op, no reply</li>
 *   <li>update_id dedupe + state transition (or request creation for {@code /review})</li>
 * </ol>
 * Authenticated updates always get 2xx so Telegram does not retry; failures are logged by category only.
 */
@RestController
final class TelegramWebhookController {

    static final String PATH = "/api/v1/telegram/webhook";
    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final TelegramApprovalService approvals;
    private final TelegramApprovalSettings settings;
    private final ObjectMapper mapper;

    TelegramWebhookController(TelegramApprovalService approvals, TelegramApprovalSettings settings,
                              ObjectMapper mapper) {
        this.approvals = approvals;
        this.settings = settings;
        this.mapper = mapper;
    }

    @PostMapping(PATH)
    ResponseEntity<Void> webhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
            @RequestBody(required = false) byte[] body
    ) {
        if (!settings.isReady()) return ResponseEntity.notFound().build();
        if (secret == null || secret.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!settings.webhookSecretMatches(secret)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        var update = update(body);
        if (update == null) return ResponseEntity.ok().build();
        var callback = callback(update);
        if (callback != null) {
            try {
                approvals.handleCallback(callback);
            } catch (RuntimeException failure) {
                log.warn("Telegram callback processing failed: {}", failure.getClass().getSimpleName());
            }
            return ResponseEntity.ok().build();
        }
        var command = command(update);
        if (command != null) {
            try {
                approvals.handleCommand(command);
            } catch (RuntimeException failure) {
                log.warn("Telegram command processing failed: {}", failure.getClass().getSimpleName());
            }
        }
        return ResponseEntity.ok().build();
    }

    private JsonNode update(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            var update = mapper.readTree(body);
            return update != null && update.isObject() ? update : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** A {@code message} update with integral update_id, chat.id, from.id and a text field; else null. */
    private static TelegramApprovalService.Command command(JsonNode update) {
        var message = update.get("message");
        if (message == null || !message.isObject()) return null;
        var updateId = integral(update.get("update_id"));
        var chatId = integral(message.path("chat").get("id"));
        var fromId = integral(message.path("from").get("id"));
        var text = message.get("text");
        if (updateId == null || chatId == null || fromId == null || text == null || !text.isTextual()) return null;
        return new TelegramApprovalService.Command(updateId, chatId, fromId, text.asText());
    }

    private static TelegramApprovalService.Callback callback(JsonNode update) {
        var query = update.get("callback_query");
        if (query == null || !query.isObject()) return null;
        var updateId = integral(update.get("update_id"));
        var message = query.get("message");
        var chatId = message == null || !message.isObject() ? null
                : integral(message.path("chat").get("id"));
        var fromId = integral(query.path("from").get("id"));
        var id = query.get("id");
        var data = query.get("data");
        if (updateId == null || chatId == null || fromId == null || id == null || !id.isTextual()
                || id.asText().isBlank()) {
            return null;
        }
        return new TelegramApprovalService.Callback(updateId, chatId, fromId, id.asText(),
                data != null && data.isTextual() ? data.asText() : null);
    }

    private static Long integral(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : null;
    }
}
