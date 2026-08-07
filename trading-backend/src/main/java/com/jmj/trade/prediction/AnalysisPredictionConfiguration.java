package com.jmj.trade.prediction;

import com.jmj.trade.broker.BrokerAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/**
 * Owns the analysis prediction bean wiring — the {@link AnalysisPredictionService} and its
 * evaluation scheduling sub-configuration. It shares the credential vault's activation property
 * so the bean set stays byte-identical to when this wiring lived in
 * {@code CredentialVaultConfiguration}; a follow-up delta can drop the class-level condition once
 * analysis prediction no longer keys off credential availability.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class AnalysisPredictionConfiguration {

    @Bean
    AnalysisPredictionService analysisPredictionService(
            JdbcTemplate jdbcTemplate,
            BrokerAdapter brokerAdapter,
            PredictionModelRegistryService registry,
            PlatformTransactionManager transactionManager
    ) {
        return new AnalysisPredictionService(
                jdbcTemplate, brokerAdapter, registry, new TransactionTemplate(transactionManager));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "prediction.evaluation", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class PredictionEvaluationSchedulingConfiguration {

        @Bean
        PredictionEvaluationLease predictionEvaluationLease(
                JdbcTemplate jdbcTemplate,
                @Value("${prediction.evaluation.lock-ttl:PT10M}") Duration lockTtl
        ) {
            return new PredictionEvaluationLease(jdbcTemplate, lockTtl);
        }

        @Bean
        PredictionEvaluationScheduler predictionEvaluationScheduler(
                PredictionEvaluationLease lease,
                AnalysisPredictionService predictions,
                PredictionEvaluationMetrics metrics,
                @Value("${prediction.evaluation.batch-size:100}") int batchSize,
                @Value("${prediction.evaluation.max-per-tick:1000}") int maxPerTick,
                @Value("${prediction.evaluation.max-runtime:PT5M}") Duration maxRuntime
        ) {
            return new PredictionEvaluationScheduler(
                    lease, predictions, metrics, batchSize, maxPerTick, maxRuntime);
        }
    }
}
