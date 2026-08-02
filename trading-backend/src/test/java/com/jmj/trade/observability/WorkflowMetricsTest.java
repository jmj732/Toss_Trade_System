package com.jmj.trade.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowMetricsTest {

    @Test
    void exposesStaleRunningAndUnpublishedOutboxCounts() {
        var jdbc = new StubJdbcTemplate();
        var registry = new SimpleMeterRegistry();

        new WorkflowMetrics(jdbc, java.time.Duration.ofMinutes(15)).bindTo(registry);

        assertThat(registry.get("trade.workflow.stale.running")
                .tag("workflow", "sync").gauge().value()).isEqualTo(2);
        assertThat(registry.get("trade.workflow.stale.running")
                .tag("workflow", "analysis").gauge().value()).isEqualTo(3);
        assertThat(registry.get("trade.outbox.backlog")
                .tag("outbox", "order-intent").gauge().value()).isEqualTo(4);
        assertThat(registry.get("trade.outbox.backlog")
                .tag("outbox", "order-submission").gauge().value()).isEqualTo(5);
        assertThat(registry.get("trade.outbox.dead_letter")
                .tag("outbox", "order-intent").gauge().value()).isEqualTo(6);
        assertThat(registry.get("trade.outbox.dead_letter")
                .tag("outbox", "order-submission").gauge().value()).isEqualTo(7);
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            Long value;
            if (sql.contains("account_sync_runs")) {
                value = 2L;
            } else if (sql.contains("analysis_runs")) {
                value = 3L;
            } else if (sql.contains("failed_at IS NOT NULL")) {
                // Dead-letter gauge: distinct from the backlog gauge over the same table.
                value = sql.contains("order_intent_outbox_events") ? 6L : 7L;
            } else if (sql.contains("order_intent_outbox_events")) {
                value = 4L;
            } else {
                value = 5L;
            }
            return requiredType.cast(value);
        }
    }
}
