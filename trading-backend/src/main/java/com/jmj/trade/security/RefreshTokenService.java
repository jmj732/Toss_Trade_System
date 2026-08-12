package com.jmj.trade.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;
    private final JdbcTemplate jdbc;
    private final SecureRandom random;
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    RefreshTokenService(
            JdbcTemplate jdbc,
            @Value("${security.refresh-token-ttl:P7D}") Duration ttl
    ) {
        this(jdbc, new SecureRandom(), Clock.systemUTC(), ttl);
    }

    RefreshTokenService(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, new SecureRandom(), clock, Duration.ofDays(7));
    }

    private RefreshTokenService(JdbcTemplate jdbc, SecureRandom random, Clock clock, Duration ttl) {
        this.jdbc = jdbc;
        this.random = random;
        this.clock = clock;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("refresh token ttl must be positive");
        }
        this.ttl = ttl;
    }

    @Transactional
    Rotation issue(UUID userId, Instant authenticatedAt) {
        var now = clock.instant();
        var raw = randomToken();
        var sessionId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        insert(sessionId, userId, familyId, raw, now, now.plus(ttl), authenticatedAt);
        return new Rotation(raw, sessionId, familyId, userId, authenticatedAt, now.plus(ttl));
    }

    @Transactional(noRollbackFor = {
            RefreshTokenReuseException.class,
            RefreshTokenExpiredException.class
    })
    Rotation rotate(String rawToken) {
        var now = clock.instant();
        var row = find(rawToken);
        if (row == null) {
            throw new InvalidRefreshTokenException();
        }
        if (row.revokedAt() != null || row.replacedByHash() != null) {
            revokeFamily(row.familyId(), now, true);
            throw new RefreshTokenReuseException();
        }
        if (!row.expiresAt().isAfter(now)) {
            revoke(row.sessionId(), now);
            throw new RefreshTokenExpiredException();
        }
        var nextRaw = randomToken();
        var nextSessionId = UUID.randomUUID();
        var nextExpiry = now.plus(ttl);
        insert(nextSessionId, row.userId(), row.familyId(), nextRaw, now, nextExpiry, row.authenticatedAt());
        var updated = jdbc.update("""
                UPDATE auth_refresh_sessions
                   SET replaced_by_hash = ?, revoked_at = ?, last_used_at = ?
                 WHERE id = ? AND revoked_at IS NULL AND replaced_by_hash IS NULL
                """, sha256Hex(nextRaw), at(now), at(now), row.sessionId());
        if (updated != 1) {
            revokeFamily(row.familyId(), now, true);
            throw new RefreshTokenReuseException();
        }
        return new Rotation(nextRaw, nextSessionId, row.familyId(), row.userId(), row.authenticatedAt(), nextExpiry);
    }

    @Transactional
    void revoke(String rawToken) {
        var row = find(rawToken);
        if (row != null) {
            revoke(row.sessionId(), clock.instant());
        }
    }

    @Transactional
    void revokeAll(UUID userId) {
        jdbc.update("""
                UPDATE auth_refresh_sessions
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE user_id = ? AND revoked_at IS NULL
                """, at(clock.instant()), userId);
    }

    private void insert(
            UUID sessionId,
            UUID userId,
            UUID familyId,
            String rawToken,
            Instant issuedAt,
            Instant expiresAt,
            Instant authenticatedAt
    ) {
        jdbc.update("""
                INSERT INTO auth_refresh_sessions (
                    id, user_id, family_id, token_hash, issued_at, last_used_at,
                    expires_at, authenticated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, userId, familyId, sha256Hex(rawToken), at(issuedAt), at(issuedAt),
                at(expiresAt), authenticatedAt == null ? null : at(authenticatedAt));
    }

    private Row find(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return jdbc.query("""
                SELECT id, user_id, family_id, expires_at, authenticated_at,
                       replaced_by_hash, revoked_at
                  FROM auth_refresh_sessions
                 WHERE token_hash = ?
                 FOR UPDATE
                """, (result, rowNumber) -> new Row(
                result.getObject("id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("family_id", UUID.class),
                result.getObject("expires_at", OffsetDateTime.class).toInstant(),
                optionalInstant(result.getObject("authenticated_at", OffsetDateTime.class)),
                result.getString("replaced_by_hash"),
                optionalInstant(result.getObject("revoked_at", OffsetDateTime.class))),
                sha256Hex(rawToken)).stream().findFirst().orElse(null);
    }

    private void revoke(UUID sessionId, Instant now) {
        jdbc.update("""
                UPDATE auth_refresh_sessions SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE id = ?
                """, at(now), sessionId);
    }

    private void revokeFamily(UUID familyId, Instant now, boolean reuseDetected) {
        jdbc.update("""
                UPDATE auth_refresh_sessions
                   SET revoked_at = COALESCE(revoked_at, ?),
                       reuse_detected_at = CASE WHEN ? THEN COALESCE(reuse_detected_at, ?) ELSE reuse_detected_at END
                 WHERE family_id = ?
                """, at(now), reuseDetected, at(now), familyId);
    }

    private String randomToken() {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant optionalInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record Row(
            UUID sessionId,
            UUID userId,
            UUID familyId,
            Instant expiresAt,
            Instant authenticatedAt,
            String replacedByHash,
            Instant revokedAt
    ) {
    }

    record Rotation(
            String refreshToken,
            UUID sessionId,
            UUID familyId,
            UUID userId,
            Instant authenticatedAt,
            Instant expiresAt
    ) {
    }

    static class InvalidRefreshTokenException extends RuntimeException {
    }

    static final class RefreshTokenExpiredException extends InvalidRefreshTokenException {
    }

    static final class RefreshTokenReuseException extends InvalidRefreshTokenException {
    }
}
