package com.jmj.trade.connector;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only fallback used by direct unit construction; production wiring uses JDBC. */
final class InMemoryConnectorOAuthRefreshTokenStore implements ConnectorOAuthRefreshTokenStore {
    private final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();

    @Override
    public void save(Grant grant) {
        grants.put(grant.tokenHash(), grant);
    }

    @Override
    public Optional<Grant> consume(String tokenHash, String clientId, Instant consumedAt) {
        var grant = grants.remove(tokenHash);
        if (grant == null || !grant.clientId().equals(clientId) || !grant.expiresAt().isAfter(consumedAt)) {
            return Optional.empty();
        }
        return Optional.of(grant);
    }
}
