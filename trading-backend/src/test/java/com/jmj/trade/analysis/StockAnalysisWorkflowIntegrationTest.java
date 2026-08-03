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
        PROVIDERS.stubFor(post("/internal/v3/stock-analyses")
                .willReturn(aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "requestId":"{{jsonPath request.body '$.requestId'}}",
                                  "schemaVersion":"1",
                                  "inputSnapshotId":"{{jsonPath request.body '$.input.snapshotId'}}",
                                  "symbol":"{{jsonPath request.body '$.input.symbol'}}",
                                  "asOf":"{{jsonPath request.body '$.input.collectedAt'}}",
                                  "status":"DEGRADED",
                                  "missingData":["fundamental:FIELD_MISSING:fundamental.net_income","valuation:FIELD_MISSING:fundamental.eps","technical:FIELD_MISSING:technical.sma20","marketRegime:FIELD_MISSING:macro.vix"],
                                  "observations":{{jsonPath request.body '$.input.observations'}},
                                  "analyzers":[
                                    {"analyzer":"fundamental","confidence":"0","missingData":["FIELD_MISSING:fundamental.net_income"],"metrics":[
                                      {"name":"fundamental.profit_margin","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:fundamental.net_income"]},
                                      {"name":"fundamental.roe","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:fundamental.net_income"]},
                                      {"name":"fundamental.debt_to_equity","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:fundamental.net_income"]},
                                      {"name":"fundamental.operating_cash_flow_margin","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:fundamental.net_income"]}
                                    ]},
                                    {"analyzer":"valuation","confidence":"0","missingData":["FIELD_MISSING:fundamental.eps"],"metrics":[
                                      {"name":"valuation.pe","value":null,"unit":"multiple","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:fundamental.eps"]},
                                      {"name":"valuation.price_to_book","value":null,"unit":"multiple","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:fundamental.eps"]},
                                      {"name":"valuation.price_to_sales","value":null,"unit":"multiple","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:fundamental.eps"]},
                                      {"name":"valuation.fcf_yield","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:fundamental.eps"]}
                                    ]},
                                    {"analyzer":"technical","confidence":"0","missingData":["FIELD_MISSING:technical.sma20"],"metrics":[
                                      {"name":"technical.price_vs_sma20","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:technical.sma20"]},
                                      {"name":"technical.price_vs_sma50","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:technical.sma20"]},
                                      {"name":"technical.sma_trend","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:technical.sma20"]},
                                      {"name":"technical.rsi14","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:technical.sma20"]},
                                      {"name":"technical.volatility20","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:technical.sma20"]}
                                    ]},
                                    {"analyzer":"marketRegime","confidence":"0","missingData":["FIELD_MISSING:macro.vix"],"metrics":[
                                      {"name":"marketRegime.vix","value":null,"unit":"index","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:macro.vix"]},
                                      {"name":"marketRegime.sp500Return20d","value":null,"unit":"ratio","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:macro.vix"]},
                                      {"name":"marketRegime.state","value":null,"unit":"state","asOf":null,"provenance":[],"missingData":["FIELD_MISSING:macro.vix"]}
                                    ]}
                                  ]
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
                .andExpect(jsonPath("$.result.status").value("DEGRADED"))
                .andExpect(jsonPath("$.result.observations[0].provider").value("FMP"))
                .andExpect(jsonPath("$.result.observations[0].value").value("189.40"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/stock-analyses/AAPL")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.analyzers[0].analyzer").value("fundamental"))
                .andExpect(jsonPath("$.result.observations[0].provider").value("FMP"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_input_snapshots", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_analysis_runs WHERE status = 'SUCCEEDED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_analysis_results", Integer.class))
                .isEqualTo(1);
        PROVIDERS.verify(1, postRequestedFor(urlPathEqualTo("/internal/v3/stock-analyses")));
    }

    @Test
    void returnsNotFoundWhenNoCompletedAnalysisExists() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/stock-analyses/AAPL")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_ANALYSIS_RESULT_NOT_FOUND"));
    }

    @Test
    void exposesOwnerSafeImmutableAnalysisHistoryAndRunSelection() throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/stock-analyses/AAPL")
                        .with(user(USER_ID.toString()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        var runId = result.getResponse().getContentAsString()
                .replaceFirst(".*\\\"runId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/stock-analyses/AAPL/history").param("limit", "7")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value(runId))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].result.status").value("DEGRADED"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/stock-analyses/AAPL/runs/" + runId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputSnapshotId").isNotEmpty());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/stock-analyses/AAPL/history")
                        .with(user(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
    }
}
