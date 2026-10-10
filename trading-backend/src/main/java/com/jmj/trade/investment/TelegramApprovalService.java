package com.jmj.trade.investment;

import com.jmj.trade.notification.TelegramApprovalSettings;
import com.jmj.trade.notification.TelegramInteractiveClient;
import com.jmj.trade.notification.TelegramInteractiveClient.Button;
import com.jmj.trade.notification.TelegramInteractiveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Telegram 2-step approval of a thesis invalidation trigger: request creation (user REST) and webhook callbacks.
 *
 * <p>State machine: PENDING -(승인)-> AWAITING_CONFIRM -(최종 승인)-> APPROVED; PENDING/AWAITING_CONFIRM
 * -(보류/취소)-> HELD; expiry -> EXPIRED; a newer request -> SUPERSEDED; a rejected thesis write -> CONFLICT/FAILED;
 * a failed Telegram send -> FAILED. Only 최종 승인 writes the thesis (status CONFIRMED, trigger taken from the
 * stored request row, every other field unchanged) through {@code putThesis(TELEGRAM, ...)}. No order is created.
 *
 * <p>Every transition runs in one transaction that locks the owner's {@code users} row first (same lock order as
 * request creation and {@code putThesis}), then claims the Telegram {@code update_id} with
 * {@code INSERT ... ON CONFLICT DO NOTHING} in that same transaction, then applies a guarded
 * {@code UPDATE ... RETURNING}. Telegram calls happen only after commit and never change the outcome.
 */
@Service
public class TelegramApprovalService {

    private static final Logger log = LoggerFactory.getLogger(TelegramApprovalService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 1-char action + ':' + 43-char base64url token = 45 bytes, within Telegram's 64-byte callback_data limit. */
    private static final Pattern CALLBACK_DATA = Pattern.compile("([DAHFX]):([A-Za-z0-9_-]{43})");
    static final String EXISTING_PROPOSAL = "EXISTING_PROPOSAL";
    private static final Set<String> SOURCES = Set.of(EXISTING_PROPOSAL,
            ThesisCandidateGenerator.COMPUTED_ATR, ThesisCandidateGenerator.COMPUTED_SUPPORT);
    private static final String STAGE1_TOKEN = "token_sha256";
    private static final String CONFIRM_TOKEN = "confirm_token_sha256";
    private static final String TABLE = "investment_thesis_approval_requests";
    private static final String COLUMNS = """
            id, user_id, ticker, candidate_source, candidate_trigger, inputs::text AS inputs, source_as_of,
            thesis_expected_updated_at, status, status_reason, expires_at, confirm_expires_at,
            telegram_message_id, confirm_message_id, decided_at, created_at, updated_at""";
    private static final String UNAVAILABLE = "이미 처리되었거나 유효하지 않은 요청입니다. 변경 없음.";
    private static final String NO_RISK = "리스크 미산출(보유·관심 대상 아님)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final InvestmentContextService investment;
    private final ThesisCandidateGenerator candidates;
    private final TelegramApprovalSettings settings;
    private final ObjectProvider<TelegramInteractiveClient> clients;
    private final Duration requestTtl;
    private final Duration confirmTtl;
    private final Clock clock = Clock.systemUTC();

    public TelegramApprovalService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            InvestmentContextService investment,
            ThesisCandidateGenerator candidates,
            TelegramApprovalSettings settings,
            ObjectProvider<TelegramInteractiveClient> clients,
            @Value("${notification.telegram.approval-ttl:PT24H}") Duration requestTtl,
            @Value("${notification.telegram.confirm-ttl:PT5M}") Duration confirmTtl
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.investment = Objects.requireNonNull(investment, "investment");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.requestTtl = positive(requestTtl, "requestTtl");
        this.confirmTtl = positive(confirmTtl, "confirmTtl");
    }

    // ------------------------------------------------------------------ request creation (user REST)

