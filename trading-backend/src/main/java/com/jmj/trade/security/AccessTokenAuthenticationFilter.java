package com.jmj.trade.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

final class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AccessTokenService tokens;

    AccessTokenAuthenticationFilter(AccessTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout")
                || isApiKeyBatchRequest(request);
    }

    private static boolean isApiKeyBatchRequest(HttpServletRequest request) {
        var authorization = request.getHeader("Authorization");
        return "POST".equals(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/broker-connections/")
                && request.getRequestURI().endsWith("/analysis-predictions/batch")
                && authorization != null
                && authorization.startsWith("Bearer ");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            var claims = tokens.parse(header.substring("Bearer ".length()));
            SecurityContextHolder.getContext().setAuthentication(
                    new AccessTokenAuthentication(new AuthenticatedUser(
                            claims.userId(), claims.sessionId(), claims.authenticatedAt())));
            filterChain.doFilter(request, response);
        } catch (AccessTokenService.InvalidAccessTokenException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    private static final class AccessTokenAuthentication extends AbstractAuthenticationToken {

        private final AuthenticatedUser principal;

        private AccessTokenAuthentication(AuthenticatedUser principal) {
            super(List.of());
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }

        @Override
        public String getName() {
            return principal.getName();
        }
    }
}
