package com.jmj.trade.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Component
final class WorkflowMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;
    private final long staleSeconds;

    WorkflowMetrics(
            JdbcTemplate jdbc,
            @Value("${observability.stale-running-after:PT15M}") Duration staleAfter
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        staleSeconds = staleAfter.toSeconds();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        staleGauge(registry, "sync", "account_sync_runs");
        staleGauge(registry, "analysis", "analysis_runs");
        outboxGauge(registry, "order-intent", "order_intent_outbox_events");
        outboxGauge(registry, "order-submission", "order_submission_outbox_events");
    }

    private void staleGauge(MeterRegistry registry, String workflow, String table) {
        Gauge.builder("trade.workflow.stale.running", this,
                        ignored -> count("""
                                SELECT count(*)
                                  FROM %s
                                 WHERE status = 'RUNNING'
                                   AND started_at < CURRENT_TIMESTAMP - INTERVAL '%d seconds'
                                """.formatted(table, staleSeconds)))
                .description("Stale RUNNING workflows")
                .tag("workflow", workflow)
                .register(registry);
    }

    private void outboxGauge(MeterRegistry registry, String outbox, String table) {
        Gauge.builder("trade.outbox.backlog", this,
                        ignored -> count("""
                                SELECT count(*)
                                  FROM %s
                                 WHERE published_at IS NULL
                                   AND failed_at IS NULL
                                """.formatted(table)))
                .description("Unpublished outbox events awaiting relay")
                .tag("outbox", outbox)
                .register(registry);
        Gauge.builder("trade.outbox.dead_letter", this,
                        ignored -> count("""
                                SELECT count(*)
                                  FROM %s
                                 WHERE failed_at IS NOT NULL
                                """.formatted(table)))
                .description("Dead-letter outbox events past the retry cap")
                .tag("outbox", outbox)
                .register(registry);
    }

    private long count(String sql) {
        var value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
