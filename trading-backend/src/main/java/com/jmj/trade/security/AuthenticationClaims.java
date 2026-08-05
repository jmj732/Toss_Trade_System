package com.jmj.trade.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Duration;
import java.time.Instant;

public final class AuthenticationClaims {

    private AuthenticationClaims() {
    }

    public static Instant authenticatedAt(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.authenticatedAt();
        }
        if (authentication.getPrincipal() instanceof OidcUser oidcUser
                && oidcUser.getIdToken() != null) {
            return oidcUser.getIdToken().getAuthenticatedAt();
        }
        return null;
    }

    public static void requireRecent(Authentication authentication, Duration freshness) {
        var authTime = authenticatedAt(authentication);
        var now = Instant.now();
        if (authTime == null || authTime.isAfter(now.plusSeconds(60))
                || Duration.between(authTime, now).compareTo(freshness) > 0) {
            throw new ReauthenticationRequiredException();
        }
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public static final class ReauthenticationRequiredException extends RuntimeException {
    }
}
