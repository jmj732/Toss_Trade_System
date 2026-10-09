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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredStockDataProviderSelectionTest {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

    static {
        SERVER.start();
    }

    @BeforeEach
    void reset() {
        SERVER.resetAll();
        SERVER.stubFor(get(urlPathEqualTo("/quote"))
                .willReturn(aResponse().withBody("{\"price\":189.4,\"volume\":20,\"change\":1.1}")));
        SERVER.stubFor(get(urlPathEqualTo("/history"))
                .willReturn(aResponse().withBody("[{\"date\":\"2026-09-30\",\"close\":189.4}]")));
        SERVER.stubFor(get(urlPathEqualTo("/fundamentals"))
                .willReturn(aResponse().withBody("{\"marketCap\":1000}")));
    }

    @AfterAll
    static void stop() {
        SERVER.stop();
    }

    @Test
    void quoteOnlySelectionCallsCheapQuoteEndpointAndSkipsHistoryAndFundamentals() {
        var quote = new StockAnalysisProviderProperties.EndpointConfiguration(
                "/quote", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT", "",
                Map.of("quote.price", "/price", "quote.volume", "/volume",
                        "quote.change-percent", "/change", "price.latestPrice", "/price"),
                StockAnalysisProviderProperties.AsOfMode.OBSERVED_AT, null);
        var history = new StockAnalysisProviderProperties.EndpointConfiguration(
                "/history", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "DATE", "/0/date",
                Map.of("price.regularCloseHistory", ""),
                StockAnalysisProviderProperties.AsOfMode.SOURCE_AS_OF,
                StockAnalysisProviderProperties.PriceSession.REGULAR_CLOSE);
        var fundamentals = new StockAnalysisProviderProperties.EndpointConfiguration(
                "/fundamentals", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT", "",
                Map.of("fundamental.marketCap", "/marketCap"),
                StockAnalysisProviderProperties.AsOfMode.OBSERVED_AT, null);
        var configuration = new StockAnalysisProviderProperties.ProviderConfiguration(
                true, false, URI.create(SERVER.baseUrl()), "/unused", "test-key", "", "",
                Map.of(), Set.of(), "selection-test", Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT",
                Duration.ofSeconds(5), Duration.ofSeconds(5), 0, Duration.ZERO, 100, Duration.ofSeconds(1),
                "", Map.of(), Map.of("quote", quote, "history", history, "fundamentals", fundamentals));
        var provider = new ConfiguredStockDataProvider(
                StockDataProviderId.FMP, configuration, new tools.jackson.databind.ObjectMapper());

        var values = provider.fetch(new ProviderRequest("AAPL", Map.of()),
                Set.of("quote.price", "quote.volume", "quote.change-percent", "price.latestPrice", "price.session"));

        assertThat(values).extracting(ProviderValue::field)
                .containsExactlyInAnyOrder("quote.price", "quote.volume", "quote.change-percent", "price.latestPrice");
        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/quote")));
        SERVER.verify(0, getRequestedFor(urlPathEqualTo("/history")));
        SERVER.verify(0, getRequestedFor(urlPathEqualTo("/fundamentals")));
    }
}
