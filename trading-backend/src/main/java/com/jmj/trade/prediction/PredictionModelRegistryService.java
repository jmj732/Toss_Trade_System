package com.jmj.trade.prediction;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PredictionModelRegistryService {

    private final JdbcTemplate jdbc;

    public PredictionModelRegistryService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    VersionView register(UUID userId, RegisterCommand command) {
        validate(command);
        var id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO prediction_model_versions (
                        id, user_id, model_version, contract_version, status, created_at
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                    """, id, userId, command.modelVersion(), command.contractVersion());
        } catch (DuplicateKeyException exception) {
            throw new PredictionModelRegistryException(
                    PredictionModelRegistryException.Code.ALREADY_EXISTS);
        }
        return find(userId, id);
    }

    List<VersionView> list(UUID userId) {
        return jdbc.query("""
                SELECT id, model_version, contract_version, status, created_at, deprecated_at
                  FROM prediction_model_versions
                 WHERE user_id = ?
                 ORDER BY created_at, id
                """, PredictionModelRegistryService::view, userId);
    }

    VersionView deprecate(UUID userId, UUID id) {
        jdbc.update("""
                UPDATE prediction_model_versions
                   SET status = 'DEPRECATED',
                       deprecated_at = CURRENT_TIMESTAMP
                 WHERE user_id = ?
                   AND id = ?
                   AND status = 'ACTIVE'
                """, userId, id);
        return find(userId, id);
    }

    void delete(UUID userId, UUID id) {
        try {
            if (jdbc.update("""
                    DELETE FROM prediction_model_versions
                     WHERE user_id = ?
                       AND id = ?
                    """, userId, id) == 0) {
                throw new PredictionModelRegistryException(
                        PredictionModelRegistryException.Code.NOT_FOUND);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new PredictionModelRegistryException(
                    PredictionModelRegistryException.Code.IN_USE);
        }
    }

    boolean isActive(UUID userId, String modelVersion, String contractVersion) {
        return !jdbc.queryForList("""
                SELECT 1
                  FROM prediction_model_versions
                 WHERE user_id = ?
                   AND model_version = ?
                   AND contract_version = ?
                   AND status = 'ACTIVE'
                """, Integer.class, userId, modelVersion, contractVersion).isEmpty();
    }

    boolean lockActive(UUID userId, String modelVersion, String contractVersion) {
        return !jdbc.queryForList("""
                SELECT 1
                  FROM prediction_model_versions
                 WHERE user_id = ?
                   AND model_version = ?
                   AND contract_version = ?
                   AND status = 'ACTIVE'
                   FOR SHARE
                """, Integer.class, userId, modelVersion, contractVersion).isEmpty();
    }

    private VersionView find(UUID userId, UUID id) {
        return jdbc.query("""
                SELECT id, model_version, contract_version, status, created_at, deprecated_at
                  FROM prediction_model_versions
                 WHERE user_id = ?
                   AND id = ?
                """, PredictionModelRegistryService::view, userId, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new PredictionModelRegistryException(
                        PredictionModelRegistryException.Code.NOT_FOUND));
    }

    private void validate(RegisterCommand command) {
        if (command == null
                || invalidVersion(command.modelVersion())
                || invalidVersion(command.contractVersion())) {
            throw new PredictionModelRegistryException(
                    PredictionModelRegistryException.Code.INVALID_INPUT);
        }
    }

    private boolean invalidVersion(String value) {
        return value == null || value.isBlank() || value.length() > 50;
    }

    private static VersionView view(ResultSet result, int row) throws SQLException {
        var deprecatedAt = result.getObject("deprecated_at", OffsetDateTime.class);
        return new VersionView(
                result.getObject("id", UUID.class),
                result.getString("model_version"),
                result.getString("contract_version"),
                Status.valueOf(result.getString("status")),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                deprecatedAt == null ? null : deprecatedAt.toInstant());
    }

    public enum Status {
        ACTIVE,
        DEPRECATED
    }

    public record RegisterCommand(String modelVersion, String contractVersion) {
    }

    public record VersionView(
            UUID id,
            String modelVersion,
            String contractVersion,
            Status status,
            java.time.Instant createdAt,
            java.time.Instant deprecatedAt
    ) {
    }
}
