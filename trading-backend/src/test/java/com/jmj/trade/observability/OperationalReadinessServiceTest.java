package com.jmj.trade.observability;

import com.jmj.trade.marketdata.StockDataProviderId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalReadinessServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void staleSourceDataFailsClosedAsStale() {
        var result = OperationalReadinessService.classify(
                true, true, List.of(), NOW.minusSeconds(301), NOW.minusSeconds(1),
                NOW, Duration.ofMinutes(5));

        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.ready()).isFalse();
        assertThat(result.lagMs()).isEqualTo(301_000L);
    }

    @Test
    void partialProviderDataIsDegradedWithoutDroppingHealthyFields() {
        var result = OperationalReadinessService.classify(
                true, true, List.of("DATA_NOT_PRESENT"), NOW.minusSeconds(10), NOW,
                NOW, Duration.ofMinutes(5));

        assertThat(result.status()).isEqualTo("DEGRADED");
        assertThat(result.ready()).isFalse();
        assertThat(result.missingData()).containsExactly("DATA_NOT_PRESENT");
    }

    @Test
    void unavailableSecretBlocksProviderProbe() {
        var result = OperationalReadinessService.classify(
                true, false, List.of(), null, NOW, NOW, Duration.ofMinutes(5));

        assertThat(result.status()).isEqualTo("SECRET_MISSING");
        assertThat(result.ready()).isFalse();
    }

    @Test
    void evidenceSummaryNeverContainsProviderValueOrSecret() {
        var evidence = OperationalReadinessService.evidenceJson(
                List.of(new OperationalReadinessService.ProviderEvidence(
                        "FMP", "HEALTHY", 1000L, List.of(),
                        NOW.minusSeconds(1), NOW)));

        assertThat(evidence).contains("FMP", "HEALTHY", "1000");
        assertThat(evidence).doesNotContain("provider-secret", "189.40", "raw-response");
    }

    @Test
    void notConfiguredProviderFailsOverallAndTriggersAlertPath() {
        var overall = OperationalReadinessService.overall(List.of(
                new OperationalReadinessService.ProviderEvidence(
                        "FMP", "NOT_CONFIGURED", null, List.of(), null, NOW)));

        assertThat(overall).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void dynamicAlphaAndEndpointOnlyProvidersAreConfiguredWithoutBroadeningEmptyGenericProviders() {
        var alpha = new OperationalReadinessService.ProviderConfiguration(true,
                URI.create("https://www.alphavantage.co/query"), Map.of(), false, "key", "");
        assertThat(OperationalReadinessService.configured(StockDataProviderId.ALPHA_VANTAGE, alpha)).isTrue();
        var alphaStatus = OperationalReadinessService.classify(true, true,
                List.of("consensus.currency"), NOW.minusSeconds(1), NOW, NOW, Duration.ofMinutes(5));
        assertThat(alphaStatus.status()).isEqualTo("DEGRADED");

        var endpointOnlyFmp = new OperationalReadinessService.ProviderConfiguration(true,
                URI.create("https://financialmodelingprep.com"), Map.of(), true, "key", "");
        assertThat(OperationalReadinessService.configured(StockDataProviderId.FMP, endpointOnlyFmp)).isTrue();

        var emptyGeneric = new OperationalReadinessService.ProviderConfiguration(true,
                URI.create("https://finnhub.io"), Map.of(), false, "key", "");
        assertThat(OperationalReadinessService.configured(StockDataProviderId.FINNHUB, emptyGeneric)).isFalse();
    }
}
