package com.jmj.trade.prediction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class PredictionEvaluationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PredictionEvaluationScheduler.class);

    private final PredictionEvaluationLease lease;
    private final AnalysisPredictionService predictions;
    private final int batchSize;
    private final int maxPerTick;
    private final Duration maxRuntime;

    public PredictionEvaluationScheduler(
            PredictionEvaluationLease lease,
            AnalysisPredictionService predictions,
            int batchSize,
            int maxPerTick,
            Duration maxRuntime
    ) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
        this.batchSize = batchSize;
        this.maxPerTick = maxPerTick;
        this.maxRuntime = Objects.requireNonNull(maxRuntime, "maxRuntime");
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
            return;
        }
        try {
            var startedAt = Instant.now();
            var deadline = startedAt.plus(maxRuntime);
            predictions.evaluateDue(
                    startedAt,
                    batchSize,
                    maxPerTick,
                    () -> Instant.now().isBefore(deadline) && lease.renew(owner));
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
