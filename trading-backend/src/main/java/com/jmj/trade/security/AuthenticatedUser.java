package com.jmj.trade.security;

import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The only user identity exposed by the stateless API authentication layer. */
public record AuthenticatedUser(UUID userId, UUID sessionId, Instant authenticatedAt) implements Principal {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
