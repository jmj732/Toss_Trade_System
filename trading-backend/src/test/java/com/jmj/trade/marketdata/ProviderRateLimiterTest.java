package com.jmj.trade.marketdata;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRateLimiterTest {
    @Test
    void virtualClockPreservesSecMinimumIntervalWithoutWallClockSleep() {
        var nanos = new AtomicLong();
        var policy = new ProviderTransportPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1),
                0, Duration.ZERO, 100, Duration.ofSeconds(1));
        var limiter = new ProviderRateLimiter(StockDataProviderId.SEC, policy, nanos::get, nanos::addAndGet);
        limiter.acquire();
        limiter.acquire();
        assertThat(nanos.get()).isEqualTo(Duration.ofMillis(500).toNanos());
    }
    @Test
    void cooldownRejectsUntilItsDeadlineWithoutWallClockSleep() {
        var nanos = new AtomicLong();
        var policy = new ProviderTransportPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1),
                0, Duration.ZERO, 100, Duration.ofSeconds(1));
        var limiter = new ProviderRateLimiter(StockDataProviderId.SEC, policy, nanos::get, nanos::addAndGet);
        limiter.coolDownFor(Duration.ofSeconds(2));
        assertThatThrownBy(limiter::acquire).isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("HTTP_429");
        nanos.set(Duration.ofSeconds(2).toNanos());
        limiter.acquire();
        limiter.acquire();
        assertThat(nanos.get()).isEqualTo(Duration.ofMillis(2500).toNanos());
    }
}
