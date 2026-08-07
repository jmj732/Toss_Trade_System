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
        // 서명은 32바이트 HMAC -> base64url(43자, 무패딩)이다. 마지막 문자는 실질 4비트만
        // 담고 인코더가 하위 2비트를 항상 0으로 채우므로, 그 위치를 고정 문자로 바꾸면
        // 원본의 상위 4비트와 우연히 같을 때(약 1/16 확률) 디코딩된 바이트가 그대로라
        // 변조가 감지되지 않는 flaky 케이스가 있었다. 끝에서 두 번째 문자는 전체 6비트가
        // 실제 서명 바이트에 그대로 대응하므로, 그 자리를 원본과 다른 문자로 바꾸면
        // 디코딩된 바이트가 항상 달라져 결정적으로 검증에 실패한다.
        var tamperIndex = token.length() - 2;
        var original = token.charAt(tamperIndex);
        var replacement = original == 'x' ? 'y' : 'x';
        var tampered = token.substring(0, tamperIndex) + replacement + token.charAt(token.length() - 1);
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
