package com.jmj.trade.prediction;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class PredictionIngestionApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final PredictionIngestionApiKeyService apiKeys;
    private final PredictionIngestionApiKeyRateLimiter rateLimiter;
    private final PredictionIngestionApiKeyMetrics metrics;
    private final ObjectMapper objectMapper;

    public PredictionIngestionApiKeyAuthenticationFilter(
            PredictionIngestionApiKeyService apiKeys,
            PredictionIngestionApiKeyRateLimiter rateLimiter,
            PredictionIngestionApiKeyMetrics metrics,
            ObjectMapper objectMapper
    ) {
        this.apiKeys = apiKeys;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
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
        if (key.expired()) {
            apiKeys.recordRejection(
                    key, PredictionIngestionApiKeyService.RejectionReason.EXPIRED);
            metrics.recordRejected(PredictionIngestionApiKeyMetrics.Reason.EXPIRED);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var cachedRequest = new CachedBodyRequest(request);
        final PredictionIngestionApiKeyRateLimiter.Decision decision;
        try {
            decision = rateLimiter.acquire(key.id(), batchWeight(cachedRequest.body()));
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
        filterChain.doFilter(cachedRequest, response);
    }

    private int batchWeight(byte[] body) {
        try {
            var root = objectMapper.readTree(body);
            if (root == null) {
                return 0;
            }
            var items = root.path("items");
            return items.isArray() ? items.size() : 0;
        } catch (JacksonException exception) {
            return 0;
        }
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

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            body = request.getInputStream().readAllBytes();
        }

        private byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            var encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
        }
    }
}
