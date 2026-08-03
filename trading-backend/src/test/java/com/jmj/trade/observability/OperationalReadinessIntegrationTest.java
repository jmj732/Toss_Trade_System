package com.jmj.trade.observability;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "spring.datasource.hikari.maximum-pool-size=4",
                "stock-analysis.providers.fmp.enabled=true"
        })
class OperationalReadinessIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final WireMockServer PROVIDER = new WireMockServer(options().dynamicPort());

    static {
        PROVIDER.start();
    }

    @Autowired
    private OperationalReadinessService readiness;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("stock-analysis.providers.fmp.base-url", PROVIDER::baseUrl);
        registry.add("stock-analysis.providers.fmp.path", () -> "/quote");
        registry.add("stock-analysis.providers.fmp.api-key", () -> "provider-secret");
        registry.add("stock-analysis.providers.fmp.api-key-header", () -> "X-API-Key");
        registry.add("stock-analysis.providers.fmp.fields[quote.price]", () -> "/price");
        registry.add("stock-analysis.providers.fmp.as-of-path", () -> "/asOf");
        registry.add("stock-analysis.providers.fmp.max-retries", () -> "0");
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @BeforeEach
    void clean() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        PROVIDER.resetAll();
        jdbc.execute("TRUNCATE production_readiness_checks, notification_outbox_events, users CASCADE");
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @Test
    void injectedProviderFailureIsDegradedAndRerunnableWithoutOrderSideEffects() {
        PROVIDER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/quote"))
                .willReturn(aResponse().withStatus(503).withBody("raw-provider-response")));

        var failed = readiness.checkProviders(USER_ID, "AAPL", "test-operator");

        assertThat(failed.providers()).filteredOn(item -> item.provider().equals("FMP"))
                .singleElement().extracting(OperationalReadinessService.ProviderView::status)
                .isEqualTo("UNAVAILABLE");
        assertThat(failed.status()).isEqualTo("DEGRADED");
        assertThat(failed.alerts()).contains("PROVIDER_FMP_UNAVAILABLE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox_events WHERE event_type = 'PRODUCTION_READINESS_ALERT'",
                Long.class)).isEqualTo(1L);

        PROVIDER.resetAll();
        PROVIDER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/quote"))
                .willReturn(aResponse().withBody("{\"price\":\"189.40\",\"asOf\":\"%s\"}"
                        .formatted(Instant.now().minusSeconds(10)))));

        var recovered = readiness.checkProviders(USER_ID, "AAPL", "test-operator");

        assertThat(recovered.providers()).filteredOn(item -> item.provider().equals("FMP"))
                .singleElement().extracting(OperationalReadinessService.ProviderView::status)
                .isEqualTo("HEALTHY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production_readiness_checks", Long.class))
                .isEqualTo(2L);
        var evidence = jdbc.queryForObject(
                "SELECT evidence::text FROM production_readiness_checks ORDER BY created_at DESC LIMIT 1",
                String.class);
        assertThat(evidence).doesNotContain("provider-secret", "189.40", "raw-provider-response");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_intents", Long.class)).isZero();
    }

    @Test
    void readinessRoutesRequireAuthAndCsrfAndRedactProviderSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/operations/readiness"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operations/readiness")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("provider-secret"))));

        mockMvc.perform(post("/api/v1/operations/readiness/provider-check")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"AAPL\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/operations/readiness/provider-check")
                        .with(user(USER_ID.toString())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"bad symbol\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCTION_READINESS_INPUT_INVALID"));
    }
}
