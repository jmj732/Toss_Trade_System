package com.jmj.trade.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public final class AccessTokenService {

    private static final String HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    AccessTokenService(
            @Value("${security.access-token-signing-secret}")
            String secret,
            @Value("${security.access-token-ttl:PT20M}") Duration ttl
    ) {
        this(secret, ttl, Clock.systemUTC());
    }

    AccessTokenService(String secret, Duration ttl, Clock clock) {
        this(secret, ttl, clock, new ObjectMapper());
    }

    private AccessTokenService(String secret, Duration ttl, Clock clock, ObjectMapper objectMapper) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("access token signing secret must be at least 32 bytes");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("access token ttl must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant authenticatedAt) {
        var now = clock.instant();
        var expiresAt = now.plus(ttl);
        var authTime = authenticatedAt == null ? "" : ",\"auth_time\":" + authenticatedAt.getEpochSecond();
        var payload = "{\"sub\":\"" + userId + "\",\"sid\":\"" + sessionId
                + "\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + expiresAt.getEpochSecond()
                + authTime + ",\"amr\":\"oidc\"}";
        var encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        var value = HEADER + "." + encodedPayload;
        return new IssuedAccessToken(value + "." + encode(sign(value)), expiresAt);
    }

    Claims parse(String token) {
        try {
            var parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])) {
                throw invalid();
            }
            var expected = sign(parts[0] + "." + parts[1]);
            var actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw invalid();
            }
            JsonNode node = objectMapper.readTree(new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            var userId = UUID.fromString(requiredText(node, "sub"));
            var sessionId = UUID.fromString(requiredText(node, "sid"));
            var issuedAt = Instant.ofEpochSecond(requiredLong(node, "iat"));
            var expiresAt = Instant.ofEpochSecond(requiredLong(node, "exp"));
            var now = clock.instant();
            if (!expiresAt.isAfter(now) || issuedAt.isAfter(now.plusSeconds(60))
                    || !"oidc".equals(requiredText(node, "amr"))) {
                throw invalid();
            }
            var authenticatedAt = node.has("auth_time") && !node.get("auth_time").isNull()
                    ? Instant.ofEpochSecond(node.get("auth_time").longValue()) : null;
            return new Claims(userId, sessionId, issuedAt, expiresAt, authenticatedAt);
        } catch (JacksonException | IllegalArgumentException | ArithmeticException exception) {
            throw invalid();
        }
    }

    private byte[] sign(String input) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    String cookieSignature(String value) {
        return encode(sign(value));
    }

    boolean validCookieSignature(String value, String signature) {
        try {
            return MessageDigest.isEqual(sign(value), Base64.getUrlDecoder().decode(signature));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String requiredText(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid();
        }
        return value.asText();
    }

    private static long requiredLong(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isNumber()) {
            throw invalid();
        }
        return value.longValue();
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static InvalidAccessTokenException invalid() {
        return new InvalidAccessTokenException();
    }

    public record IssuedAccessToken(String value, Instant expiresAt) {
    }

    record Claims(
            UUID userId,
            UUID sessionId,
            Instant issuedAt,
            Instant expiresAt,
            Instant authenticatedAt
    ) {
    }

    static final class InvalidAccessTokenException extends RuntimeException {
        InvalidAccessTokenException() {
            super("invalid access token");
        }
    }
}
