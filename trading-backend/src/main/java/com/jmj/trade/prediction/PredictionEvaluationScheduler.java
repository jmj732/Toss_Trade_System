package com.jmj.trade.prediction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PredictionEvaluationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PredictionEvaluationScheduler.class);

    private final PredictionEvaluationLease lease;
    private final AnalysisPredictionService predictions;
    private final PredictionEvaluationMetrics metrics;
    private final int batchSize;
    private final int maxPerTick;
    private final Duration maxRuntime;
    private final Clock clock;

    public PredictionEvaluationScheduler(
            PredictionEvaluationLease lease,
            AnalysisPredictionService predictions,
            PredictionEvaluationMetrics metrics,
            int batchSize,
            int maxPerTick,
            Duration maxRuntime
    ) {
        this(lease, predictions, metrics, batchSize, maxPerTick, maxRuntime, Clock.systemUTC());
    }

    PredictionEvaluationScheduler(
            PredictionEvaluationLease lease,
            AnalysisPredictionService predictions,
            PredictionEvaluationMetrics metrics,
            int batchSize,
            int maxPerTick,
            Duration maxRuntime,
            Clock clock
    ) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.batchSize = batchSize;
        this.maxPerTick = maxPerTick;
        this.maxRuntime = Objects.requireNonNull(maxRuntime, "maxRuntime");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize <= 0 || maxPerTick <= 0 || !maxRuntime.isPositive()) {
            throw new IllegalArgumentException("batchSize, maxPerTick and maxRuntime must be positive");
        }
    }

    @Scheduled(
            fixedDelayString = "${prediction.evaluation.interval:PT1H}",
            initialDelayString = "${prediction.evaluation.initial-delay:PT1M}")
    void evaluate() {
        var owner = UUID.randomUUID();
        if (!lease.acquire(owner)) {
            metrics.recordLeaseFailure(PredictionEvaluationMetrics.LeaseStage.ACQUIRE);
            return;
        }
        try {
            var startedAt = clock.instant();
            var deadline = startedAt.plus(maxRuntime);
            var timeStopped = new AtomicBoolean();
            var renewFailed = new AtomicBoolean();
            var result = predictions.evaluateDueWithResult(
                    startedAt,
                    batchSize,
                    maxPerTick,
                    () -> {
                        if (!clock.instant().isBefore(deadline)) {
                            timeStopped.set(true);
                            return false;
                        }
                        if (!lease.renew(owner)) {
                            renewFailed.set(true);
                            return false;
                        }
                        return true;
                    });
            metrics.recordTick(result.attempted(), result.succeeded(), result.quoteFailed());
            if (renewFailed.get()) {
                metrics.recordLeaseFailure(PredictionEvaluationMetrics.LeaseStage.RENEW);
            } else if (timeStopped.get()) {
                metrics.recordEarlyStop(PredictionEvaluationMetrics.EarlyStopReason.TIME);
            } else if (result.countLimitReached()) {
                metrics.recordEarlyStop(PredictionEvaluationMetrics.EarlyStopReason.COUNT);
            }
        } catch (RuntimeException exception) {
            LOG.atWarn()
                    .addKeyValue("operation", "prediction_evaluation")
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("scheduled prediction evaluation could not run");
        } finally {
            lease.release(owner);
        }
    }
}
