package com.jmj.trade.intelligence.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;

class MarketEventIngestionConfigurationTest {

    @Test
    void schedulerRequiresAnExplicitOptInProperty() {
        var condition = MarketEventIngestionConfiguration.SchedulingConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("market-events.scheduler");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }
}
