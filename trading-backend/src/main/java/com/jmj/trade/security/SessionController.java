package com.jmj.trade.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/session")
final class SessionController {

    @GetMapping
    SessionView read(Authentication authentication) {
        var userId = UUID.fromString(authentication.getName());
        var authenticatedAt = authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user.authenticatedAt() : null;
        return new SessionView(userId, authenticatedAt);
    }

    record SessionView(UUID userId, java.time.Instant authenticatedAt) {
    }
}
