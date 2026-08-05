package com.jmj.trade.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
final class AuthController {

    private final RefreshTokenService refreshTokens;
    private final AccessTokenService accessTokens;
    private final OriginPolicy origins;

    AuthController(RefreshTokenService refreshTokens, AccessTokenService accessTokens,
                   OriginPolicy origins) {
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.origins = origins;
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(
            @CookieValue(name = AuthCookieSupport.REFRESH_COOKIE, required = false) String rawToken,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        origins.require(request);
        try {
            var rotated = refreshTokens.rotate(rawToken);
            var access = accessTokens.issue(
                    rotated.userId(), rotated.sessionId(), rotated.authenticatedAt());
            AuthCookieSupport.setRefreshCookie(response, rotated.refreshToken(),
                    Duration.between(java.time.Instant.now(), rotated.expiresAt()));
            return ResponseEntity.ok(new AuthResponse(access.value(), access.expiresAt()));
        } catch (RefreshTokenService.InvalidRefreshTokenException exception) {
            AuthCookieSupport.clearRefreshCookie(response);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorView("AUTH_REFRESH_INVALID"));
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = AuthCookieSupport.REFRESH_COOKIE, required = false) String rawToken,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        origins.require(request);
        refreshTokens.revoke(rawToken);
        AuthCookieSupport.clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(
            Principal principal,
            @CookieValue(name = AuthCookieSupport.REFRESH_COOKIE, required = false) String rawToken,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        origins.require(request);
        refreshTokens.revokeAll(UUID.fromString(principal.getName()));
        refreshTokens.revoke(rawToken);
        AuthCookieSupport.clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(OriginPolicy.OriginRejectedException.class)
    ResponseEntity<ErrorView> originRejected() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorView("AUTH_ORIGIN_REJECTED"));
    }

    record AuthResponse(String accessToken, java.time.Instant expiresAt) {
    }

    record ErrorView(String code) {
    }
}
