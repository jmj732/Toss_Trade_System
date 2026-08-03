package com.jmj.trade.prediction;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PredictionEvaluationSchedulerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void recordsLeaseAcquireFailureWithoutStartingATick() {
        var lease = mock(PredictionEvaluationLease.class);
        var predictions = mock(AnalysisPredictionService.class);
        var fixture = metrics();
        when(lease.acquire(any())).thenReturn(false);
        var scheduler = scheduler(lease, predictions, fixture.metrics(), Clock.fixed(T0, ZoneOffset.UTC));

        scheduler.evaluate();

        assertThat(fixture.registry().get("trade.prediction.evaluation.lease.failure")
                .tag("stage", "acquire").counter().count()).isEqualTo(1);
        verifyNoInteractions(predictions);
    }

    @Test
    void recordsTickCountsAndCountCap() {
        var lease = mock(PredictionEvaluationLease.class);
        var predictions = mock(AnalysisPredictionService.class);
        var fixture = metrics();
        when(lease.acquire(any())).thenReturn(true);
        when(lease.renew(any())).thenReturn(true);
        when(predictions.evaluateDueWithResult(any(), anyInt(), anyInt(), any()))
                .thenReturn(new AnalysisPredictionService.EvaluationTickResult(5, 3, 1, 1, true));
        var scheduler = scheduler(lease, predictions, fixture.metrics(), Clock.fixed(T0, ZoneOffset.UTC));

        scheduler.evaluate();

        assertThat(counter(fixture.registry(), "trade.prediction.evaluation.attempted")).isEqualTo(5);
        assertThat(counter(fixture.registry(), "trade.prediction.evaluation.succeeded")).isEqualTo(3);
        assertThat(counter(fixture.registry(), "trade.prediction.evaluation.quote.failed")).isEqualTo(1);
        assertThat(counter(fixture.registry(), "trade.prediction.evaluation.item.failed")).isEqualTo(1);
        assertThat(fixture.registry().get("trade.prediction.evaluation.early.stop")
                .tag("reason", "count").counter().count()).isEqualTo(1);
    }

    @Test
    void recordsRenewFailureWithoutAlsoCountingACapStop() {
        var lease = mock(PredictionEvaluationLease.class);
        var predictions = mock(AnalysisPredictionService.class);
        var fixture = metrics();
        when(lease.acquire(any())).thenReturn(true);
        when(lease.renew(any())).thenReturn(false);
        when(predictions.evaluateDueWithResult(any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    var continuation = invocation.getArgument(3, BooleanSupplier.class);
                    assertThat(continuation.getAsBoolean()).isFalse();
                    return new AnalysisPredictionService.EvaluationTickResult(0, 0, 0, false);
                });
        var scheduler = scheduler(lease, predictions, fixture.metrics(), Clock.fixed(T0, ZoneOffset.UTC));

        scheduler.evaluate();

        assertThat(fixture.registry().get("trade.prediction.evaluation.lease.failure")
                .tag("stage", "renew").counter().count()).isEqualTo(1);
        assertThat(fixture.registry().get("trade.prediction.evaluation.early.stop")
                .tag("reason", "count").counter().count()).isZero();
        assertThat(fixture.registry().get("trade.prediction.evaluation.early.stop")
                .tag("reason", "time").counter().count()).isZero();
    }

    @Test
    void checksTimeCapBeforeRenewingTheLease() {
        var lease = mock(PredictionEvaluationLease.class);
        var predictions = mock(AnalysisPredictionService.class);
        var fixture = metrics();
        when(lease.acquire(any())).thenReturn(true);
        when(predictions.evaluateDueWithResult(any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    var continuation = invocation.getArgument(3, BooleanSupplier.class);
                    assertThat(continuation.getAsBoolean()).isFalse();
                    return new AnalysisPredictionService.EvaluationTickResult(0, 0, 0, false);
                });
        var clock = new SequenceClock(T0, T0.plus(Duration.ofMinutes(2)));
        var scheduler = scheduler(lease, predictions, fixture.metrics(), clock);

        scheduler.evaluate();

        assertThat(fixture.registry().get("trade.prediction.evaluation.early.stop")
                .tag("reason", "time").counter().count()).isEqualTo(1);
        verify(lease, never()).renew(any());
    }

    private static PredictionEvaluationScheduler scheduler(
            PredictionEvaluationLease lease,
            AnalysisPredictionService predictions,
            PredictionEvaluationMetrics metrics,
            Clock clock
    ) {
        return new PredictionEvaluationScheduler(
                lease, predictions, metrics, 100, 1000, Duration.ofMinutes(1), clock);
    }

    private static MetricsFixture metrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PredictionEvaluationMetrics(
                new JdbcTemplate(), registry, Duration.ofSeconds(30), Clock.fixed(T0, ZoneOffset.UTC));
        return new MetricsFixture(metrics, registry);
    }

    private static double counter(SimpleMeterRegistry registry, String name) {
        return registry.get(name).counter().count();
    }

    private record MetricsFixture(
            PredictionEvaluationMetrics metrics,
            SimpleMeterRegistry registry
    ) {
    }

    private static final class SequenceClock extends Clock {
        private final Instant first;
        private final Instant later;
        private int calls;

        SequenceClock(Instant first, Instant later) {
            this.first = first;
            this.later = later;
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
            return calls++ == 0 ? first : later;
        }
    }
}
