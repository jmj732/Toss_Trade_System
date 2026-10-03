package com.jmj.trade.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlphaVantageEarningsEstimatesProviderTest {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-03T01:44:33.982953Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void selectsNearestFutureFiscalYearFromMixedDescendingRows() throws Exception {
        var body = Map.of(
                "symbol", "AVT",
                "estimates", List.of(
                        row("2028-06-30", "fiscal year", "4.8", "1800"),
                        row("2027-06-30", "fiscal year", "3.2", "1400"),
                        row("2026-12-31", "fiscal quarter", "0.9", "350"),
                        row("2026-09-30", "fiscal quarter", "0.8", "325"),
                        row("2026-06-30", "fiscal quarter", "0.7", "300")));
        SERVER.stubFor(get(urlPathEqualTo("/query"))
                .withQueryParam("function", equalTo("EARNINGS_ESTIMATES"))
                .withQueryParam("symbol", equalTo("AVT"))
                .withQueryParam("apikey", equalTo("test-token"))
                .willReturn(aResponse().withBody(MAPPER.writeValueAsString(body))));

        var provider = provider(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));
        var values = provider.fetch(new ProviderRequest("AVT", Map.of()));

        assertThat(values).filteredOn(value -> value.field().startsWith("consensus.")).hasSize(4);
        assertThat(value(values, "consensus.horizon").value().asText()).isEqualTo("2027-06-30");
        assertThat(value(values, "consensus.epsConsensus").value().decimalValue())
                .isEqualByComparingTo("3.2");
        assertThat(value(values, "consensus.revenueConsensus").value().decimalValue())
                .isEqualByComparingTo("1400");
        assertThat(values).allSatisfy(value -> {
            assertThat(value.asOf()).isEqualTo(OBSERVED_AT);
            assertThat(value.asOfBasis()).isEqualTo(StockAnalysisInput.AsOfBasis.OBSERVED_AT);
        });
        assertThat(value(values, "consensus.revenueConsensus").unit())
                .isEqualTo("CURRENCY_UNVERIFIED");
        assertThat(value(values, "consensus.currency").missingData())
                .containsExactly("CURRENCY_UNAVAILABLE");
        SERVER.verify(getRequestedFor(urlPathEqualTo("/query"))
                .withQueryParam("function", equalTo("EARNINGS_ESTIMATES"))
                .withQueryParam("symbol", equalTo("AVT"))
                .withQueryParam("apikey", equalTo("test-token")));
    }

    @Test
    void rejectsHttp200ProviderMessagesWithoutExposingTheirText() throws Exception {
        SERVER.stubFor(get(urlPathEqualTo("/query"))
                .willReturn(aResponse().withBody(MAPPER.writeValueAsString(Map.of(
                        "Information", "provider-secret quota message")))));

        assertThatThrownBy(() -> provider(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC)
                ).fetch(new ProviderRequest("AVT", Map.of())))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("API_ERROR")
                .hasMessageNotContaining("provider-secret quota message");
    }

    @Test
    void rejectsNullJsonRootAsInvalidResponse() {
        SERVER.stubFor(get(urlPathEqualTo("/query"))
                .willReturn(aResponse().withBody("null")));

        assertThatThrownBy(() -> provider(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC)
                ).fetch(new ProviderRequest("AVT", Map.of())))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("INVALID_RESPONSE");
    }

    @Test
    void keepsRevenueWhenEpsEstimateIsNotNumeric() throws Exception {
        var body = Map.of("symbol", "AVT", "estimates", List.of(row(
                "2027-06-30", "fiscal year", "not-a-decimal", "1400")));
        SERVER.stubFor(get(urlPathEqualTo("/query"))
                .willReturn(aResponse().withBody(MAPPER.writeValueAsString(body))));

        var values = provider(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC))
                .fetch(new ProviderRequest("AVT", Map.of()));

        assertThat(value(values, "consensus.epsConsensus").value()).isNull();
        assertThat(value(values, "consensus.epsConsensus").missingData()).containsExactly("INVALID_NUMERIC");
        assertThat(value(values, "consensus.revenueConsensus").value().decimalValue())
                .isEqualByComparingTo("1400");
    }

    @Test
    void onlyAlphaVantageMayUseDynamicMappingWithoutJsonPointers() {
        var configuration = configuration();

        assertThatCode(() -> new StockAnalysisProviderProperties(Map.of("alpha-vantage", configuration)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new StockAnalysisProviderProperties(Map.of("fmp", configuration)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("enabled provider requires fields or endpoints");
    }

    @Test
    void providerFactoryUsesAlphaMapperAndKeepsGenericFmpProvider() {
        var properties = new StockAnalysisProviderProperties(Map.of(
                "alpha-vantage", configuration(),
                "fmp", configuration(Map.of("consensus.horizon", "/date"))));

        var providers = new StockAnalysisProviderConfiguration()
                .stockDataProviderRegistry(properties, MAPPER).providers();

        assertThat(providers).filteredOn(provider -> provider.id() == StockDataProviderId.ALPHA_VANTAGE)
                .singleElement().isInstanceOf(AlphaVantageEarningsEstimatesProvider.class);
        assertThat(providers).filteredOn(provider -> provider.id() == StockDataProviderId.FMP)
                .singleElement().isInstanceOf(ConfiguredStockDataProvider.class);
    }

    @Test
    void selectedIntradayPriceFieldsDoNotCallAlphaVantage() {
        var provider = provider(Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));
        var assembler = new StockAnalysisInputAssembler(
                new StockDataProviderRegistry(List.of(provider)), Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));

        var input = assembler.assemble("AVT", Map.of(), Set.of("price.latestPrice", "price.session"));

        assertThat(input.observations()).isEmpty();
        SERVER.verify(0, getRequestedFor(urlPathEqualTo("/query")));
    }

    private static AlphaVantageEarningsEstimatesProvider provider(Clock clock) {
        return new AlphaVantageEarningsEstimatesProvider(configuration(), MAPPER, clock);
    }

    private static StockAnalysisProviderProperties.ProviderConfiguration configuration() {
        return configuration(Map.of());
    }

    private static StockAnalysisProviderProperties.ProviderConfiguration configuration(Map<String, String> fields) {
        return new StockAnalysisProviderProperties.ProviderConfiguration(
                true, true, URI.create(SERVER.baseUrl()), "/query", "test-token", "", "apikey",
                Map.of("function", "EARNINGS_ESTIMATES"), Set.of(), "", Map.of(), Map.of(), Map.of(), Map.of(),
                "INSTANT", Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO,
                1000, Duration.ofSeconds(1), "", fields);
    }

    private static Map<String, Object> row(String date, String horizon, String eps, String revenue) {
        return Map.ofEntries(
                Map.entry("date", date),
                Map.entry("horizon", horizon),
                Map.entry("eps_estimate_average", eps),
                Map.entry("eps_estimate_average_7_days_ago", "3.1"),
                Map.entry("eps_estimate_average_30_days_ago", "3.0"),
                Map.entry("eps_estimate_average_60_days_ago", "2.9"),
                Map.entry("eps_estimate_average_90_days_ago", "2.8"),
                Map.entry("eps_estimate_revision_up_trailing_7_days", "2"),
                Map.entry("eps_estimate_revision_up_trailing_30_days", "4"),
                Map.entry("eps_estimate_revision_down_trailing_7_days", "1"),
                Map.entry("eps_estimate_revision_down_trailing_30_days", "3"),
                Map.entry("revenue_estimate_average", revenue),
                Map.entry("eps_estimate_analyst_count", "15"),
                Map.entry("eps_estimate_high", eps),
                Map.entry("eps_estimate_low", eps),
                Map.entry("revenue_estimate_analyst_count", "12"),
                Map.entry("revenue_estimate_high", revenue),
                Map.entry("revenue_estimate_low", revenue));
    }

    private static ProviderValue value(List<ProviderValue> values, String field) {
        return values.stream().filter(value -> value.field().equals(field)).findFirst().orElseThrow();
    }
}
