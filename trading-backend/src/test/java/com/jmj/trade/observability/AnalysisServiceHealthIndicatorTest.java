package com.jmj.trade.observability;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisServiceHealthIndicatorTest {

    @Test
    void reportsAnalysisReadinessWithoutLeakingResponseContent() {
        var server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get(urlEqualTo("/internal/v1/ready"))
                    .willReturn(aResponse().withStatus(200).withBody("secret-body")));
            var indicator = new AnalysisServiceHealthIndicator(
                    server.baseUrl(), Duration.ofMillis(300), Duration.ofMillis(300));

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

            server.resetAll();
            server.stubFor(get(urlEqualTo("/internal/v1/ready"))
                    .willReturn(aResponse().withStatus(503).withBody("account-1234")));
            var health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails().toString())
                    .doesNotContain("account-1234", "secret-body");
        } finally {
            server.stop();
        }
    }
}
