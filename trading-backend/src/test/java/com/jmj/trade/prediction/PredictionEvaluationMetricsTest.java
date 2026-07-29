package com.jmj.trade.prediction;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionEvaluationMetricsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void sharesOneDatabaseSnapshotAcrossBothGaugesUntilTheTtlExpires() {
        var jdbc = new StubJdbcTemplate();
        jdbc.row(3, T0.minus(Duration.ofHours(2)));
        var registry = new SimpleMeterRegistry();
        var clock = new MutableClock(T0);
        new PredictionEvaluationMetrics(jdbc, registry, Duration.ofSeconds(30), clock);

        assertThat(gauge(registry, "trade.prediction.evaluation.backlog")).isEqualTo(3);
        assertThat(gauge(registry, "trade.prediction.evaluation.max.lag.ms"))
                .isEqualTo(Duration.ofHours(2).toMillis());
        assertThat(jdbc.queryCount()).isEqualTo(1);

        jdbc.row(1, T0.minus(Duration.ofHours(1)));
        clock.advance(Duration.ofSeconds(29));
        assertThat(gauge(registry, "trade.prediction.evaluation.backlog")).isEqualTo(3);
        assertThat(jdbc.queryCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(gauge(registry, "trade.prediction.evaluation.backlog")).isEqualTo(1);
        assertThat(jdbc.queryCount()).isEqualTo(2);
    }

    @Test
    void failedRefreshIsThrottledAndKeepsTheLastSuccessfulSnapshot() {
        var jdbc = new StubJdbcTemplate();
        jdbc.fail();
        var registry = new SimpleMeterRegistry();
        var clock = new MutableClock(T0);
        new PredictionEvaluationMetrics(jdbc, registry, Duration.ofSeconds(30), clock);

        assertThat(gauge(registry, "trade.prediction.evaluation.backlog")).isZero();
        assertThat(gauge(registry, "trade.prediction.evaluation.max.lag.ms")).isZero();
        assertThat(jdbc.queryCount()).isEqualTo(1);

        jdbc.row(2, T0.minus(Duration.ofMinutes(5)));
        clock.advance(Duration.ofSeconds(30));
        assertThat(gauge(registry, "trade.prediction.evaluation.backlog")).isEqualTo(2);

        jdbc.fail();
        clock.advance(Duration.ofSeconds(30));
        assertThat(gauge(registry, "trade.prediction.evaluation.backlog")).isEqualTo(2);
        assertThat(gauge(registry, "trade.prediction.evaluation.max.lag.ms"))
                .isEqualTo(Duration.ofMinutes(6).toMillis());
        assertThat(jdbc.queryCount()).isEqualTo(3);
    }

    @Test
    void recordsTickLeaseAndEarlyStopCounters() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PredictionEvaluationMetrics(
                new StubJdbcTemplate(), registry, Duration.ofSeconds(30), Clock.fixed(T0, ZoneOffset.UTC));

        metrics.recordTick(5, 3, 1);
        metrics.recordLeaseFailure(PredictionEvaluationMetrics.LeaseStage.ACQUIRE);
        metrics.recordLeaseFailure(PredictionEvaluationMetrics.LeaseStage.RENEW);
        metrics.recordEarlyStop(PredictionEvaluationMetrics.EarlyStopReason.COUNT);
        metrics.recordEarlyStop(PredictionEvaluationMetrics.EarlyStopReason.TIME);

        assertThat(counter(registry, "trade.prediction.evaluation.attempted")).isEqualTo(5);
        assertThat(counter(registry, "trade.prediction.evaluation.succeeded")).isEqualTo(3);
        assertThat(counter(registry, "trade.prediction.evaluation.quote.failed")).isEqualTo(1);
        assertThat(registry.get("trade.prediction.evaluation.lease.failure")
                .tag("stage", "acquire").counter().count()).isEqualTo(1);
        assertThat(registry.get("trade.prediction.evaluation.lease.failure")
                .tag("stage", "renew").counter().count()).isEqualTo(1);
        assertThat(registry.get("trade.prediction.evaluation.early.stop")
                .tag("reason", "count").counter().count()).isEqualTo(1);
        assertThat(registry.get("trade.prediction.evaluation.early.stop")
                .tag("reason", "time").counter().count()).isEqualTo(1);
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private static double counter(SimpleMeterRegistry registry, String name) {
        return registry.get(name).counter().count();
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private Map<String, Object> row = emptyRow();
        private boolean failing;
        private int queryCount;

        void row(long count, Instant oldestDueAt) {
            row = Map.of(
                    "backlog_count", count,
                    "oldest_target_due_at", OffsetDateTime.ofInstant(oldestDueAt, ZoneOffset.UTC));
            failing = false;
        }

        void fail() {
            failing = true;
        }

        int queryCount() {
            return queryCount;
        }

        @Override
        public Map<String, Object> queryForMap(String sql) {
            queryCount++;
            if (failing) {
                throw new DataAccessResourceFailureException("unavailable");
            }
            return row;
        }

        private static Map<String, Object> emptyRow() {
            var row = new HashMap<String, Object>();
            row.put("backlog_count", 0L);
            row.put("oldest_target_due_at", null);
            return row;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
