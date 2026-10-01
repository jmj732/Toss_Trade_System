package com.jmj.trade.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;

import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

    @Test
    void endpointFailureKeepsSuccessfulEndpointValuesAndTheirOwnAsOf() {
        SERVER.stubFor(get(urlPathEqualTo("/quote"))
                .willReturn(aResponse().withBody("{\"price\":189.4,\"timestamp\":1785614400}")));
        SERVER.stubFor(get(urlPathEqualTo("/statements"))
                .willReturn(aResponse().withStatus(503).withBody("provider error")));
        var configuration = new StockAnalysisProviderProperties.ProviderConfiguration(
                true, true, URI.create(SERVER.baseUrl()), "/unused", "provider-secret", "", "",
                Map.of(), Set.of(), "stock-analysis-test", Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO, 1000, Duration.ofSeconds(1),
                "", Map.of(), Map.of(
                "quote", endpoint("/quote", "/timestamp", "EPOCH_SECONDS", "price.latestPrice", "/price"),
                "fundamentals", endpoint("/statements", "/acceptedAt", "INSTANT", "fundamental.revenueTTM", "/revenue")));
        var input = new StockAnalysisInputAssembler(
                new StockDataProviderRegistry(List.of(new ConfiguredStockDataProvider(
                        StockDataProviderId.FMP, configuration, new tools.jackson.databind.ObjectMapper()))),
                Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC))
                .assemble("AAPL", Map.of());

        assertThat(input.observations()).filteredOn(item -> item.field().equals("price.latestPrice"))
                .singleElement().satisfies(item -> {
                    assertThat(item.value().asText()).isEqualTo("189.4");
                    assertThat(item.asOf()).isEqualTo(Instant.ofEpochSecond(1785614400));
                    assertThat(item.provider()).isEqualTo(StockDataProviderId.FMP);
                    assertThat(item.missingData()).isEmpty();
                });
        assertThat(input.observations()).filteredOn(item -> item.field().equals("fundamental.revenueTTM"))
                .singleElement().satisfies(item -> {
                    assertThat(item.value()).isNull();
                    assertThat(item.asOf()).isNull();
                    assertThat(item.provider()).isEqualTo(StockDataProviderId.FMP);
                    assertThat(item.missingData()).containsExactly("PROVIDER_UNAVAILABLE", "PROVIDER_HTTP_503");
                });
    }

    @Test
    void endpointMetadataSeparatesForecastHorizonFromObservedSnapshotAndAddsVerifiedPriceSession() {
        SERVER.stubFor(get(urlPathEqualTo("/eod"))
                .willReturn(aResponse().withBody("[{\"date\":\"2026-08-01\",\"close\":189.4}]")));
        SERVER.stubFor(get(urlPathEqualTo("/estimates"))
                .willReturn(aResponse().withBody("[{\"date\":\"2027-09-30\",\"revenueAvg\":510000000000}]")));
        var observedAt = Instant.parse("2026-08-02T00:00:00Z");
        var eod = new StockAnalysisProviderProperties.EndpointConfiguration(
                "/eod", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "DATE", "/0/date",
                Map.of("price.regularClose", "/0/close", "price.latestPrice", "/0/close"),
                StockAnalysisProviderProperties.AsOfMode.SOURCE_AS_OF,
                StockAnalysisProviderProperties.PriceSession.REGULAR_CLOSE);
        var estimates = new StockAnalysisProviderProperties.EndpointConfiguration(
                "/estimates", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "DATE", "/0/date",
                Map.of("consensus.revenueConsensus", "/0/revenueAvg", "consensus.horizon", "/0/date"),
                StockAnalysisProviderProperties.AsOfMode.OBSERVED_AT, null);
        var configuration = providerConfiguration(Map.of("eod", eod, "estimates", estimates));
        var provider = new ConfiguredStockDataProvider(StockDataProviderId.FMP, configuration,
                new tools.jackson.databind.ObjectMapper(), Clock.fixed(observedAt, ZoneOffset.UTC));
        var input = new StockAnalysisInputAssembler(new StockDataProviderRegistry(List.of(provider)),
                Clock.fixed(observedAt, ZoneOffset.UTC)).assemble("AAPL", Map.of());

        assertThat(provider.fields()).contains("price.session");
        assertThat(input.observations()).filteredOn(item -> item.field().equals("price.session"))
                .singleElement().satisfies(item -> {
                    assertThat(item.value().asText()).isEqualTo("REGULAR_CLOSE");
                    assertThat(item.asOf()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
                    assertThat(item.asOfBasis()).isEqualTo(StockAnalysisInput.AsOfBasis.SOURCE_AS_OF);
                });
        assertThat(input.observations()).filteredOn(item -> item.field().equals("consensus.revenueConsensus"))
                .singleElement().satisfies(item -> {
                    assertThat(item.asOf()).isEqualTo(observedAt);
                    assertThat(item.asOf()).isBefore(Instant.parse("2027-09-30T00:00:00Z"));
                    assertThat(item.asOfBasis()).isEqualTo(StockAnalysisInput.AsOfBasis.OBSERVED_AT);
                });
        assertThat(input.observations()).filteredOn(item -> item.field().equals("consensus.horizon"))
                .singleElement().satisfies(item -> {
                    assertThat(item.value().asText()).isEqualTo("2027-09-30");
                    assertThat(item.asOf()).isEqualTo(observedAt);
                    assertThat(item.asOfBasis()).isEqualTo(StockAnalysisInput.AsOfBasis.OBSERVED_AT);
                });
    }

    @Test
    void jsonPointerRootPreservesEodHistoryArrayAndNullRootIsMissing() {
        SERVER.stubFor(get(urlPathEqualTo("/history"))
                .willReturn(aResponse().withBody("["
                        + "{\"date\":\"2026-08-03\",\"close\":190.0},"
                        + "{\"date\":\"2026-07-31\",\"close\":189.0}]")));
        SERVER.stubFor(get(urlPathEqualTo("/history-null"))
                .willReturn(aResponse().withBody("null")));
        var history = new StockAnalysisProviderProperties.EndpointConfiguration(
                "/history", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "DATE", "/0/date",
                Map.of("price.regularCloseHistory", ""),
                StockAnalysisProviderProperties.AsOfMode.SOURCE_AS_OF, null);
        var historyProvider = new ConfiguredStockDataProvider(StockDataProviderId.FMP,
                providerConfiguration(Map.of("history", history)), new tools.jackson.databind.ObjectMapper());

        var value = historyProvider.fetch(new ProviderRequest("AAPL", Map.of())).getFirst();

        assertThat(value.field()).isEqualTo("price.regularCloseHistory");
        assertThat(value.value().isArray()).isTrue();
        assertThat(value.value()).hasSize(2);
        assertThat(value.value().get(0).get("date").asText()).isEqualTo("2026-08-03");
        assertThat(value.asOf()).isEqualTo(Instant.parse("2026-08-03T00:00:00Z"));
        assertThat(value.missingData()).isEmpty();

        var nullHistory = new StockAnalysisProviderProperties.EndpointConfiguration(
                "/history-null", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "DATE", "/0/date",
                Map.of("price.regularCloseHistory", ""),
                StockAnalysisProviderProperties.AsOfMode.SOURCE_AS_OF, null);
        var nullProvider = new ConfiguredStockDataProvider(StockDataProviderId.FMP,
                providerConfiguration(Map.of("history", nullHistory)), new tools.jackson.databind.ObjectMapper());
        var missing = nullProvider.fetch(new ProviderRequest("AAPL", Map.of())).getFirst();
        assertThat(missing.value()).isNull();
        assertThat(missing.missingData()).contains("DATA_NOT_PRESENT", "AS_OF_UNAVAILABLE");

        assertThatThrownBy(() -> providerConfiguration(Map.of("bad", endpoint(
                "/bad", "", "DATE", "price.regularCloseHistory", "  "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON pointer");
    }

    @Test
    void endpointRejectsMappedOrDuplicateStaticPriceSessionFields() {
        assertThatThrownBy(() -> endpointWithSession(Map.of("price.session", "/session"),
                StockAnalysisProviderProperties.PriceSession.REGULAR_CLOSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price.session is supplied by priceSession metadata");

        var first = endpointWithSession(Map.of("price.latestPrice", "/price"),
                StockAnalysisProviderProperties.PriceSession.REGULAR_CLOSE);
        var second = endpointWithSession(Map.of("quote.price", "/price"),
                StockAnalysisProviderProperties.PriceSession.LIVE_REGULAR);
        assertThatThrownBy(() -> providerConfiguration(Map.of("first", first, "second", second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate fields");
    }

    @Test
    void providerRejectsDuplicateFieldsAcrossConfiguredEndpoints() {
        assertThatThrownBy(() -> new StockAnalysisProviderProperties.ProviderConfiguration(
                true, false, URI.create(SERVER.baseUrl()), "/unused", "provider-secret", "", "",
                Map.of(), Set.of(), "stock-analysis-test", Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO, 1000, Duration.ofSeconds(1),
                "", Map.of(), Map.of(
                "one", endpoint("/one", "", "INSTANT", "quote.price", "/price"),
                "two", endpoint("/two", "", "INSTANT", "quote.price", "/price"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate fields");
    }

    @Test
    void bindsEndpointMapAndKeepsLegacySingleEndpointProperties() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("stock-analysis.providers.fmp.enabled", "true"),
                Map.entry("stock-analysis.providers.fmp.base-url", SERVER.baseUrl()),
                Map.entry("stock-analysis.providers.fmp.api-key", "provider-secret"),
                Map.entry("stock-analysis.providers.fmp.path", "/legacy"),
                Map.entry("stock-analysis.providers.fmp.fields[quote.price]", "/price"),
                Map.entry("stock-analysis.providers.twelve-data.enabled", "true"),
                Map.entry("stock-analysis.providers.twelve-data.base-url", SERVER.baseUrl()),
                Map.entry("stock-analysis.providers.twelve-data.api-key", "provider-secret"),
                Map.entry("stock-analysis.providers.twelve-data.endpoints.quote.path", "/quote"),
                Map.entry("stock-analysis.providers.twelve-data.endpoints.quote.as-of-mode", "OBSERVED_AT"),
                Map.entry("stock-analysis.providers.twelve-data.endpoints.quote.price-session", "REGULAR_CLOSE"),
                Map.entry("stock-analysis.providers.twelve-data.endpoints.quote.as-of-path", "/timestamp"),
                Map.entry("stock-analysis.providers.twelve-data.endpoints.quote.as-of-format", "EPOCH_SECONDS"),
                Map.entry("stock-analysis.providers.twelve-data.endpoints.quote.fields[price.latestPrice]", "/price")));

        var properties = new Binder(source)
                .bind("stock-analysis", Bindable.of(StockAnalysisProviderProperties.class))
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.providers().get("fmp").fields()).containsEntry("quote.price", "/price");
        assertThat(properties.providers().get("fmp").configuredEndpoints()).hasSize(1);
        assertThat(properties.providers().get("twelve-data").configuredEndpoints())
                .singleElement().satisfies(endpoint -> {
                    assertThat(endpoint.path()).isEqualTo("/quote");
                    assertThat(endpoint.asOfPath()).isEqualTo("/timestamp");
                    assertThat(endpoint.asOfMode())
                            .isEqualTo(StockAnalysisProviderProperties.AsOfMode.OBSERVED_AT);
                    assertThat(endpoint.priceSession())
                            .isEqualTo(StockAnalysisProviderProperties.PriceSession.REGULAR_CLOSE);
                    assertThat(endpoint.fields()).containsEntry("price.latestPrice", "/price");
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

    private static StockAnalysisProviderProperties.ProviderConfiguration providerConfiguration(
            Map<String, StockAnalysisProviderProperties.EndpointConfiguration> endpoints
    ) {
        return new StockAnalysisProviderProperties.ProviderConfiguration(
                true, false, URI.create(SERVER.baseUrl()), "/unused", "provider-secret", "", "",
                Map.of(), Set.of(), "", Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO, 1000, Duration.ofSeconds(1),
                "", Map.of(), endpoints);
    }

    private static StockAnalysisProviderProperties.EndpointConfiguration endpointWithSession(
            Map<String, String> fields, StockAnalysisProviderProperties.PriceSession session
    ) {
        return new StockAnalysisProviderProperties.EndpointConfiguration(
                "/session", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT", "/timestamp",
                fields, StockAnalysisProviderProperties.AsOfMode.SOURCE_AS_OF, session);
    }

    private static StockAnalysisProviderProperties.EndpointConfiguration endpoint(
            String path, String asOfPath, String asOfFormat, String field, String pointer
    ) {
        return new StockAnalysisProviderProperties.EndpointConfiguration(
                path, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), asOfFormat, asOfPath,
                Map.of(field, pointer));
    }
}
