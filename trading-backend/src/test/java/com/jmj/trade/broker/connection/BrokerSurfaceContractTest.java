package com.jmj.trade.broker.connection;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerSurfaceContractTest {

    @Test
    void unavailableIsExplicitAndDoesNotLookLikeAnEmptySuccess() {
        var response = BrokerSurfaceResponse.unavailable("PROVIDER_UNSUPPORTED");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.UNAVAILABLE);
        assertThat(response.unavailable()).isTrue();
        assertThat(response.unavailableReason()).isEqualTo("PROVIDER_UNSUPPORTED");
        assertThat(response.data()).isNull();
    }

    @Test
    void degradedResponseKeepsRealDataAndItsQualityFlags() {
        var observedAt = Instant.parse("2026-08-09T00:00:00Z");
        var data = new BrokerSurfaceResponse.PriceView(
                "AAPL", new BigDecimal("100"), null, null, "USD", observedAt, null);

        var response = BrokerSurfaceResponse.degraded(
                data, true, true, List.of("bidPrice", "askPrice"), "QUOTE_FIELDS_UNAVAILABLE");

        assertThat(response.status()).isEqualTo(BrokerSurfaceResponse.Status.DEGRADED);
        assertThat(response.data()).isEqualTo(data);
        assertThat(response.stale()).isTrue();
        assertThat(response.unknownFields()).containsExactly("bidPrice", "askPrice");
        assertThat(response.unavailable()).isFalse();
    }
}
