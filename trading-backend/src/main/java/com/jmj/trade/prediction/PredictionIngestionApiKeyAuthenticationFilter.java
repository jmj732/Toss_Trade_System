package com.jmj.trade.prediction;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public final class PredictionIngestionApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final PredictionIngestionApiKeyService apiKeys;

    public PredictionIngestionApiKeyAuthenticationFilter(
            PredictionIngestionApiKeyService apiKeys
    ) {
        this.apiKeys = apiKeys;
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
        var authenticated = apiKeys.authenticate(rawKey);
        if (authenticated.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var key = authenticated.get();
        var authentication = new UsernamePasswordAuthenticationToken(
                key.userId().toString(), null, List.of());
        authentication.setDetails(key.scope());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
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
