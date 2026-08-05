package com.jmj.trade.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

final class OriginPolicy {

    private final String expectedOrigin;

    OriginPolicy(String publicDashboardUrl) {
        try {
            var uri = URI.create(publicDashboardUrl.trim());
            if ((!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            expectedOrigin = uri.getScheme().toLowerCase() + "://" + uri.getRawAuthority();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("public dashboard origin is invalid", exception);
        }
    }

    void require(HttpServletRequest request) {
        if (!expectedOrigin.equals(request.getHeader("Origin"))) {
            throw new OriginRejectedException();
        }
    }

    String expectedOrigin() {
        return expectedOrigin;
    }

    static final class OriginRejectedException extends RuntimeException {
    }
}
