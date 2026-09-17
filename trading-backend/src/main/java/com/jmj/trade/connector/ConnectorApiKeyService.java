package com.jmj.trade.connector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ConnectorApiKeyService {

    private static final String RAW_PREFIX = "ckey_";
    private static final int DISPLAY_PREFIX_LENGTH = 13;
    public static final String READ_SCOPE = "connector:read";
    public static final String TRADE_SCOPE = "connector:trade";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SecureRandom random;
    private final Clock clock;

    public ConnectorApiKeyService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SecureRandom random,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public IssuedKey issue(UUID userId, UUID connectionId, Instant expiresAt) {
        return issue(userId, connectionId, expiresAt, READ_SCOPE);
    }

    public IssuedKey issue(UUID userId, UUID connectionId, Instant expiresAt, String scope) {
        requireIds(userId, connectionId);
        requireScope(scope);
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new ApiKeyException(Code.INVALID_INPUT);
        }
        return transactions.execute(status -> {
            var exists = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM broker_connections
                     WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                    """, Integer.class, connectionId, userId);
            if (!Integer.valueOf(1).equals(exists)) {
                throw new ApiKeyException(Code.CONNECTION_NOT_FOUND);
            }
            var raw = rawKey();
            var id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO connector_api_keys
                        (id, user_id, connection_id, key_hash, key_prefix, scope, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, ?)
                    """, id, userId, connectionId, hash(raw), raw.substring(0, DISPLAY_PREFIX_LENGTH),
                    scope, expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
            var key = jdbc.query("""
                    SELECT id, connection_id, key_prefix, status, created_at, last_used_at, revoked_at, expires_at
                      FROM connector_api_keys WHERE id = ?
                    """, ConnectorApiKeyService::view, id).getFirst();
            return new IssuedKey(key.id(), raw, key.connectionId(), key.prefix(), key.status(),
                    key.createdAt(), key.expiresAt(), scope);
        });
    }

    public List<KeyView> list(UUID userId) {
        return jdbc.query("""
                SELECT id, connection_id, key_prefix,
                       CASE WHEN status = 'ACTIVE' AND expires_at IS NOT NULL
                                  AND expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE status END AS status,
                       created_at, last_used_at, revoked_at, expires_at
                  FROM connector_api_keys WHERE user_id = ? ORDER BY created_at, id
                """, ConnectorApiKeyService::view, userId);
    }

    public void revoke(UUID userId, UUID id) {
        var updated = jdbc.update("""
                UPDATE connector_api_keys SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
                 WHERE user_id = ? AND id = ? AND status = 'ACTIVE'
                """, userId, id);
        if (updated == 0) {
            throw new ApiKeyException(Code.NOT_FOUND);
        }
    }

    public Optional<AuthenticatedKey> findActive(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(RAW_PREFIX)) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT api_key.id, api_key.user_id, api_key.connection_id, api_key.key_prefix, api_key.scope, api_key.expires_at,
                       api_key.expires_at IS NOT NULL AND api_key.expires_at <= CURRENT_TIMESTAMP AS expired
                  FROM connector_api_keys api_key
                  JOIN broker_connections broker
                    ON broker.id = api_key.connection_id AND broker.user_id = api_key.user_id
                WHERE api_key.key_hash = ? AND api_key.status IN ('ACTIVE', 'EXPIRED')
                   AND broker.status = 'ACTIVE' AND broker.deleted_at IS NULL
                """, (rs, row) -> new AuthenticatedKey(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("connection_id", UUID.class), rs.getString("key_prefix"),
                instant(rs, "expires_at"), rs.getBoolean("expired"), rs.getString("scope")), hash(rawKey))
                .stream().findFirst();
    }

    public boolean markUsed(UUID id) {
        return Boolean.TRUE.equals(transactions.execute(status -> jdbc.query("""
                UPDATE connector_api_keys SET last_used_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'ACTIVE'
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                 RETURNING true
                """, (rs, row) -> rs.getBoolean(1), id).stream().findFirst().orElse(false)));
    }

    private String rawKey() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return RAW_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static KeyView view(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new KeyView(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getString("key_prefix"), Status.valueOf(rs.getString("status")),
                instant(rs, "created_at"), instant(rs, "last_used_at"),
                instant(rs, "revoked_at"), instant(rs, "expires_at"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void requireIds(UUID userId, UUID connectionId) {
        if (userId == null || connectionId == null) throw new ApiKeyException(Code.INVALID_INPUT);
    }

    private static void requireScope(String scope) {
        if (!READ_SCOPE.equals(scope) && !TRADE_SCOPE.equals(scope)) {
            throw new ApiKeyException(Code.INVALID_INPUT);
        }
    }

    public enum Status { ACTIVE, EXPIRED, REVOKED }

    public record KeyView(UUID id, UUID connectionId, String prefix, Status status,
                          Instant createdAt, Instant lastUsedAt, Instant revokedAt, Instant expiresAt) { }

    public record IssuedKey(UUID id, String apiKey, UUID connectionId, String prefix,
                            Status status, Instant createdAt, Instant expiresAt, String scope) {
        public IssuedKey(UUID id, String apiKey, UUID connectionId, String prefix,
                         Status status, Instant createdAt, Instant expiresAt) {
            this(id, apiKey, connectionId, prefix, status, createdAt, expiresAt, READ_SCOPE);
        }
    }

    public record AuthenticatedKey(UUID id, UUID userId, UUID connectionId,
                                   String prefix, Instant expiresAt, boolean expired, String scope) {
        public AuthenticatedKey(UUID id, UUID userId, UUID connectionId,
                                String prefix, Instant expiresAt, boolean expired) {
            this(id, userId, connectionId, prefix, expiresAt, expired, READ_SCOPE);
        }
        public AuthenticatedKey(UUID id, UUID userId, UUID connectionId, Instant expiresAt) {
            this(id, userId, connectionId, "", expiresAt, false, READ_SCOPE);
        }
        public boolean expired() { return expired; }
        public boolean canTrade() { return TRADE_SCOPE.equals(scope); }
    }

    public enum Code { INVALID_INPUT, CONNECTION_NOT_FOUND, NOT_FOUND }

    public static final class ApiKeyException extends RuntimeException {
        private final Code code;
        public ApiKeyException(Code code) { super(code.name()); this.code = code; }
        public Code code() { return code; }
    }
}
