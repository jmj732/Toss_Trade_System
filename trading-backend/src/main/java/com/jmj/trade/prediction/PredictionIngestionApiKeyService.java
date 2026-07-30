package com.jmj.trade.prediction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PredictionIngestionApiKeyService {

    private static final String RAW_PREFIX = "tpik_";
    private static final int DISPLAY_PREFIX_LENGTH = 13;

    private final JdbcTemplate jdbc;
    private final PredictionModelRegistryService registry;
    private final TransactionTemplate transactions;
    private final SecureRandom random;

    public PredictionIngestionApiKeyService(
            JdbcTemplate jdbc,
            PredictionModelRegistryService registry,
            TransactionTemplate transactions,
            SecureRandom random
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.random = Objects.requireNonNull(random, "random");
    }

    IssuedKey issue(UUID userId, IssueCommand command) {
        validate(command);
        return transactions.execute(status -> {
            if (!registry.lockActive(userId, command.modelVersion(), command.contractVersion())) {
                throw new ApiKeyException(ApiKeyException.Code.MODEL_SCOPE_NOT_ACTIVE);
            }
            return insert(userId, command.modelVersion(), command.contractVersion());
        });
    }

    List<KeyView> list(UUID userId) {
        return jdbc.query("""
                SELECT id, model_version, contract_version, key_prefix, status,
                       created_at, last_used_at, revoked_at
                  FROM prediction_ingestion_api_keys
                 WHERE user_id = ?
                 ORDER BY created_at, id
                """, PredictionIngestionApiKeyService::view, userId);
    }

    IssuedKey rotate(UUID userId, UUID id) {
        return transactions.execute(status -> {
            var current = jdbc.query("""
                    SELECT model_version, contract_version, status
                      FROM prediction_ingestion_api_keys
                     WHERE user_id = ?
                       AND id = ?
                     FOR UPDATE
                    """, (result, row) -> new StoredKey(
                            result.getString("model_version"),
                            result.getString("contract_version"),
                            Status.valueOf(result.getString("status"))),
                    userId, id).stream().findFirst()
                    .orElseThrow(() -> new ApiKeyException(ApiKeyException.Code.NOT_FOUND));
            if (current.status() != Status.ACTIVE) {
                throw new ApiKeyException(ApiKeyException.Code.NOT_ACTIVE);
            }
            if (!registry.lockActive(userId, current.modelVersion(), current.contractVersion())) {
                throw new ApiKeyException(ApiKeyException.Code.MODEL_SCOPE_NOT_ACTIVE);
            }
            jdbc.update("""
                    UPDATE prediction_ingestion_api_keys
                       SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """, id);
            return insert(userId, current.modelVersion(), current.contractVersion());
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

    Optional<AuthenticatedKey> authenticate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(RAW_PREFIX)) {
            return Optional.empty();
        }
        return transactions.execute(status -> {
            var authenticated = jdbc.query("""
                    SELECT id, user_id, model_version, contract_version
                      FROM prediction_ingestion_api_keys
                     WHERE key_hash = ?
                       AND status = 'ACTIVE'
                     FOR UPDATE
                    """, (result, row) -> new AuthenticatedKey(
                            result.getObject("id", UUID.class),
                            result.getObject("user_id", UUID.class),
                            new AnalysisPredictionService.ModelContractScope(
                                    result.getString("model_version"),
                                    result.getString("contract_version"))),
                    hash(rawKey)).stream().findFirst();
            authenticated.ifPresent(key -> jdbc.update("""
                    UPDATE prediction_ingestion_api_keys
                       SET last_used_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """, key.id()));
            return authenticated;
        });
    }

    private IssuedKey insert(UUID userId, String modelVersion, String contractVersion) {
        var rawKey = rawKey();
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prediction_ingestion_api_keys (
                    id, user_id, model_version, contract_version, key_hash, key_prefix,
                    status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                """, id, userId, modelVersion, contractVersion, hash(rawKey),
                rawKey.substring(0, DISPLAY_PREFIX_LENGTH));
        var key = find(userId, id);
        return new IssuedKey(
                key.id(), rawKey, key.modelVersion(), key.contractVersion(), key.prefix(),
                key.status(), key.createdAt());
    }

    private KeyView find(UUID userId, UUID id) {
        return jdbc.query("""
                SELECT id, model_version, contract_version, key_prefix, status,
                       created_at, last_used_at, revoked_at
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

    private static void validate(IssueCommand command) {
        if (command == null
                || invalidVersion(command.modelVersion())
                || invalidVersion(command.contractVersion())) {
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
                instant(result, "revoked_at"));
    }

    private static java.time.Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    enum Status {
        ACTIVE,
        REVOKED
    }

    record IssueCommand(String modelVersion, String contractVersion) {
    }

    record KeyView(
            UUID id,
            String modelVersion,
            String contractVersion,
            String prefix,
            Status status,
            java.time.Instant createdAt,
            java.time.Instant lastUsedAt,
            java.time.Instant revokedAt
    ) {
    }

    record IssuedKey(
            UUID id,
            String apiKey,
            String modelVersion,
            String contractVersion,
            String prefix,
            Status status,
            java.time.Instant createdAt
    ) {
    }

    record AuthenticatedKey(
            UUID id,
            UUID userId,
            AnalysisPredictionService.ModelContractScope scope
    ) {
    }

    private record StoredKey(String modelVersion, String contractVersion, Status status) {
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
