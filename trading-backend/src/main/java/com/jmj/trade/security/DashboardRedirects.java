package com.jmj.trade.security;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

final class DashboardRedirects {

    private final URI dashboard;

    DashboardRedirects(String publicDashboardUrl) {
        try {
            var parsed = URI.create(publicDashboardUrl.trim());
            if ((!"http".equalsIgnoreCase(parsed.getScheme())
                    && !"https".equalsIgnoreCase(parsed.getScheme()))
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null) {
                throw new IllegalArgumentException("public dashboard URL must be an HTTP(S) origin");
            }
            dashboard = parsed;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("public dashboard URL is invalid", exception);
        }
    }

    String dashboardUrl(String returnTo) {
        var safeReturnTo = safeReturnTo(returnTo);
        return "/".equals(safeReturnTo) ? dashboard.toString() : appendPath(safeReturnTo);
    }

    String dashboardUrl(String returnTo, String accessToken, java.time.Instant expiresAt) {
        return dashboardUrl(returnTo) + "#access_token=" + encode(accessToken)
                + "&expires_at=" + expiresAt.getEpochSecond();
    }

    String loginUrl(String error, String returnTo) {
        var query = new StringBuilder();
        var errorCode = errorCode(error);
        if (errorCode != null) {
            query.append("error=").append(encode(errorCode));
        }
        var safeReturnTo = safeReturnTo(returnTo);
        if (!"/".equals(safeReturnTo)) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append("returnTo=").append(encode(safeReturnTo));
        }
        var login = appendPath("/login");
        return query.isEmpty() ? login : login + "?" + query;
    }

    static String safeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return "/";
        }
        var candidate = returnTo.trim();
        if (!candidate.startsWith("/")
                || candidate.startsWith("//")
                || candidate.indexOf('\\') >= 0
                || candidate.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            return "/";
        }
        try {
            var decoded = URLDecoder.decode(candidate, StandardCharsets.UTF_8);
            if (decoded.startsWith("//") || decoded.contains("\\")) {
                return "/";
            }
        } catch (IllegalArgumentException exception) {
            return "/";
        }
        try {
            var parsed = URI.create(candidate);
            if (parsed.isAbsolute() || parsed.getRawAuthority() != null || parsed.getRawPath() == null) {
                return "/";
            }
            var path = parsed.getRawPath();
            if (path.isEmpty()) {
                path = "/";
            }
            return path + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
        } catch (IllegalArgumentException exception) {
            return "/";
        }
    }

    static String errorCode(AuthenticationException failure) {
        if (failure instanceof OAuth2AuthenticationException oauthFailure) {
            return errorCode(oauthFailure.getError().getErrorCode());
        }
        return "login";
    }

    static String errorCode(String error) {
        return switch (error == null ? "" : error) {
            case "access_denied" -> "access_denied";
            case "invalid_state_parameter", "authorization_request_not_found", "state" -> "state";
            case "session_expired" -> "session_expired";
            case "login" -> "login";
            default -> error == null || error.isBlank() ? null : "login";
        };
    }

    private String appendPath(String path) {
        var base = dashboard.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
