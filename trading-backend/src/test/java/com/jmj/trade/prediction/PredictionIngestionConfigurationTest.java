package com.jmj.trade.prediction;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PredictionIngestionConfigurationTest {

    // JdbcTemplate is an InitializingBean that rejects a null DataSource, so the stand-in needs
    // one even though these tests only assert which beans the condition registers.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(mock(DataSource.class)))
            .withUserConfiguration(
                    PredictionIngestionConfiguration.PredictionIngestionApiKeyCleanupConfiguration.class);

    @Test
    void cleanupSchedulerIsWiredWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("prediction.ingestion-api-key.cleanup.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PredictionIngestionApiKeyCleanup.class));
    }

    @Test
    void cleanupSchedulerIsAbsentWhenPropertyMissing() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(PredictionIngestionApiKeyCleanup.class));
    }

    @Test
    void cleanupSchedulerIsAbsentWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("prediction.ingestion-api-key.cleanup.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PredictionIngestionApiKeyCleanup.class));
    }
}
