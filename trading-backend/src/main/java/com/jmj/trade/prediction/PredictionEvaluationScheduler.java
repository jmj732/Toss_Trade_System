package com.jmj.trade.prediction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class PredictionEvaluationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PredictionEvaluationScheduler.class);

    private final PredictionEvaluationLease lease;
    private final AnalysisPredictionService predictions;

    public PredictionEvaluationScheduler(
            PredictionEvaluationLease lease,
            AnalysisPredictionService predictions
    ) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.predictions = Objects.requireNonNull(predictions, "predictions");
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
            predictions.evaluateDue(Instant.now());
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
