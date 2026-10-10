package com.jmj.trade.investment;

import com.jmj.trade.notification.TelegramInteractiveClient;
import com.jmj.trade.notification.TelegramInteractiveException;
import com.jmj.trade.notification.TelegramApprovalSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages Telegram thesis approval workflow: request creation, 2-step confirmation, and state transitions.
 * Handles token generation, expiry, and webhook callbacks from Telegram inline button presses.
 */
@Service
public class TelegramApprovalService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final InvestmentContextService investmentContext;
    private final ObjectProvider<TelegramInteractiveClient> interactiveClient;
    private final ObjectProvider<TelegramApprovalSettings> approvalSettings;
    private final Clock clock;
    private final Duration approvallTtl;
    private final Duration confirmTtl;

    @Autowired
    public TelegramApprovalService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TransactionTemplate transaction,
            InvestmentContextService investmentContext,
            ObjectProvider<TelegramInteractiveClient> interactiveClient,
            ObjectProvider<TelegramApprovalSettings> approvalSettings,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value("${notification.telegram.approval-ttl:PT24H}") String approvallTtlString,
            @org.springframework.beans.factory.annotation.Value("${notification.telegram.confirm-ttl:PT5M}") String confirmTtlString) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
        this.investmentContext = investmentContext;
        this.interactiveClient = interactiveClient;
        this.approvalSettings = approvalSettings;
        this.clock = clock;
        this.approvallTtl = Duration.parse(approvallTtlString);
        this.confirmTtl = Duration.parse(confirmTtlString);
    }

    /**
     * Creates an approval request and sends a Telegram message with inline keyboard buttons.
     * Supersedes any existing open request for the same user+ticker pair.
     */
    public void createApprovalRequest(
            UUID userId,
            String ticker,
            String candidateSource,
            BigDecimal candidateTrigger,
            Map<String, Object> inputs,
            Instant sourceAsOf,
            Instant thesisExpectedUpdatedAt) {

        var settings = approvalSettings.getIfAvailable();
        if (settings == null || !settings.isReady()) {
            throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        }

        var now = clock.instant();
        var id = UUID.randomUUID();
        var token = generateToken();
        var tokenSha256 = sha256(token);
        var expiresAt = now.plus(approvallTtl);

        var inputsJson = encodeJson(inputs);

        transaction.execute(ignored -> {
            // Lock users row to enforce order invariant
            jdbc.queryForList("SELECT id FROM users WHERE id=? FOR NO KEY UPDATE", UUID.class, userId);

            // Expire or supersede any existing open requests
            jdbc.update(
                    "UPDATE investment_thesis_approval_requests SET status='EXPIRED', updated_at=? " +
                    "WHERE user_id=? AND ticker=? AND status='PENDING' AND expires_at<=?",
                    timestamp(now), userId, ticker, timestamp(now));
            jdbc.update(
                    "UPDATE investment_thesis_approval_requests SET status='SUPERSEDED', updated_at=? " +
                    "WHERE user_id=? AND ticker=? AND status IN ('PENDING','AWAITING_CONFIRM') AND expires_at>?",
                    timestamp(now), userId, ticker, timestamp(now));

            // Insert new request
            jdbc.update("""
                    INSERT INTO investment_thesis_approval_requests (
                        id, user_id, ticker, candidate_source, candidate_trigger, inputs,
                        source_as_of, thesis_expected_updated_at, status, expires_at,
                        token_sha256, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, userId, ticker, candidateSource, candidateTrigger, inputsJson,
                    timestamp(sourceAsOf), timestamp(thesisExpectedUpdatedAt), "PENDING", timestamp(expiresAt),
                    tokenSha256, timestamp(now), timestamp(now));

            return null;
        });

        // Send Telegram message with keyboard (outside transaction)
        sendApprovalMessage(id, token, userId, ticker, candidateSource);
    }

    /**
     * Processes a Telegram callback query from an inline button press (상세, 승인, 보류).
     * Validates chat ID, from ID, callback_data format, and dedupe update_id.
     * Transitions state machine and calls putThesis if approving.
     */
    public void handleCallbackQuery(
            Long updateId,
            Long chatId,
            Long fromId,
            String callbackData,
            String callbackQueryId) {

        var settings = approvalSettings.getIfAvailable();
        if (settings == null || !settings.isReady()) {
            return; // Feature off: 404 at controller level
        }

        // Validate chat and user IDs match config
        if (!chatId.equals(settings.chatId()) || !fromId.equals(settings.approverId())) {
            answerAndReturn(callbackQueryId, "Unauthorized");
            return;
        }

        // Parse action and token from callback_data
        var parts = callbackData.split(":", 2);
        if (parts.length != 2) {
            answerAndReturn(callbackQueryId, "Invalid request format");
            return;
        }

        var action = parts[0];
        var token = parts[1];
        var tokenSha256 = sha256(token);

        // Handle each action type
        if ("D".equals(action)) {
            // 상세 검토 (read-only, no state change, no token consume)
            answerAndReturn(callbackQueryId, null);
            return;
        }

        // For approval actions: dedupe and transition
        var now = clock.instant();
        var dedupeInserted = transaction.execute(ignored -> {
            // Try to insert dedupe row; if it already exists, this is a duplicate
            try {
                jdbc.update(
                        "INSERT INTO telegram_webhook_updates(update_id, received_at) VALUES(?, ?)",
                        updateId, timestamp(now));
                return true;
            } catch (Exception e) {
                // Duplicate update_id
                return false;
            }
        });

        if (!dedupeInserted) {
            // Duplicate; no state change
            answerAndReturn(callbackQueryId, null);
            return;
        }

        // Now transition based on action
        if ("A".equals(action)) {
            // 승인 (PENDING → AWAITING_CONFIRM)
            transitionToPendingConfirm(tokenSha256, now, fromId, callbackQueryId);
        } else if ("B".equals(action)) {
            // 최종 승인 (AWAITING_CONFIRM → APPROVED)
            transitionToApproved(tokenSha256, now, fromId, callbackQueryId);
        } else if ("C".equals(action)) {
            // 보류/취소 (→ HELD)
            transitionToHeld(tokenSha256, now, fromId, callbackQueryId);
        } else {
            answerAndReturn(callbackQueryId, "Unknown action");
        }
    }

    private void transitionToPendingConfirm(String tokenSha256, Instant now, Long telegramUserId, String callbackQueryId) {
        var confirmToken = generateToken();
        var confirmTokenSha256 = sha256(confirmToken);
        var confirmExpiresAt = now.plus(confirmTtl);

        transaction.execute(ignored -> {
            var updated = jdbc.update(
                    """
                    UPDATE investment_thesis_approval_requests
                    SET status='AWAITING_CONFIRM', confirm_token_sha256=?, confirm_expires_at=?, updated_at=?
                    WHERE token_sha256=? AND status='PENDING' AND expires_at>?
                    """,
                    confirmTokenSha256, timestamp(confirmExpiresAt), timestamp(now),
                    tokenSha256, timestamp(now));
            if (updated == 0) {
                throw new InvestmentException(InvestmentException.Code.CONFLICT);
            }
            return null;
        });

        answerAndReturn(callbackQueryId, "Approval step 1 confirmed. Confirm again to proceed.");
    }

    private void transitionToApproved(String confirmTokenSha256, Instant now, Long telegramUserId, String callbackQueryId) {
        // Main D1 transaction
        var result = transaction.execute(ignored -> {
            // Dedupe and transition
            try {
                jdbc.queryForList("SELECT id FROM users WHERE id=? FOR NO KEY UPDATE", UUID.class, UUID.fromString("00000000-0000-0000-0000-000000000000"));
            } catch (Exception e) {
                // Placeholder: users lock is deferred
            }

            var request = jdbc.query("""
                    UPDATE investment_thesis_approval_requests
                    SET status='APPROVED', decided_at=?, decided_by_telegram_user_id=?, updated_at=?
                    WHERE confirm_token_sha256=? AND status='AWAITING_CONFIRM' AND expires_at>? AND confirm_expires_at>?
                    RETURNING id, user_id, ticker, candidate_trigger, source_as_of, thesis_expected_updated_at
                    """,
                    (rs, row) -> Map.of(
                            "id", rs.getObject(1, UUID.class),
                            "user_id", rs.getObject(2, UUID.class),
                            "ticker", rs.getString(3),
                            "candidate_trigger", rs.getBigDecimal(4),
                            "source_as_of", rs.getObject(5, OffsetDateTime.class).toInstant(),
                            "thesis_expected_updated_at", rs.getObject(6, OffsetDateTime.class).toInstant()
                    ),
                    timestamp(now), telegramUserId, timestamp(now),
                    confirmTokenSha256, timestamp(now), timestamp(now));

            if (request.isEmpty()) {
                // Look up reason: expired, already processed, or other conflict
                var count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM investment_thesis_approval_requests WHERE confirm_token_sha256=?",
                        Integer.class, confirmTokenSha256);
                if (count == 0) {
                    throw new InvestmentException(InvestmentException.Code.CONFLICT);
                }
                throw new InvestmentException(InvestmentException.Code.CONFLICT);
            }

            var req = request.get(0);
            var userId = (UUID) req.get("user_id");
            var ticker = (String) req.get("ticker");
            var candidateTrigger = (BigDecimal) req.get("candidate_trigger");
            var sourceAsOf = (Instant) req.get("source_as_of");
            var thesisExpectedUpdatedAt = (Instant) req.get("thesis_expected_updated_at");

            // Read current thesis for all fields except status, trigger
            var thesisRow = jdbc.query("""
                    SELECT core_thesis, upside_driver, expectations_gap, fundamental_invalidation,
                           revision_invalidation, price_risk_trigger, price_risk_trigger_price,
                           expand_trigger, exit_or_discard_trigger, classification
                    FROM investment_thesis_states WHERE user_id=? AND ticker=?
                    """,
                    (rs, row) -> Map.of(
                            "core_thesis", rs.getString(1),
                            "upside_driver", rs.getString(2),
                            "expectations_gap", rs.getString(3),
                            "fundamental_invalidation", rs.getString(4),
                            "revision_invalidation", rs.getString(5),
                            "price_risk_trigger", rs.getString(6),
                            "price_risk_trigger_price", rs.getBigDecimal(7),
                            "expand_trigger", rs.getString(8),
                            "exit_or_discard_trigger", rs.getString(9),
                            "classification", rs.getString(10)
                    ),
                    userId, ticker);

            if (thesisRow.isEmpty()) {
                throw new InvestmentException(InvestmentException.Code.CONFLICT);
            }

            var thesis = thesisRow.get(0);

            var input = new InvestmentContextService.ThesisInput(
                    (String) thesis.get("core_thesis"),
                    (String) thesis.get("upside_driver"),
                    (String) thesis.get("expectations_gap"),
                    (String) thesis.get("fundamental_invalidation"),
                    (String) thesis.get("revision_invalidation"),
                    (String) thesis.get("price_risk_trigger"),
                    candidateTrigger, // Override with candidate trigger
                    "CONFIRMED",
                    (String) thesis.get("expand_trigger"),
                    (String) thesis.get("exit_or_discard_trigger"),
                    (String) thesis.get("classification")
            );

            var reason = "TELEGRAM_APPROVAL request=" + req.get("id");

            try {
                investmentContext.putThesis(
                        ThesisActor.TELEGRAM, userId, ticker, input,
                        thesisExpectedUpdatedAt, sourceAsOf, reason, null);
            } catch (InvestmentException e) {
                if (e.code() == InvestmentException.Code.CONFLICT) {
                    // Write CONFLICT status
                    jdbc.update(
                            "UPDATE investment_thesis_approval_requests SET status='CONFLICT', updated_at=? WHERE id=?",
                            timestamp(now), req.get("id"));
                    throw e;
                } else {
                    // Write FAILED status
                    jdbc.update(
                            "UPDATE investment_thesis_approval_requests SET status='FAILED', status_reason=?, updated_at=? WHERE id=?",
                            e.toString(), timestamp(now), req.get("id"));
                    throw e;
                }
            }

            return req;
        });

        // Post-commit reply (outside tx)
        answerAndReturn(callbackQueryId, "Thesis approved and confirmed!");
    }

    private void transitionToHeld(String tokenSha256, Instant now, Long telegramUserId, String callbackQueryId) {
        transaction.execute(ignored -> {
            jdbc.update(
                    """
                    UPDATE investment_thesis_approval_requests
                    SET status='HELD', decided_at=?, decided_by_telegram_user_id=?, status_reason=?, updated_at=?
                    WHERE token_sha256=? AND status='PENDING' AND expires_at>?
                    """,
                    timestamp(now), telegramUserId, "CANCELLED", timestamp(now),
                    tokenSha256, timestamp(now));
            return null;
        });

        answerAndReturn(callbackQueryId, "Request held.");
    }

    private void sendApprovalMessage(UUID requestId, String token, UUID userId, String ticker, String candidateSource) {
        var client = interactiveClient.getIfAvailable();
        if (client == null) {
            return;
        }

        var message = String.format(
                "Thesis Approval Request for %s\n\n" +
                "Candidate Source: %s\n" +
                "User: %s\n\n" +
                "Please review the thesis and confirm.",
                ticker, candidateSource, userId);

        var keyboard = List.of(
                List.of(
                        Map.entry("상세 검토", "D:" + token),
                        Map.entry("승인", "A:" + token),
                        Map.entry("보류", "C:" + token)
                )
        );

        try {
            var messageId = client.sendWithKeyboard(message, keyboard);
            // Update with message_id for later reference
            var now = clock.instant();
            jdbc.update(
                    "UPDATE investment_thesis_approval_requests SET telegram_message_id=?, updated_at=? WHERE id=?",
                    messageId, timestamp(now), requestId);
        } catch (TelegramInteractiveException e) {
            // Log but don't fail
            System.err.println("Failed to send approval message: " + e.reason());
        }
    }

    private void answerAndReturn(String callbackQueryId, String text) {
        var client = interactiveClient.getIfAvailable();
        if (client != null && callbackQueryId != null) {
            try {
                client.answerCallbackQuery(callbackQueryId, text, false);
            } catch (TelegramInteractiveException e) {
                System.err.println("Failed to answer callback: " + e.reason());
            }
        }
    }

    private String generateToken() {
        var random = new SecureRandom();
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes());
            var hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String encodeJson(Map<String, Object> obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode JSON", e);
        }
    }

    private String timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneId.of("UTC")).toString();
    }
}