    /**
     * Creates a PENDING request for the caller's existing thesis and sends the stage-1 keyboard. The trigger is
     * never taken from the caller: it is the stored proposal trigger or a server-computed candidate.
     */
    public RequestView createRequest(UUID userId, String rawTicker, String rawSource, Instant expectedThesisUpdatedAt) {
        if (!settings.isReady() || !settings.hasUsableWebhookSecret() || !settings.isTargetUser(userId)) {
            throw new Rejected(Rejected.Reason.NOT_READY, null);
        }
        var ticker = InvestmentContextService.ticker(rawTicker);
        var source = rawSource == null ? "" : rawSource.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(source)) throw new Rejected(Rejected.Reason.INVALID_SOURCE, null);
        var thesis = investment.currentThesis(userId, ticker);
        if (thesis == null) throw new Rejected(Rejected.Reason.THESIS_NOT_FOUND, null);
        if (expectedThesisUpdatedAt != null && !expectedThesisUpdatedAt.equals(thesis.updatedAt())) {
            throw new Rejected(Rejected.Reason.THESIS_CHANGED, null);
        }

        BigDecimal trigger;
        Instant sourceAsOf;
        Map<String, Object> inputs;
        if (EXISTING_PROPOSAL.equals(source)) {
            trigger = thesis.priceRiskTriggerPrice();
            if (trigger == null || trigger.signum() <= 0) throw new Rejected(Rejected.Reason.TRIGGER_MISSING, null);
            sourceAsOf = thesis.updatedAt();
            inputs = new LinkedHashMap<>();
            inputs.put("method", EXISTING_PROPOSAL);
            inputs.put("sourceAsOfBasis", "PROPOSAL_RECORDED_AT");
            inputs.put("proposalStatus", thesis.invalidationStatus());
        } else {
            var candidate = candidates.candidates(userId, ticker).get(source);
            if (candidate == null || !candidate.available()) {
                throw new Rejected(Rejected.Reason.CANDIDATE_UNVERIFIED,
                        candidate == null ? "CANDIDATE_MISSING" : candidate.reason());
            }
            trigger = candidate.trigger();
            sourceAsOf = candidate.sourceAsOf();
            inputs = candidate.inputs();
        }
        if ("CONFIRMED".equals(thesis.invalidationStatus()) && thesis.priceRiskTriggerPrice() != null
                && thesis.priceRiskTriggerPrice().compareTo(trigger) == 0) {
            throw new Rejected(Rejected.Reason.ALREADY_CONFIRMED, null);
        }
        if (sourceAsOf == null || sourceAsOf.isAfter(clock.instant())) {
            throw new Rejected(Rejected.Reason.CANDIDATE_UNVERIFIED, "SOURCE_AS_OF_INVALID");
        }
        try {
            // Dry run of the exact putThesis validation the final approval will perform.
            InvestmentContextService.validateThesis(confirmedInput(thesis, trigger));
        } catch (InvestmentException invalid) {
            throw new Rejected(Rejected.Reason.INVALID_THESIS, null);
        }
        var hypothetical = hypotheticalRisk(userId, ticker, trigger);

        var id = UUID.randomUUID();
        var token = newToken();
        var now = clock.instant();
        var inputsJson = mapper.writeValueAsString(inputs);
        var closed = transaction.execute(status -> {
            investment.lockThesisWriter(userId);
            var locked = investment.currentThesis(userId, ticker);
            if (locked == null || !locked.updatedAt().equals(thesis.updatedAt())) {
                throw new Rejected(Rejected.Reason.THESIS_CHANGED, null);
            }
            var expired = jdbc.query(returning("""
                    UPDATE investment_thesis_approval_requests
                       SET status='EXPIRED', status_reason='EXPIRED', updated_at=?
                     WHERE user_id=? AND ticker=? AND status IN ('PENDING','AWAITING_CONFIRM')
                       AND (expires_at<=? OR confirm_expires_at<=?)
                    """), ROW, ts(now), userId, ticker, ts(now), ts(now));
            var superseded = jdbc.query(returning("""
                    UPDATE investment_thesis_approval_requests
                       SET status='SUPERSEDED', status_reason=?, updated_at=?
                     WHERE user_id=? AND ticker=? AND status IN ('PENDING','AWAITING_CONFIRM')
                    """), ROW, "SUPERSEDED_BY " + id, ts(now), userId, ticker);
            jdbc.update("""
                    INSERT INTO investment_thesis_approval_requests
                        (id, user_id, ticker, candidate_source, candidate_trigger, inputs, source_as_of,
                         thesis_expected_updated_at, status, expires_at, token_sha256, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, 'PENDING', ?, ?, ?, ?)
                    """, id, userId, ticker, source, trigger, inputsJson, ts(sourceAsOf), ts(thesis.updatedAt()),
                    ts(now.plus(requestTtl)), sha256Hex(token), ts(now), ts(now));
            return new Closed(expired, superseded);
        });

