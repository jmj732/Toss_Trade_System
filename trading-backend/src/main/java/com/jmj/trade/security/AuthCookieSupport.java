package com.jmj.trade.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

final class AuthCookieSupport {

    static final String REFRESH_COOKIE = "trade_refresh_token";

    private AuthCookieSupport() {
    }

    static void setRefreshCookie(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build()
                .toString());
    }

    static void clearRefreshCookie(HttpServletResponse response) {
        setRefreshCookie(response, "", Duration.ZERO);
    }
}
