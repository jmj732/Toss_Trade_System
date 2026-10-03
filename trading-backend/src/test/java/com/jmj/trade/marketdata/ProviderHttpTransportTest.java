package com.jmj.trade.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
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

class ProviderHttpTransportTest {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

    static {
        SERVER.start();
    }

    @AfterAll
    static void stop() {
        SERVER.stop();
    }

    @Test
    void computesBoundedExponentialRetryDelays() {
        assertThat(ProviderHttpTransport.retryDelay(Duration.ofSeconds(2), 0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(ProviderHttpTransport.retryDelay(Duration.ofSeconds(2), 1)).isEqualTo(Duration.ofSeconds(4));
        assertThat(ProviderHttpTransport.retryDelay(Duration.ofSeconds(2), 2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(ProviderHttpTransport.retryDelay(Duration.ofSeconds(2), 10)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void retriesThrottledRequestsAndSendsDeclaredUserAgent() {
        SERVER.resetAll();
        SERVER.stubFor(get(urlPathEqualTo("/sec"))
                .inScenario("SEC throttling")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("retry-1"));
        SERVER.stubFor(get(urlPathEqualTo("/sec"))
                .inScenario("SEC throttling")
                .whenScenarioStateIs("retry-1")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("retry-2"));
        SERVER.stubFor(get(urlPathEqualTo("/sec"))
                .inScenario("SEC throttling")
                .whenScenarioStateIs("retry-2")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("success"));
        SERVER.stubFor(get(urlPathEqualTo("/sec"))
                .inScenario("SEC throttling")
                .whenScenarioStateIs("success")
                .willReturn(aResponse().withBody("ok")));

        var response = transport(3, Duration.ofMillis(1)).get(URI.create(SERVER.baseUrl() + "/sec"));

        assertThat(response).isEqualTo("ok");
        SERVER.verify(4, getRequestedFor(urlPathEqualTo("/sec"))
                .withHeader("User-Agent", equalTo("test@example.com")));
    }

    @Test
    void sharesTheSecPacingStateAcrossTransportInstances() {
        SERVER.resetAll();
        SERVER.stubFor(get(urlPathEqualTo("/sec")).willReturn(aResponse().withBody("ok")));
        var policyInterval = Duration.ofMillis(1);
        var first = transport(0, Duration.ZERO, policyInterval);
        var second = transport(0, Duration.ZERO, policyInterval);

        first.get(URI.create(SERVER.baseUrl() + "/sec"));
        var started = System.nanoTime();
        second.get(URI.create(SERVER.baseUrl() + "/sec"));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(450));
    }

    @Test
    void surfacesPersistent429AfterTheConfiguredRetryBound() {
        SERVER.resetAll();
        SERVER.stubFor(get(urlPathEqualTo("/sec"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")));
        var transport = transport(1, Duration.ZERO);

        assertThatThrownBy(() -> transport.get(URI.create(SERVER.baseUrl() + "/sec")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("HTTP_429");
        SERVER.verify(2, getRequestedFor(urlPathEqualTo("/sec")));
    }

    @Test
    void honorsRetryAfterBeyondTheBackoffBoundWithoutRetryingOrBlocking() {
        SERVER.resetAll();
        SERVER.stubFor(get(urlPathEqualTo("/sec"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));
        var transport = isolatedTransport(3, Duration.ofMillis(1));
        var started = System.nanoTime();

        assertThatThrownBy(() -> transport.get(URI.create(SERVER.baseUrl() + "/sec")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("HTTP_429");
        assertThatThrownBy(() -> transport.get(URI.create(SERVER.baseUrl() + "/sec")))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("HTTP_429");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/sec")));
    }

    private static ProviderHttpTransport transport(int retries, Duration backoff) {
        return transport(retries, backoff, Duration.ofSeconds(1));
    }

    private static ProviderHttpTransport transport(int retries, Duration backoff, Duration rateLimitWindow) {
        var configuration = configuration(retries, backoff, rateLimitWindow);
        return new ProviderHttpTransport(StockDataProviderId.SEC, configuration);
    }

    private static ProviderHttpTransport isolatedTransport(int retries, Duration backoff) {
        var configuration = configuration(retries, backoff, Duration.ofSeconds(1));
        return new ProviderHttpTransport(StockDataProviderId.SEC, configuration,
                new ProviderRateLimiter(StockDataProviderId.SEC, configuration.transportPolicy(), false));
    }

    private static StockAnalysisProviderProperties.ProviderConfiguration configuration(
            int retries, Duration backoff, Duration rateLimitWindow) {
        return new StockAnalysisProviderProperties.ProviderConfiguration(
                true, false, URI.create(SERVER.baseUrl()), "/", "", "", "", Map.of(), Set.of(),
                "test@example.com", Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT",
                Duration.ofSeconds(1), Duration.ofSeconds(1), retries, backoff,
                1000, rateLimitWindow, "", Map.of());
    }
}
