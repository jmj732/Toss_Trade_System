package com.jmj.trade.connector;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface ConnectorOAuthRefreshTokenStore {
    void save(Grant grant);

    Optional<Grant> consume(String tokenHash, String clientId, Instant consumedAt);

    record Grant(String tokenHash, String clientId, UUID userId, UUID connectionId,
                 String scope, String resource, Instant createdAt, Instant expiresAt) { }
}
