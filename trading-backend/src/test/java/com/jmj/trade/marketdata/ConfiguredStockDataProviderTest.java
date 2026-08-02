package com.jmj.trade.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredStockDataProviderTest {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

    static {
        SERVER.start();
    }

    @BeforeEach
    void reset() {
        SERVER.resetAll();
    }

    @AfterAll
    static void stop() {
        SERVER.stop();
    }

    @Test
    void retriesTransientFailureAndKeepsProviderMetadata() {
        SERVER.stubFor(get(urlPathEqualTo("/provider"))
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503).withBody("raw-provider-response"))
                .willSetStateTo("recovered"));
        SERVER.stubFor(get(urlPathEqualTo("/provider"))
                .inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withBody("{\"price\":\"189.40\",\"asOf\":\"2026-08-01T20:00:00Z\"}")));

        var provider = provider(1);
        var value = provider.fetch(new ProviderRequest("AAPL", Map.of())).getFirst();

        assertThat(value.value().asText()).isEqualTo("189.40");
        assertThat(value.asOf()).hasToString("2026-08-01T20:00:00Z");
        assertThat(value.identifier()).isEqualTo("AAPL");
        SERVER.verify(2, getRequestedFor(urlPathEqualTo("/provider"))
                .withHeader("X-API-Key", equalTo("provider-secret")));
    }

    @Test
    void providerFailureDoesNotExposeRawResponse() {
        SERVER.stubFor(get(urlPathEqualTo("/provider"))
                .willReturn(aResponse().withStatus(503).withBody("raw-provider-response")));

        assertThatThrownBy(() -> provider(0).fetch(new ProviderRequest("AAPL", Map.of())))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, exception -> {
                    assertThat(exception.provider()).isEqualTo(StockDataProviderId.FMP);
                    assertThat(exception.getMessage()).doesNotContain("raw-provider-response");
                });
    }

    private static ConfiguredStockDataProvider provider(int retries) {
        var configuration = new StockAnalysisProviderProperties.ProviderConfiguration(
                true,
                false,
                URI.create(SERVER.baseUrl()),
                "/provider",
                "provider-secret",
                "X-API-Key",
                "",
                Map.of(),
                Set.of(),
                "stock-analysis-test",
                Map.of(),
                Map.of(),
                Map.of("quote.price", "{symbol}"),
                Map.of(),
                "INSTANT",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                retries,
                Duration.ZERO,
                1000,
                Duration.ofSeconds(1),
                "/asOf",
                Map.of("quote.price", "/price"));
        return new ConfiguredStockDataProvider(StockDataProviderId.FMP, configuration, new tools.jackson.databind.ObjectMapper());
    }
}
