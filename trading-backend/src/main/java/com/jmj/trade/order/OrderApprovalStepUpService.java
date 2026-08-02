package com.jmj.trade.order;

import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 승인 step-up 재인증 토큰 발급·검증 (플랜 원장 E2).
 *
 * <p>발급은 사람이 최근에 재인증했음을 근거로만 이뤄진다. 근거는 OIDC 표준 {@code auth_time}
 * (IdP 가 단언한 최종 인증 시각)이며, 신선도 창을 벗어나거나 값이 없으면 fail-closed 로 거절한다.
 * 아무 검증 없이 발급되는 토큰은 보안 연극이므로 만들지 않는다.
 *
 * <p>토큰은 짧은 수명 + 단일 사용 + (사용자, 승인 대상) 바인딩이다. 원문은 반환 직후 버리고
 * 저장은 SHA-256 해시로만 한다(SPEC:1151). 검증은 원자적 compare-and-set 소비로,
 * 만료·재사용·타 사용자·타 주문 토큰은 모두 실패시켜 승인 계층이 401 로 매핑한다.
 */
public final class OrderApprovalStepUpService {

    private static final int TOKEN_BYTES = 32;

    private final JdbcTemplate jdbc;
    private final SecureRandom random;
    private final Clock clock;
    private final Duration reauthFreshness;
    private final Duration tokenTtl;

    public OrderApprovalStepUpService(
            JdbcTemplate jdbc,
            SecureRandom random,
            Clock clock,
            Duration reauthFreshness,
            Duration tokenTtl
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reauthFreshness = requirePositive(reauthFreshness, "reauthFreshness");
        this.tokenTtl = requirePositive(tokenTtl, "tokenTtl");
    }

    /**
     * 최근 OIDC 재인증({@code authTime})을 근거로 (사용자, 승인 대상)에 바인딩된 단일 사용 토큰을
     * 발급한다. {@code authTime} 이 없거나 신선도 창을 벗어나면 재인증을 요구한다.
     */
    IssuedStepUp issue(UUID userId, UUID orderIntentId, Instant authTime) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(orderIntentId, "orderIntentId");
        var now = clock.instant();
        requireFreshReauthentication(authTime, now);

        var rawToken = randomToken();
        var expiresAt = now.plus(tokenTtl);
        jdbc.update("""
                INSERT INTO order_approval_step_up_tokens (
                    token_hash, user_id, order_intent_id, issued_at, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, ?, NULL)
                """,
                sha256Hex(rawToken),
                userId,
                orderIntentId,
                at(now),
                at(expiresAt));
        return new IssuedStepUp(rawToken, expiresAt);
    }

    /**
     * 주문이 아닌 일반 subject(예: kill switch 해제 대상, 플랜 원장 E4)에 바인딩된 단일 사용 토큰을
     * 발급한다. E2 의 주문 승인 토큰과 같은 신선도·수명·해시 저장 규칙을 그대로 쓰되, 바인딩만
     * (사용자, subjectKind, subjectRef) 로 일반화한다. {@code order_intent_id} 는 NULL 로 두어
     * 주문 소유권 FK 를 건너뛴다(주문 아님).
     */
    IssuedStepUp issueForSubject(UUID userId, String subjectKind, UUID subjectRef, Instant authTime) {
        Objects.requireNonNull(userId, "userId");
        requireSubject(subjectKind, subjectRef);
        var now = clock.instant();
        requireFreshReauthentication(authTime, now);

        var rawToken = randomToken();
        var expiresAt = now.plus(tokenTtl);
        jdbc.update("""
                INSERT INTO order_approval_step_up_tokens (
                    token_hash, user_id, order_intent_id, subject_kind, subject_ref,
                    issued_at, expires_at, consumed_at
                ) VALUES (?, ?, NULL, ?, ?, ?, ?, NULL)
                """,
                sha256Hex(rawToken),
                userId,
                subjectKind,
                subjectRef,
                at(now),
                at(expiresAt));
        return new IssuedStepUp(rawToken, expiresAt);
    }

    /**
     * (사용자, subjectKind, subjectRef)에 바인딩된 미사용·미만료 토큰을 원자적으로 소비한다(E4).
     * 만료·재사용·타 사용자·타 대상·형식 오류는 모두 {@link PaperOrderWorkflowException#stepUpRequired()}
     * 로 거절한다. 호출 트랜잭션이 롤백되면 소비도 함께 롤백된다.
     */
    void consumeForSubject(UUID userId, String subjectKind, UUID subjectRef, String rawToken) {
        Objects.requireNonNull(userId, "userId");
        requireSubject(subjectKind, subjectRef);
        if (rawToken == null || rawToken.isBlank()) {
            throw PaperOrderWorkflowException.stepUpRequired();
        }
        var now = clock.instant();
        var updated = jdbc.update("""
                UPDATE order_approval_step_up_tokens
                   SET consumed_at = ?
                 WHERE token_hash = ?
                   AND user_id = ?
                   AND subject_kind = ?
                   AND subject_ref = ?
                   AND consumed_at IS NULL
                   AND expires_at > ?
                """,
                at(now),
                sha256Hex(rawToken),
                userId,
                subjectKind,
                subjectRef,
                at(now));
        if (updated != 1) {
            throw PaperOrderWorkflowException.stepUpRequired();
        }
    }

    /**
     * (사용자, 승인 대상)에 바인딩된 미사용·미만료 토큰을 원자적으로 소비한다. 만료·재사용·타 사용자·
     * 타 주문·형식 오류는 모두 {@link PaperOrderWorkflowException#stepUpRequired()} 로 거절한다.
     * 승인 트랜잭션 안에서 호출되어, 승인이 롤백되면 소비도 함께 롤백된다.
     */
    void consume(UUID userId, UUID orderIntentId, String rawToken) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(orderIntentId, "orderIntentId");
        if (rawToken == null || rawToken.isBlank()) {
            throw PaperOrderWorkflowException.stepUpRequired();
        }
        var now = clock.instant();
        var updated = jdbc.update("""
                UPDATE order_approval_step_up_tokens
                   SET consumed_at = ?
                 WHERE token_hash = ?
                   AND user_id = ?
                   AND order_intent_id = ?
                   AND consumed_at IS NULL
                   AND expires_at > ?
                """,
                at(now),
                sha256Hex(rawToken),
                userId,
                orderIntentId,
                at(now));
        if (updated != 1) {
            throw PaperOrderWorkflowException.stepUpRequired();
        }
    }

    private void requireFreshReauthentication(Instant authTime, Instant now) {
        if (authTime == null
                || authTime.isAfter(now.plusSeconds(60))
                || Duration.between(authTime, now).compareTo(reauthFreshness) > 0) {
            throw PaperOrderWorkflowException.stepUpRequired();
        }
    }

    private String randomToken() {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String rawToken) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireSubject(String subjectKind, UUID subjectRef) {
        if (subjectKind == null || subjectKind.isBlank() || "ORDER_APPROVAL".equals(subjectKind)) {
            // 주문 승인 토큰은 (userId, orderIntentId) 바인딩 메서드를 써야 한다. 일반 subject 경로는
            // order_intent_id 를 NULL 로 두므로 ORDER_APPROVAL 을 여기로 흘리면 CHECK 제약에 걸린다.
            throw new IllegalArgumentException("subjectKind must be a non-order subject");
        }
        Objects.requireNonNull(subjectRef, "subjectRef");
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public record IssuedStepUp(String stepUpToken, Instant expiresAt) {
        public IssuedStepUp {
            Objects.requireNonNull(stepUpToken, "stepUpToken");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
