package com.jmj.trade.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void rejectsMissingSigningSecret() {
        assertThatThrownBy(() -> new AccessTokenService("", Duration.ofMinutes(5), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuesAndParsesShortLivedTokenWithInternalIdentityAndReauthTime() {
        var userId = UUID.randomUUID();
        var sessionId = UUID.randomUUID();
        var authTime = NOW.minusSeconds(30);
        var service = new AccessTokenService(
                "01234567890123456789012345678901", Duration.ofMinutes(5), CLOCK);

        var issued = service.issue(userId, sessionId, authTime);
        var claims = service.parse(issued.value());

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
        assertThat(claims.authenticatedAt()).isEqualTo(authTime);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void rejectsTamperedAndExpiredTokens() {
        var fixed = new AccessTokenService(
                "01234567890123456789012345678901", Duration.ofSeconds(1), CLOCK);
        var token = fixed.issue(UUID.randomUUID(), UUID.randomUUID(), NOW).value();
        var tampered = token.substring(0, token.length() - 1) + "x";
        var expired = new AccessTokenService(
                "01234567890123456789012345678901", Duration.ofSeconds(1), CLOCK)
                .issue(UUID.randomUUID(), UUID.randomUUID(), NOW).value();
        var expiredParser = new AccessTokenService(
                "01234567890123456789012345678901", Duration.ofMinutes(5),
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));

        assertThatThrownBy(() -> fixed.parse(tampered))
                .isInstanceOf(AccessTokenService.InvalidAccessTokenException.class);
        assertThatThrownBy(() -> expiredParser.parse(expired))
                .isInstanceOf(AccessTokenService.InvalidAccessTokenException.class);
    }
}