        closed.expired().forEach(row -> closeKeyboards(row, "만료됨. 이 버튼은 더 이상 유효하지 않습니다."));
        closed.superseded().forEach(row -> closeKeyboards(row, "새 요청으로 대체됨. 이 버튼은 더 이상 유효하지 않습니다."));
        var request = find(id);
        var keyboard = List.of(List.of(
                new Button("상세 검토", "D:" + token),
                new Button("승인", "A:" + token),
                new Button("보류", "H:" + token)));
        try {
            var messageId = client().sendMessage(requestText(request, thesis, hypothetical), keyboard);
            jdbc.update("UPDATE " + TABLE + " SET telegram_message_id=? WHERE id=?", messageId, id);
        } catch (TelegramInteractiveException failure) {
            log.warn("Telegram approval request delivery failed: {}", failure.reason());
            markSendFailed(request, "PENDING");
        }
        return view(find(id));
    }

    /** The caller's requests, newest first. Token hashes and Telegram ids are never exposed. */
    public List<RequestView> list(UUID userId, int limit) {
        if (userId == null || limit < 1 || limit > 200) throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE user_id=? ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW, userId, limit).stream().map(this::view).toList();
    }

    // ------------------------------------------------------------------ webhook callbacks

    /**
     * Handles an authenticated callback_query. Callers from another chat or user change nothing and trigger no
     * outbound Telegram call. Duplicate update_ids are no-ops.
     */
    public void handleCallback(Callback callback) {
        if (!settings.isReady()) return;
        if (!settings.isAuthorizedActor(callback.chatId(), callback.fromId())) {
            log.warn("Telegram callback ignored: UNAUTHORIZED_ACTOR");
            return;
        }
        var now = clock.instant();
        var matcher = CALLBACK_DATA.matcher(Objects.requireNonNullElse(callback.data(), ""));
        if (!matcher.matches()) {
            if (claimAlone(callback, now)) answer(callback, UNAVAILABLE);
            return;
        }
        var hash = sha256Hex(matcher.group(2));
        switch (matcher.group(1).charAt(0)) {
            case 'D' -> detail(callback, hash, now);
            case 'A' -> post(callback, stageOne(callback, hash, now));
            case 'H' -> post(callback, hold(callback, STAGE1_TOKEN, hash, now));
            case 'X' -> post(callback, hold(callback, CONFIRM_TOKEN, hash, now));
            case 'F' -> post(callback, finalApproval(callback, hash, now));
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private Outcome stageOne(Callback callback, String hash, Instant now) {
        var confirmToken = newToken();
        return guarded(callback, STAGE1_TOKEN, hash, now, owner -> {
            var rows = jdbc.query(returning("""
                    UPDATE investment_thesis_approval_requests
                       SET status='AWAITING_CONFIRM', confirm_token_sha256=?, confirm_expires_at=?, updated_at=?
                     WHERE token_sha256=? AND status='PENDING' AND expires_at>?
                    """), ROW, sha256Hex(confirmToken), ts(now.plus(confirmTtl)), ts(now),
                    hash, ts(now));
            return rows.isEmpty() ? expireOrAlready(STAGE1_TOKEN, hash, now)
                    : new Outcome(Kind.AWAITING_CONFIRM, rows.getFirst(), confirmToken);
        });
    }

    private Outcome hold(Callback callback, String column, String hash, Instant now) {
        var statuses = STAGE1_TOKEN.equals(column) ? "('PENDING','AWAITING_CONFIRM')" : "('AWAITING_CONFIRM')";
        return guarded(callback, column, hash, now, owner -> {
            var rows = jdbc.query(returning("""
                    UPDATE investment_thesis_approval_requests
                       SET status='HELD', status_reason='CANCELLED', decided_at=?, decided_by_telegram_user_id=?,
                           updated_at=?
                     WHERE %s=? AND status IN %s
                       AND expires_at>? AND (confirm_expires_at IS NULL OR confirm_expires_at>?)
                    """.formatted(column, statuses)), ROW, ts(now), callback.fromId(), ts(now), hash, ts(now), ts(now));
            return rows.isEmpty() ? expireOrAlready(column, hash, now) : new Outcome(Kind.HELD, rows.getFirst(), null);
        });
    }

    /** D1: TX1 = lock user, claim update, guarded APPROVED, putThesis(TELEGRAM); a rejected write -> TX2. */
    private Outcome finalApproval(Callback callback, String hash, Instant now) {
        var approved = new AtomicReference<RequestRow>();
        try {
            return guarded(callback, CONFIRM_TOKEN, hash, now, owner -> {
                var rows = jdbc.query(returning("""
                        UPDATE investment_thesis_approval_requests
                           SET status='APPROVED', decided_at=?, decided_by_telegram_user_id=?, updated_at=?
                         WHERE confirm_token_sha256=? AND status='AWAITING_CONFIRM'
                           AND expires_at>? AND confirm_expires_at>?
                        """), ROW, ts(now), callback.fromId(), ts(now), hash, ts(now), ts(now));
                if (rows.isEmpty()) return expireOrAlready(CONFIRM_TOKEN, hash, now);
                var request = rows.getFirst();
                approved.set(request);
                var thesis = investment.currentThesis(request.userId(), request.ticker());
                if (thesis == null) throw new InvestmentException(InvestmentException.Code.CONFLICT);
                investment.putThesis(ThesisActor.TELEGRAM, request.userId(), request.ticker(),
                        confirmedInput(thesis, request.candidateTrigger()), request.thesisExpectedUpdatedAt(),
                        request.sourceAsOf(), "TELEGRAM_APPROVAL request=" + request.id(), null);
                return new Outcome(Kind.APPROVED, request, null);
            });
        } catch (InvestmentException rejected) {
            // TX1 rolled back entirely (including its dedupe row); record the failure in TX2.
            var request = approved.get();
            if (request == null) throw rejected;
            var conflict = rejected.code() == InvestmentException.Code.CONFLICT;
            return recordRejection(callback, request, conflict ? Kind.CONFLICT : Kind.FAILED,
                    conflict ? "THESIS_CHANGED" : "THESIS_WRITE_REJECTED_" + rejected.code().name(), now);
        }
    }

    private Outcome recordRejection(Callback callback, RequestRow request, Kind kind, String reason, Instant now) {
        return transaction.execute(status -> {
            investment.lockThesisWriter(request.userId());
            if (!claim(callback.updateId(), now)) return Outcome.of(Kind.DUPLICATE);
            var rows = jdbc.query(returning("""
                    UPDATE investment_thesis_approval_requests
                       SET status=?, status_reason=?, decided_at=?, decided_by_telegram_user_id=?, updated_at=?
                     WHERE id=? AND status='AWAITING_CONFIRM'
                    """), ROW, kind.name(), reason, ts(now), callback.fromId(), ts(now),
                    request.id());
            return rows.isEmpty() ? Outcome.of(Kind.ALREADY_PROCESSED) : new Outcome(kind, rows.getFirst(), null);
        });
    }

    /** Shared transaction skeleton: owner lookup, users lock, update_id claim, then the guarded transition. */
    private Outcome guarded(Callback callback, String column, String hash, Instant now,
                            Function<UUID, Outcome> transition) {
        return transaction.execute(status -> {
            var owner = jdbc.query("SELECT user_id FROM " + TABLE + " WHERE " + column + "=?",
                    (rs, row) -> rs.getObject(1, UUID.class), hash).stream().findFirst().orElse(null);
            if (owner != null) investment.lockThesisWriter(owner);
            if (!claim(callback.updateId(), now)) return Outcome.of(Kind.DUPLICATE);
            if (owner == null) return Outcome.of(Kind.UNKNOWN);
            return transition.apply(owner);
        });
    }

    private Outcome expireOrAlready(String column, String hash, Instant now) {
        var rows = jdbc.query(returning("""
                UPDATE investment_thesis_approval_requests
                   SET status='EXPIRED', status_reason='EXPIRED', updated_at=?
                 WHERE %s=? AND status IN ('PENDING','AWAITING_CONFIRM')
                   AND (expires_at<=? OR confirm_expires_at<=?)
                """.formatted(column)), ROW, ts(now), hash, ts(now), ts(now));
        return rows.isEmpty() ? Outcome.of(Kind.ALREADY_PROCESSED) : new Outcome(Kind.EXPIRED, rows.getFirst(), null);
    }

    private boolean claim(long updateId, Instant now) {
        return jdbc.update("INSERT INTO telegram_webhook_updates(update_id, received_at) VALUES (?, ?) "
                + "ON CONFLICT (update_id) DO NOTHING", updateId, ts(now)) == 1;
    }

    private boolean claimAlone(Callback callback, Instant now) {
        return Boolean.TRUE.equals(transaction.execute(status -> claim(callback.updateId(), now)));
    }

    /** 상세 검토: read-only, does not consume the token; unknown or closed tokens reveal nothing. */
    private void detail(Callback callback, String hash, Instant now) {
        if (!claimAlone(callback, now)) return;
        var request = jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE
                + " WHERE token_sha256=? AND status IN ('PENDING','AWAITING_CONFIRM') AND expires_at>?", ROW, hash, ts(now)).stream().findFirst().orElse(null);
        if (request == null) {
            answer(callback, UNAVAILABLE);
            return;
        }
        answer(callback, "상세 검토 내용을 보냅니다.");
        try {
            client().sendMessage(detailText(request), List.of());
        } catch (TelegramInteractiveException failure) {
            log.warn("Telegram approval detail delivery failed: {}", failure.reason());
        }
    }

    // ------------------------------------------------------------------ post-commit replies

    private void post(Callback callback, Outcome outcome) {
        switch (outcome.kind()) {
            case DUPLICATE -> { }
            case UNKNOWN, ALREADY_PROCESSED -> answer(callback, UNAVAILABLE);
            case EXPIRED -> {
                answer(callback, "요청이 만료되었습니다. 투자 논리(thesis)는 변경되지 않았습니다.");
                closeKeyboards(outcome.request(), "만료됨. 투자 논리(thesis) 변경 없음.");
            }
            case HELD -> {
                answer(callback, "보류했습니다. 투자 논리(thesis)는 변경되지 않았습니다.");
                closeKeyboards(outcome.request(), "보류됨. 투자 논리(thesis) 변경 없음.");
            }
            case CONFLICT -> {
                answer(callback, "요청 이후 투자 논리(thesis)가 바뀌어 승인하지 않았습니다. 변경 없음.");
                closeKeyboards(outcome.request(), "충돌: 요청 이후 투자 논리(thesis)가 변경됨. 변경 없음.");
            }
            case FAILED -> {
                answer(callback, "승인을 처리하지 못했습니다. 투자 논리(thesis)는 변경되지 않았습니다.");
                closeKeyboards(outcome.request(), "실패. 투자 논리(thesis) 변경 없음.");
            }
            case AWAITING_CONFIRM -> stageTwo(callback, outcome);
            case APPROVED -> {
                answer(callback, "최종 승인 완료. 투자 논리(thesis)가 CONFIRMED 되었습니다.");
                closeKeyboards(outcome.request(), "최종 승인 완료.");
                sendResult(outcome.request());
            }
        }
    }

    private void stageTwo(Callback callback, Outcome outcome) {
        var request = outcome.request();
        answer(callback, "1단계 승인. " + confirmTtl.toMinutes() + "분 안에 [최종 승인]을 눌러야 확정됩니다.");
        edit(request.telegramMessageId(), header(request) + "\n1단계 승인됨 — 아래 최종 승인 메시지를 확인하세요.");
        var keyboard = List.of(List.of(
                new Button("최종 승인", "F:" + outcome.confirmToken()),
                new Button("취소", "X:" + outcome.confirmToken())));
        var text = header(request) + "\n[2단계] 최종 승인하면 투자 논리(thesis)가 CONFIRMED 되고 무효화 가격이 "
                + request.candidateTrigger().toPlainString() + "(으)로 바뀝니다. 다른 필드와 priceRiskTrigger 문구는 "
                + "그대로입니다. 주문은 생성되지 않습니다.\n최종 승인 기한: " + request.confirmExpiresAt();
        try {
            var messageId = client().sendMessage(text, keyboard);
            jdbc.update("UPDATE " + TABLE + " SET confirm_message_id=? WHERE id=?", messageId, request.id());
        } catch (TelegramInteractiveException failure) {
            log.warn("Telegram final-approval prompt delivery failed: {}", failure.reason());
            markSendFailed(request, "AWAITING_CONFIRM");
        }
    }

    private void markSendFailed(RequestRow request, String expectedStatus) {
        transaction.executeWithoutResult(status -> {
            investment.lockThesisWriter(request.userId());
            jdbc.update("UPDATE " + TABLE + " SET status='FAILED', status_reason='SEND_FAILED', updated_at=? "
                    + "WHERE id=? AND status=?", ts(clock.instant()), request.id(), expectedStatus);
        });
    }

    private void sendResult(RequestRow request) {
        var text = new StringBuilder(header(request)).append("\n투자 논리(thesis) CONFIRMED (Telegram 2단계 승인)")
                .append("\n무효화 가격: ").append(request.candidateTrigger().toPlainString())
                .append("\n주문은 생성되지 않았습니다.\n[확정 후 리스크]\n");
        try {
            var security = investment.context(request.userId()).securities().stream()
                    .filter(item -> request.ticker().equals(item.ticker())).findFirst().orElse(null);
            text.append(riskLines(security == null ? null : security.risk()));
        } catch (RuntimeException failure) {
            log.warn("Investment context re-read after approval failed: {}", failure.getClass().getSimpleName());
            text.append("리스크 재조회 실패");
        }
        try {
            client().sendMessage(text.toString(), List.of());
        } catch (TelegramInteractiveException failure) {
            log.warn("Telegram approval result delivery failed: {}", failure.reason());
        }
    }

    private void closeKeyboards(RequestRow request, String status) {
        var text = header(request) + "\n" + status;
        edit(request.telegramMessageId(), text);
        edit(request.confirmMessageId(), text);
    }

    private void edit(Long messageId, String text) {
        if (messageId == null) return;
        try {
            client().editMessageText(messageId, text);
        } catch (TelegramInteractiveException failure) {
            log.warn("Telegram approval message edit failed: {}", failure.reason());
        }
    }

    private void answer(Callback callback, String text) {
        try {
            client().answerCallbackQuery(callback.callbackQueryId(), text);
        } catch (TelegramInteractiveException failure) {
            log.warn("Telegram callback answer failed: {}", failure.reason());
        }
    }

    private TelegramInteractiveClient client() {
        var client = clients.getIfAvailable();
        if (client == null) throw new TelegramInteractiveException("CLIENT_UNAVAILABLE");
        return client;
    }

    // ------------------------------------------------------------------ message text (no parse_mode, no amounts)

    private String requestText(RequestRow request, InvestmentContextService.ThesisView thesis,
                               InvestmentContextService.RiskContributionView hypothetical) {
        return "[투자 논리(thesis) 확정 승인 요청]\n" + header(request)
                + "\n현재 상태: " + thesis.invalidationStatus()
                + "\n후보 무효화 가격: " + request.candidateTrigger().toPlainString()
                + "\n기준 시각(sourceAsOf): " + request.sourceAsOf()
                + "\n[가정 리스크 — 확정 시, 아직 반영 안 됨]\n" + riskLines(hypothetical)
                + "\n요청 만료: " + request.expiresAt()
                + "\n[승인] 후 [최종 승인]까지 2단계를 거쳐야 확정됩니다. 주문은 생성되지 않습니다.";
    }

    private String detailText(RequestRow request) {
        var text = new StringBuilder("[상세 검토]\n").append(header(request))
                .append("\n후보 무효화 가격: ").append(request.candidateTrigger().toPlainString())
                .append("\n기준 시각(sourceAsOf): ").append(request.sourceAsOf())
                .append("\n요청 시점 투자 논리(thesis) 버전: ").append(request.thesisExpectedUpdatedAt())
                .append("\n입력값:");
        var inputs = mapper.readTree(request.inputs());
        inputs.properties().forEach(entry -> text.append("\n- ").append(entry.getKey()).append(": ")
                .append(entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString()));
        text.append("\n현재 계산 후보:");
        try {
            for (var candidate : candidates.candidates(request.userId(), request.ticker()).values()) {
                text.append("\n- ").append(candidate.source()).append(": ").append(candidate.available()
                        ? candidate.trigger().toPlainString() + " (sourceAsOf " + candidate.sourceAsOf() + ")"
                        : "UNVERIFIED (" + candidate.reason() + ")");
            }
        } catch (RuntimeException failure) {
            log.warn("Candidate recomputation for detail failed: {}", failure.getClass().getSimpleName());
            text.append("\n- 재계산 불가");
        }
        text.append("\n[가정 리스크 — 확정 시, 아직 반영 안 됨]\n")
                .append(riskLines(hypotheticalRisk(request.userId(), request.ticker(), request.candidateTrigger())))
                .append("\n참고: 승인은 숫자 무효화 가격과 상태만 바꾸며 priceRiskTrigger 문구는 바꾸지 않습니다. "
                        + "문구와 숫자가 서로 다를 수 있습니다.");
        return text.toString();
    }

    private static String header(RequestRow request) {
        return "종목: " + request.ticker() + " | 후보 출처: " + request.candidateSource();
    }

    private static String riskLines(InvestmentContextService.RiskContributionView risk) {
        if (risk == null) return NO_RISK;
        return "sizingEligible: " + (risk.sizingEligible() ? "예" : "아니오")
                + "\neligibilityReasons: " + (risk.eligibilityReasons().isEmpty() ? "없음"
                : String.join(", ", risk.eligibilityReasons()))
                + "\n무효화 하락폭: " + percent(risk.invalidationDownside())
                + "\n계획 손실 기여: " + percent(risk.plannedLossContribution());
    }

    private static String percent(BigDecimal fraction) {
        return fraction == null ? "미산출"
                : fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private InvestmentContextService.RiskContributionView hypotheticalRisk(UUID userId, String ticker,
                                                                            BigDecimal trigger) {
        try {
            return investment.hypotheticalRisk(userId, ticker, trigger);
        } catch (RuntimeException failure) {
            log.warn("Hypothetical risk evaluation failed: {}", failure.getClass().getSimpleName());
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    static InvestmentContextService.ThesisInput confirmedInput(InvestmentContextService.ThesisView thesis,
                                                               BigDecimal trigger) {
        return new InvestmentContextService.ThesisInput(thesis.coreThesis(), thesis.upsideDriver(),
                thesis.expectationsGap(), thesis.fundamentalInvalidation(), thesis.revisionInvalidation(),
                thesis.priceRiskTrigger(), trigger, "CONFIRMED", thesis.expandTrigger(),
                thesis.exitOrDiscardTrigger(), thesis.classification());
    }

    private RequestRow find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id=?", ROW, id).getFirst();
    }

    private RequestView view(RequestRow row) {
        return new RequestView(row.id(), row.ticker(), row.candidateSource(), row.candidateTrigger(),
                mapper.readTree(row.inputs()), row.sourceAsOf(), row.thesisExpectedUpdatedAt(), row.status(),
                row.statusReason(), row.expiresAt(), row.confirmExpiresAt(), row.decidedAt(), row.createdAt(),
                row.updatedAt());
    }

    static String newToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String returning(String update) {
        return update.strip() + " RETURNING " + COLUMNS;
    }

    private static OffsetDateTime ts(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        var value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || !value.isPositive()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static final RowMapper<RequestRow> ROW = (rs, row) -> new RequestRow(
            rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("ticker"),
            rs.getString("candidate_source"), rs.getBigDecimal("candidate_trigger"), rs.getString("inputs"),
            instant(rs, "source_as_of"), instant(rs, "thesis_expected_updated_at"), rs.getString("status"),
            rs.getString("status_reason"), instant(rs, "expires_at"), instant(rs, "confirm_expires_at"),
            nullableLong(rs, "telegram_message_id"), nullableLong(rs, "confirm_message_id"),
            instant(rs, "decided_at"), instant(rs, "created_at"), instant(rs, "updated_at"));

    private record RequestRow(UUID id, UUID userId, String ticker, String candidateSource, BigDecimal candidateTrigger,
                              String inputs, Instant sourceAsOf, Instant thesisExpectedUpdatedAt, String status,
                              String statusReason, Instant expiresAt, Instant confirmExpiresAt,
                              Long telegramMessageId, Long confirmMessageId, Instant decidedAt, Instant createdAt,
                              Instant updatedAt) {
    }

    private record Closed(List<RequestRow> expired, List<RequestRow> superseded) {
        Closed {
            expired = new ArrayList<>(expired);
            superseded = new ArrayList<>(superseded);
        }
    }

    private enum Kind { DUPLICATE, UNKNOWN, ALREADY_PROCESSED, EXPIRED, AWAITING_CONFIRM, APPROVED, HELD, CONFLICT, FAILED }

    private record Outcome(Kind kind, RequestRow request, String confirmToken) {
        static Outcome of(Kind kind) {
            return new Outcome(kind, null, null);
        }
    }

    /** One authenticated Telegram callback_query. */
    public record Callback(long updateId, long chatId, long fromId, String callbackQueryId, String data) {
    }

    /** Public request view; token hashes and Telegram message ids are deliberately absent. */
    public record RequestView(UUID id, String ticker, String candidateSource, BigDecimal candidateTrigger,
                              JsonNode inputs, Instant sourceAsOf, Instant thesisExpectedUpdatedAt, String status,
                              String statusReason, Instant expiresAt, Instant confirmExpiresAt, Instant decidedAt,
                              Instant createdAt, Instant updatedAt) {
    }

    /** Creation precondition failure; {@code detail} is a fixed code (never user data). */
    public static final class Rejected extends RuntimeException {
        public enum Reason { NOT_READY, INVALID_SOURCE, THESIS_NOT_FOUND, THESIS_CHANGED, TRIGGER_MISSING,
            CANDIDATE_UNVERIFIED, ALREADY_CONFIRMED, INVALID_THESIS }

        private final Reason reason;
        private final String detail;

        Rejected(Reason reason, String detail) {
            super(reason.name(), null, false, false);
            this.reason = reason;
            this.detail = detail;
        }

        public Reason reason() {
            return reason;
        }

        public String detail() {
            return detail;
        }
    }
}
