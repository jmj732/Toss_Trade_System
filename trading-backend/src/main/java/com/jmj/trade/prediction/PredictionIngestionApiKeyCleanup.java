package com.jmj.trade.prediction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

public final class PredictionIngestionApiKeyCleanup {

    private final JdbcTemplate jdbc;

    public PredictionIngestionApiKeyCleanup(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Scheduled(
            fixedDelayString = "${prediction.ingestion-api-key.cleanup.interval:PT1H}",
            initialDelayString = "${prediction.ingestion-api-key.cleanup.initial-delay:PT1M}")
    public void run() {
        expireDue();
    }

    int expireDue() {
        return jdbc.update("""
                UPDATE prediction_ingestion_api_keys
                   SET status = 'EXPIRED'
                 WHERE status = 'ACTIVE'
                   AND expires_at IS NOT NULL
                   AND expires_at <= CURRENT_TIMESTAMP
                """);
    }
}
