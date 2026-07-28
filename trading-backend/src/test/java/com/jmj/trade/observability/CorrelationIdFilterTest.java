package com.jmj.trade.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private static final String CORRELATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Test
    void preservesSafeCorrelationAndMeasuresBoundedOperations() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new CorrelationIdFilter(registry);

        perform(filter, registry, "/api/v1/broker-connections/1/portfolio-analyses", "analysis");
        perform(filter, registry, "/api/v1/broker-connections/1/portfolio-syncs", "sync");
        perform(filter, registry, "/api/v1/paper-orders/1/approve", "order");
        perform(filter, registry, "/api/v1/session", "request");

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeCorrelationAndNeverLogsSecretsOrAccountNumbers() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new CorrelationIdFilter(registry);
        var logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var request = new MockHttpServletRequest("GET", "/api/v1/session");
            request.addHeader(CorrelationIdFilter.HEADER, "123-456-7890");
            request.addHeader("Authorization", "Bearer top-secret");
            request.addHeader("X-Account-Number", "123-456-7890");
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                    .isNotBlank()
                    .doesNotContain("123-456-7890", "secret");
            assertThat(logs.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain("top-secret", "123-456-7890"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    private static void perform(
            CorrelationIdFilter filter,
            SimpleMeterRegistry registry,
            String path,
            String operation
    ) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader(CorrelationIdFilter.HEADER, CORRELATION_ID);
        var response = new MockHttpServletResponse();
        response.setStatus(503);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(CORRELATION_ID);
        assertThat(registry.get("trade.operation.duration")
                .tag("operation", operation)
                .tag("outcome", "failure")
                .timer().count()).isEqualTo(1);
    }
}
