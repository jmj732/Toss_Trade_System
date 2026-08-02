package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RealOrderCanaryPropertiesTest {

    @Test
    void missingCanaryConfigurationIsNotReady() {
        var properties = new RealOrderCanaryProperties(
                false, null, null, null, null, null, null, null);

        assertThat(properties.validationErrors())
                .contains("CANARY_DISABLED", "CANARY_ACCOUNT_NOT_PINNED", "CANARY_USD_LIMIT_INVALID");
    }

    @Test
    void configuredCanaryCannotExceedHardSafetyCeilings() {
        var properties = valid(new BigDecimal("11"), new BigDecimal("101"), Duration.ofMinutes(6));

        assertThat(properties.validationErrors())
                .containsExactlyInAnyOrder(
                        "CANARY_QUANTITY_LIMIT_INVALID", "CANARY_USD_LIMIT_INVALID", "CANARY_QUOTE_AGE_INVALID");
    }

    @Test
    void validCanaryPropertiesExposeCurrencySpecificLimit() {
        var properties = valid(BigDecimal.ONE, new BigDecimal("100"), Duration.ofMinutes(1));

        assertThat(properties.validationErrors()).isEmpty();
        assertThat(properties.maxOrderAmount(Currency.USD)).isEqualByComparingTo("100");
    }

    private static RealOrderCanaryProperties valid(BigDecimal quantity, BigDecimal usd, Duration quoteAge) {
        return new RealOrderCanaryProperties(true, UUID.randomUUID(), UUID.randomUUID(), quantity,
                new BigDecimal("100000"), usd, quoteAge, "canary");
    }
}
