package com.jmj.trade.prediction;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public final class PredictionIngestionApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final PredictionIngestionApiKeyService apiKeys;
    private final PredictionIngestionApiKeyRateLimiter rateLimiter;
    private final PredictionIngestionApiKeyMetrics metrics;

    public PredictionIngestionApiKeyAuthenticationFilter(
            PredictionIngestionApiKeyService apiKeys,
            PredictionIngestionApiKeyRateLimiter rateLimiter,
            PredictionIngestionApiKeyMetrics metrics
    ) {
        this.apiKeys = apiKeys;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isApiKeyBatchRequest(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var rawKey = request.getHeader("Authorization").substring(BEARER.length());
        var authenticated = apiKeys.findActive(rawKey);
        if (authenticated.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var key = authenticated.get();
        if (key.expiresAt() != null && !key.expiresAt().isAfter(Instant.now())) {
            apiKeys.recordRejection(
                    key, PredictionIngestionApiKeyService.RejectionReason.EXPIRED);
            metrics.recordRejected(PredictionIngestionApiKeyMetrics.Reason.EXPIRED);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        final PredictionIngestionApiKeyRateLimiter.Decision decision;
        try {
            decision = rateLimiter.acquire(key.id());
        } catch (PredictionIngestionApiKeyRateLimiter.RateLimitUnavailableException exception) {
            metrics.recordRejected(PredictionIngestionApiKeyMetrics.Reason.REDIS_UNAVAILABLE);
            error(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "PREDICTION_INGESTION_RATE_LIMIT_UNAVAILABLE",
                    null);
            return;
        }
        if (!decision.allowed()) {
            apiKeys.recordRejection(
                    key, PredictionIngestionApiKeyService.RejectionReason.RATE_LIMITED);
            metrics.recordRejected(PredictionIngestionApiKeyMetrics.Reason.RATE_LIMITED);
            var retryAfterSeconds = Math.max(
                    1, (decision.retryAfter().toMillis() + 999) / 1_000);
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            error(
                    response,
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "PREDICTION_INGESTION_API_KEY_RATE_LIMITED",
                    decision.retryAt());
            return;
        }
        if (!apiKeys.markUsed(key.id())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                key.userId().toString(), null, List.of());
        authentication.setDetails(key.scope());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private static void error(
            HttpServletResponse response,
            int status,
            String code,
            Instant retryAt
    ) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(retryAt == null
                ? "{\"code\":\"" + code + "\"}"
                : "{\"code\":\"" + code + "\",\"retryAt\":\"" + retryAt + "\"}");
    }

    public static boolean isApiKeyBatchRequest(HttpServletRequest request) {
        var path = request.getRequestURI();
        var authorization = request.getHeader("Authorization");
        return "POST".equals(request.getMethod())
                && path.startsWith("/api/v1/broker-connections/")
                && path.endsWith("/analysis-predictions/batch")
                && authorization != null
                && authorization.startsWith(BEARER);
    }
}
