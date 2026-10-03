package com.jmj.trade.marketdata;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlphaVantageDailyRequestCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant MONDAY = Instant.parse("2026-10-05T01:00:00Z");

    @Test
    void reusesOriginalDailySnapshotForSameCredentialSymbolAndFunction() {
        var clock = Clock.fixed(MONDAY, ZoneOffset.UTC);
        var cache = new AlphaVantageDailyRequestCache(MAPPER, clock, 25);
        var calls = new AtomicInteger();
        var originalAsOf = Instant.parse("2026-10-05T00:59:00Z");

        var first = cache.get("avt", "EARNINGS_ESTIMATES", "secret-a", () -> {
            calls.incrementAndGet();
            return List.of(value(originalAsOf));
        });
        var second = cache.get("AVT", "EARNINGS_ESTIMATES", "secret-a", () -> {
            calls.incrementAndGet();
            return List.of(value(MONDAY));
        });

        assertThat(calls).hasValue(1);
        assertThat(second).containsExactlyElementsOf(first);
        assertThat(second.getFirst().asOf()).isEqualTo(originalAsOf);
    }

    @Test
    void cachesSafeFailuresForTheDayInsteadOfRetrying() {
        var cache = new AlphaVantageDailyRequestCache(MAPPER, Clock.fixed(MONDAY, ZoneOffset.UTC), 25);
        var calls = new AtomicInteger();

        for (var index = 0; index < 2; index++) {
            assertThatThrownBy(() -> cache.get("AVT", "EARNINGS_ESTIMATES", "secret-a", () -> {
                calls.incrementAndGet();
                throw new ProviderUnavailableException(StockDataProviderId.ALPHA_VANTAGE,
                        "HTTP 429 provider raw text");
            }))
                    .isInstanceOf(ProviderUnavailableException.class)
                    .hasMessage("HTTP_429_PROVIDER_RAW_TEXT")
                    .hasMessageNotContaining("provider raw text");
        }

        assertThat(calls).hasValue(1);
    }

    @Test
    void appliesDailyBudgetAcrossFunctionsButSeparatesCredentialsAndDates() {
        var clock = new MutableClock(MONDAY);
        var cache = new AlphaVantageDailyRequestCache(MAPPER, clock, 1);

        cache.get("AVT", "EARNINGS_ESTIMATES", "secret-a", () -> List.of(value(MONDAY)));

        assertThatThrownBy(() -> cache.get("AVT", "SHARES_OUTSTANDING", "secret-a",
                () -> List.of(value(MONDAY))))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("DAILY_QUOTA_EXHAUSTED");
        assertThat(cache.get("AVT", "SHARES_OUTSTANDING", "secret-b",
                () -> List.of(value(MONDAY)))).hasSize(1);

        clock.advance(java.time.Duration.ofDays(1));
        assertThat(cache.get("AVT", "SHARES_OUTSTANDING", "secret-a",
                () -> List.of(value(clock.instant())))).hasSize(1);
    }

    private static ProviderValue value(Instant asOf) {
        return new ProviderValue("consensus.horizon", MAPPER.valueToTree("2027-12-31"), null,
                "fiscal year", null, asOf, List.of(), StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
