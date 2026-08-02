package com.jmj.trade.analysis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.observability.CorrelationIdFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TradingBackendApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockAnalysisWorkflowIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 8, 2, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final WireMockServer PROVIDERS =
            new WireMockServer(options().dynamicPort().globalTemplating(true));

    static {
        PROVIDERS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("analysis.service.base-url", PROVIDERS::baseUrl);
        registry.add("stock-analysis.providers.fmp.enabled", () -> "true");
        registry.add("stock-analysis.providers.fmp.base-url", PROVIDERS::baseUrl);
        registry.add("stock-analysis.providers.fmp.path", () -> "/provider");
        registry.add("stock-analysis.providers.fmp.as-of-path", () -> "/asOf");
        registry.add("stock-analysis.providers.fmp.fields.quote.price", () -> "/price");
        registry.add("stock-analysis.providers.fmp.api-key", () -> "provider-secret");
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE broker_connections, users CASCADE");
        PROVIDERS.resetAll();
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
        PROVIDERS.stubFor(get(urlPathEqualTo("/provider"))
                .willReturn(aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"price\":\"189.40\",\"asOf\":\"2026-08-01T20:00:00Z\"}")));
        PROVIDERS.stubFor(post("/internal/v2/stock-analysis-inputs")
                .willReturn(aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "requestId":"{{jsonPath request.body '$.requestId'}}",
                                  "schemaVersion":"1",
                                  "inputSnapshotId":"{{jsonPath request.body '$.input.snapshotId'}}",
                                  "symbol":"{{jsonPath request.body '$.input.symbol'}}",
                                  "status":"COMPLETED",
                                  "missingData":[],
                                  "observations":{{jsonPath request.body '$.input.observations'}}
                                }
                                """)));
    }

    @AfterAll
    static void stopProviders() {
        PROVIDERS.stop();
    }

    @Test
    void collectsProviderValuesPersistsSnapshotAndCompletesStockAnalysis() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/stock-analyses/AAPL")
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.result.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.observations[0].provider").value("FMP"))
                .andExpect(jsonPath("$.result.observations[0].value").value("189.40"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_input_snapshots", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_analysis_runs WHERE status = 'SUCCEEDED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_analysis_results", Integer.class))
                .isEqualTo(1);
        PROVIDERS.verify(1, postRequestedFor(urlPathEqualTo("/internal/v2/stock-analysis-inputs")));
    }
}
