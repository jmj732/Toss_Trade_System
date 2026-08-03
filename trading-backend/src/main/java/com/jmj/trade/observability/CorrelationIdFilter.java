package com.jmj.trade.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";
    private static final Pattern SAFE_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final MeterRegistry registry;

    public CorrelationIdFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var correlationId = correlationId(request.getHeader(HEADER));
        var operation = operation(request.getRequestURI());
        var previous = MDC.get(MDC_KEY);
        var sample = Timer.start(registry);
        var failed = false;
        String errorType = null;
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            errorType = exception.getClass().getSimpleName();
            throw exception;
        } finally {
            var outcome = failed || response.getStatus() >= 400 ? "failure" : "success";
            var elapsed = sample.stop(Timer.builder("trade.operation.duration")
                    .description("Request latency for bounded trade operations")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(registry));
            var event = LOG.atInfo()
                    .addKeyValue("operation", operation)
                    .addKeyValue("outcome", outcome)
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(elapsed));
            if (errorType != null) {
                event = event.addKeyValue("error_type", errorType);
            }
            event.log("request completed");
            restoreMdc(previous);
        }
    }

    private static String correlationId(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }

    private static String operation(String path) {
        if (path.contains("/portfolio-analyses")) {
            return "analysis";
        }
        if (path.contains("/stock-analyses")) {
            return "stock-analysis";
        }
        if (path.startsWith("/api/v1/operations/readiness")) {
            return "operations-readiness";
        }
        if (path.contains("/portfolio-syncs")) {
            return "sync";
        }
        if (path.startsWith("/api/v1/paper-orders")) {
            return "order";
        }
        return "request";
    }

    private static void restoreMdc(String previous) {
        if (previous == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previous);
        }
    }
}
