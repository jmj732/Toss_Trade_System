package com.jmj.trade.connector;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

final class JdbcConnectorOAuthRefreshTokenStore implements ConnectorOAuthRefreshTokenStore {
    private final JdbcTemplate jdbc;

    JdbcConnectorOAuthRefreshTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Grant grant) {
        jdbc.update("""
                INSERT INTO connector_oauth_refresh_tokens
                    (token_hash, client_id, user_id, connection_id, scope, resource, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, grant.tokenHash(), grant.clientId(), grant.userId(), grant.connectionId(), grant.scope(),
                grant.resource(), at(grant.createdAt()), at(grant.expiresAt()));
    }

    @Override
    public Optional<Grant> consume(String tokenHash, String clientId, Instant consumedAt) {
        return jdbc.query("""
                UPDATE connector_oauth_refresh_tokens
                   SET consumed_at = ?
                 WHERE token_hash = ? AND client_id = ?
                   AND consumed_at IS NULL AND expires_at > ?
                RETURNING token_hash, client_id, user_id, connection_id, scope, resource, created_at, expires_at
                """, (rs, row) -> new Grant(rs.getString("token_hash"), rs.getString("client_id"),
                rs.getObject("user_id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getString("scope"), rs.getString("resource"), instant(rs, "created_at"), instant(rs, "expires_at")),
                at(consumedAt), tokenHash, clientId, at(consumedAt)).stream().findFirst();
    }

    private static OffsetDateTime at(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
