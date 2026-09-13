package com.jmj.trade.connector;

import com.jmj.trade.prediction.PredictionIngestionApiKeyRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public final class ConnectorApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private final ConnectorApiKeyService apiKeys;
    private final PredictionIngestionApiKeyRateLimiter rateLimiter;

    public ConnectorApiKeyAuthenticationFilter(ConnectorApiKeyService apiKeys) {
        this(apiKeys, null);
    }

    public ConnectorApiKeyAuthenticationFilter(ConnectorApiKeyService apiKeys,
                                               PredictionIngestionApiKeyRateLimiter rateLimiter) {
        this.apiKeys = apiKeys;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isConnectorRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authorization = request.getHeader("Authorization");
        var key = apiKeys.findActive(authorization.substring(BEARER.length())).orElse(null);
        if (key == null || key.expired()) {
            error(response, HttpServletResponse.SC_UNAUTHORIZED, "CONNECTOR_UNAUTHORIZED");
            return;
        }
        if (rateLimiter != null) {
            final PredictionIngestionApiKeyRateLimiter.Decision decision;
            try {
                decision = rateLimiter.acquire(key.id(), 1);
            } catch (PredictionIngestionApiKeyRateLimiter.RateLimitUnavailableException exception) {
                error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "CONNECTOR_RATE_LIMIT_UNAVAILABLE");
                return;
            }
            if (!decision.allowed()) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", Long.toString(
                        Math.max(1, (decision.retryAfter().toMillis() + 999) / 1_000)));
                error(response, HttpStatus.TOO_MANY_REQUESTS.value(), "CONNECTOR_RATE_LIMITED");
                return;
            }
        }
        if (!apiKeys.markUsed(key.id())) {
            error(response, HttpServletResponse.SC_UNAUTHORIZED, "CONNECTOR_UNAUTHORIZED");
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                key.userId().toString(), null,
                List.of(new SimpleGrantedAuthority("SCOPE_CONNECTOR_READ")));
        authentication.setDetails(key);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    public static boolean isConnectorRequest(HttpServletRequest request) {
        var authorization = request.getHeader("Authorization");
        return "GET".equals(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/connector/")
                && authorization != null && authorization.startsWith(BEARER);
    }
}
