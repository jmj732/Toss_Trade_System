package com.jmj.trade.prediction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PredictionIngestionApiKeyService {

    private static final Logger LOG =
            LoggerFactory.getLogger(PredictionIngestionApiKeyService.class);
    private static final String RAW_PREFIX = "tpik_";
    private static final int DISPLAY_PREFIX_LENGTH = 13;

    private final JdbcTemplate jdbc;
    private final PredictionModelRegistryService registry;
    private final TransactionTemplate transactions;
    private final SecureRandom random;
    private final Clock clock;

    public PredictionIngestionApiKeyService(
            JdbcTemplate jdbc,
            PredictionModelRegistryService registry,
            TransactionTemplate transactions,
            SecureRandom random,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    IssuedKey issue(UUID userId, IssueCommand command) {
        validate(command);
        return transactions.execute(status -> {
            if (!registry.lockActive(userId, command.modelVersion(), command.contractVersion())) {
                throw new ApiKeyException(ApiKeyException.Code.MODEL_SCOPE_NOT_ACTIVE);
            }
            return insert(
                    userId,
                    command.modelVersion(),
                    command.contractVersion(),
                    command.expiresAt());
        });
    }

    List<KeyView> list(UUID userId) {
        return jdbc.query("""
                SELECT id, model_version, contract_version, key_prefix, status,
                       created_at, last_used_at, revoked_at, expires_at
                  FROM prediction_ingestion_api_keys
                 WHERE user_id = ?
                 ORDER BY created_at, id
                """, PredictionIngestionApiKeyService::view, userId);
    }

    IssuedKey rotate(UUID userId, UUID id, Instant expiresAt) {
        return transactions.execute(status -> {
            var current = jdbc.query("""
                    SELECT model_version, contract_version, status, expires_at
                      FROM prediction_ingestion_api_keys
                     WHERE user_id = ?
                       AND id = ?
                     FOR UPDATE
                    """, (result, row) -> new StoredKey(
                            result.getString("model_version"),
                            result.getString("contract_version"),
                            Status.valueOf(result.getString("status")),
                            instant(result, "expires_at")),
                    userId, id).stream().findFirst()
                    .orElseThrow(() -> new ApiKeyException(ApiKeyException.Code.NOT_FOUND));
            if (current.status() != Status.ACTIVE) {
                throw new ApiKeyException(ApiKeyException.Code.NOT_ACTIVE);
            }
            if (!registry.lockActive(userId, current.modelVersion(), current.contractVersion())) {
                throw new ApiKeyException(ApiKeyException.Code.MODEL_SCOPE_NOT_ACTIVE);
            }
            var replacementExpiry = expiresAt == null ? current.expiresAt() : expiresAt;
            validateExpiry(replacementExpiry);
            jdbc.update("""
                    UPDATE prediction_ingestion_api_keys
                       SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """, id);
            return insert(
                    userId,
                    current.modelVersion(),
                    current.contractVersion(),
                    replacementExpiry);
        });
    }

    void revoke(UUID userId, UUID id) {
        jdbc.update("""
                UPDATE prediction_ingestion_api_keys
                   SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
                 WHERE user_id = ?
                   AND id = ?
                   AND status = 'ACTIVE'
                """, userId, id);
    }

    Optional<AuthenticatedKey> findActive(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(RAW_PREFIX)) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT id, user_id, model_version, contract_version, key_prefix, expires_at,
                       status = 'EXPIRED'
                         OR (expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP)
                         AS expired
                  FROM prediction_ingestion_api_keys
                 WHERE key_hash = ?
                   AND status IN ('ACTIVE', 'EXPIRED')
                """, (result, row) -> new AuthenticatedKey(
                        result.getObject("id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getString("key_prefix"),
                        new AnalysisPredictionService.ModelContractScope(
                                result.getString("model_version"),
                                result.getString("contract_version")),
                        instant(result, "expires_at"),
                        result.getBoolean("expired")),
                hash(rawKey)).stream().findFirst();
    }

    boolean markUsed(UUID id) {
        return Boolean.TRUE.equals(transactions.execute(status -> jdbc.query("""
                UPDATE prediction_ingestion_api_keys
                   SET last_used_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                   AND status = 'ACTIVE'
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                RETURNING true
                """, (result, row) -> result.getBoolean(1), id)
                .stream()
                .findFirst()
                .orElse(false)));
    }

    void recordRejection(AuthenticatedKey key, RejectionReason reason) {
        jdbc.update("""
                INSERT INTO prediction_ingestion_api_key_rejections (
                    id, key_id, user_id, key_prefix, reason, occurred_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), key.id(), key.userId(), key.prefix(), reason.name());
        LOG.atWarn()
                .addKeyValue("operation", "prediction_ingestion_api_key_rejected")
                .addKeyValue("key_id", key.id())
                .addKeyValue("key_prefix", key.prefix())
                .addKeyValue("reason", reason.name())
                .log("prediction ingestion API key request rejected");
    }

    private IssuedKey insert(
            UUID userId,
            String modelVersion,
            String contractVersion,
            Instant expiresAt
    ) {
        var rawKey = rawKey();
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prediction_ingestion_api_keys (
                    id, user_id, model_version, contract_version, key_hash, key_prefix,
                    status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, ?)
                """, id, userId, modelVersion, contractVersion, hash(rawKey),
                rawKey.substring(0, DISPLAY_PREFIX_LENGTH),
                expiresAt == null
                        ? null
                        : OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
        var key = find(userId, id);
        return new IssuedKey(
                key.id(), rawKey, key.modelVersion(), key.contractVersion(), key.prefix(),
                key.status(), key.createdAt(), key.expiresAt());
    }

    private KeyView find(UUID userId, UUID id) {
        return jdbc.query("""
                SELECT id, model_version, contract_version, key_prefix, status,
                       created_at, last_used_at, revoked_at, expires_at
                  FROM prediction_ingestion_api_keys
                 WHERE user_id = ?
                   AND id = ?
                """, PredictionIngestionApiKeyService::view, userId, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiKeyException(ApiKeyException.Code.NOT_FOUND));
    }

    private String rawKey() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return RAW_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawKey) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void validate(IssueCommand command) {
        if (command == null
                || invalidVersion(command.modelVersion())
                || invalidVersion(command.contractVersion())) {
            throw new ApiKeyException(ApiKeyException.Code.INVALID_INPUT);
        }
        validateExpiry(command.expiresAt());
    }

    private void validateExpiry(Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new ApiKeyException(ApiKeyException.Code.INVALID_INPUT);
        }
    }

    private static boolean invalidVersion(String value) {
        return value == null || value.isBlank() || value.length() > 50;
    }

    private static KeyView view(ResultSet result, int row) throws SQLException {
        return new KeyView(
                result.getObject("id", UUID.class),
                result.getString("model_version"),
                result.getString("contract_version"),
                result.getString("key_prefix"),
                Status.valueOf(result.getString("status")),
                instant(result, "created_at"),
                instant(result, "last_used_at"),
                instant(result, "revoked_at"),
                instant(result, "expires_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    enum Status {
        ACTIVE,
        EXPIRED,
        REVOKED
    }

    record IssueCommand(String modelVersion, String contractVersion, Instant expiresAt) {
    }

    record KeyView(
            UUID id,
            String modelVersion,
            String contractVersion,
            String prefix,
            Status status,
            Instant createdAt,
            Instant lastUsedAt,
            Instant revokedAt,
            Instant expiresAt
    ) {
    }

    record IssuedKey(
            UUID id,
            String apiKey,
            String modelVersion,
            String contractVersion,
            String prefix,
            Status status,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    record AuthenticatedKey(
            UUID id,
            UUID userId,
            String prefix,
            AnalysisPredictionService.ModelContractScope scope,
            Instant expiresAt,
            boolean expired
    ) {
    }

    enum RejectionReason {
        EXPIRED,
        RATE_LIMITED
    }

    private record StoredKey(
            String modelVersion,
            String contractVersion,
            Status status,
            Instant expiresAt
    ) {
    }

    static final class ApiKeyException extends RuntimeException {
        private final Code code;

        ApiKeyException(Code code) {
            super(code.name());
            this.code = code;
        }

        Code code() {
            return code;
        }

        enum Code {
            INVALID_INPUT,
            MODEL_SCOPE_NOT_ACTIVE,
            NOT_FOUND,
            NOT_ACTIVE
        }
    }
}
