package com.jmj.trade.prediction;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.SellableQuantitySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "prediction.evaluation.enabled=false",
                "prediction.ingestion-api-key.cleanup.enabled=false"
        })
@Import(StockForecastIntegrationTest.ForecastConfiguration.class)
class StockForecastIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CONNECTION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID SNAPSHOT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID RUN_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final Instant AS_OF = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant SOURCE_AS_OF = Instant.parse("2026-08-01T20:00:00Z");
    private static final WireMockServer ANALYSIS =
            new WireMockServer(options().dynamicPort().globalTemplating(true));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        ANALYSIS.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("analysis.service.base-url", ANALYSIS::baseUrl);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @org.junit.jupiter.api.AfterAll
    static void stop() {
        ANALYSIS.stop();
        REDIS.stop();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private WebApplicationContext context;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    private StringRedisTemplate redis;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE stock_forecasts, stock_analysis_results, stock_analysis_runs, "
                + "analysis_input_snapshots, analysis_prediction_outcomes, analysis_predictions, "
                + "prediction_model_versions, broker_connections, users CASCADE");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        ANALYSIS.resetAll();
        ANALYSIS.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlPathEqualTo("/internal/v4/stock-forecasts"))
                .willReturn(aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "requestId":"{{jsonPath request.body '$.requestId'}}",
                                  "schemaVersion":"1",
                                  "inputSnapshotId":"{{jsonPath request.body '$.analysis.inputSnapshotId'}}",
                                  "symbol":"{{jsonPath request.body '$.analysis.symbol'}}",
                                  "asOf":"{{jsonPath request.body '$.analysis.asOf'}}",
                                  "evaluatedAt":"{{jsonPath request.body '$.evaluatedAt'}}",
                                  "status":"COMPLETED",
                                  "missingData":[],
                                  "confidence":"1",
                                  "modelVersion":"{{jsonPath request.body '$.modelVersion'}}",
                                  "contractVersion":"{{jsonPath request.body '$.contractVersion'}}",
                                  "forecasts":[
                                    {"name":"forecast.d1_up_probability","value":"0.55","unit":"probability","asOf":"2026-08-01T20:00:00Z","provenance":[{"provider":"FMP","field":"quote.price","asOf":"2026-08-01T20:00:00Z","collectedAt":"2026-08-02T00:00:00Z"}],"missingData":[]},
                                    {"name":"forecast.d5_expected_return","value":"0.02","unit":"ratio","asOf":"2026-08-01T20:00:00Z","provenance":[{"provider":"FMP","field":"quote.price","asOf":"2026-08-01T20:00:00Z","collectedAt":"2026-08-02T00:00:00Z"}],"missingData":[]},
                                    {"name":"forecast.d20_expected_return","value":"0.05","unit":"ratio","asOf":"2026-08-01T20:00:00Z","provenance":[{"provider":"FMP","field":"quote.price","asOf":"2026-08-01T20:00:00Z","collectedAt":"2026-08-02T00:00:00Z"}],"missingData":[]},
                                    {"name":"forecast.expected_max_loss","value":"-0.10","unit":"ratio","asOf":"2026-08-01T20:00:00Z","provenance":[{"provider":"FMP","field":"quote.price","asOf":"2026-08-01T20:00:00Z","collectedAt":"2026-08-02T00:00:00Z"}],"missingData":[]}
                                  ]
                                }
                                """)));
        insertUser();
        insertConnection();
        insertAnalysis(true);
    }

    @Test
    void createsSnapshotLinkedForecastAndLedgerPredictionThenReusesIt() throws Exception {
        mockMvc.perform(post("/api/v1/stock-forecasts/AAPL")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.forecasts[0].value").value("0.55"))
                .andExpect(jsonPath("$.predictionId").isNotEmpty());

        mockMvc.perform(post("/api/v1/stock-forecasts/AAPL")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.inputSnapshotId").value(SNAPSHOT_ID.toString()));

        mockMvc.perform(get("/api/v1/stock-forecasts/AAPL")
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.modelVersion").value("deterministic-v1"))
                .andExpect(jsonPath("$.predictionId").isNotEmpty());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM stock_forecasts", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM analysis_predictions", Integer.class)).isEqualTo(1);
    }

    @Test
    void storesForecastWithoutLedgerWhenBaselineIsMissing() throws Exception {
        insertAnalysis(false);

        mockMvc.perform(post("/api/v1/stock-forecasts/AAPL")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("COMPLETED"))
                .andExpect(jsonPath("$.predictionId").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM analysis_predictions", Integer.class)).isEqualTo(0);
    }

    private String requestBody() {
        return """
                {"connectionId":"%s","modelVersion":"deterministic-v1","contractVersion":"forecast-v1"}
                """.formatted(CONNECTION_ID);
    }

    private void insertUser() {
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    private void insertConnection() {
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, CONNECTION_ID, USER_ID, new byte[17], new byte[12], offset(AS_OF), offset(AS_OF));
        jdbc.update("""
                INSERT INTO prediction_model_versions (
                    id, user_id, model_version, contract_version, status, created_at
                ) VALUES (?, ?, 'deterministic-v1', 'forecast-v1', 'ACTIVE', ?)
                """, UUID.randomUUID(), USER_ID, offset(AS_OF));
    }

    private void insertAnalysis(boolean includeBaseline) {
        jdbc.execute("TRUNCATE stock_analysis_results, stock_analysis_runs, analysis_input_snapshots CASCADE");
        var observations = includeBaseline
                ? "[{\"field\":\"quote.price\",\"value\":\"200\",\"unit\":\"USD\",\"period\":null,\"identifier\":\"AAPL\",\"provider\":\"FMP\",\"asOf\":\"2026-08-01T20:00:00Z\",\"collectedAt\":\"2026-08-02T00:00:00Z\",\"missingData\":[]}]"
                : "[]";
        var response = """
                {
                  "requestId":"88888888-8888-8888-8888-888888888888",
                  "schemaVersion":"1",
                  "inputSnapshotId":"%s",
                  "symbol":"AAPL",
                  "asOf":"2026-08-02T00:00:00Z",
                  "status":"COMPLETED",
                  "missingData":[],
                  "observations":%s,
                  "analyzers":[]
                }
                """.formatted(SNAPSHOT_ID, observations);
        jdbc.update("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash, collected_at, created_at
                ) VALUES (?, ?, 'AAPL', '1', CAST(? AS jsonb), ?, ?, ?)
                """, SNAPSHOT_ID, USER_ID, "{}", "0".repeat(64), offset(AS_OF), offset(AS_OF));
        jdbc.update("""
                INSERT INTO stock_analysis_runs (
                    id, user_id, input_snapshot_id, symbol, status, started_at, completed_at
                ) VALUES (?, ?, ?, 'AAPL', 'SUCCEEDED', ?, ?)
                """, RUN_ID, USER_ID, SNAPSHOT_ID, offset(AS_OF), offset(AS_OF));
        jdbc.update("""
                INSERT INTO stock_analysis_results (
                    id, stock_analysis_run_id, user_id, input_snapshot_id,
                    schema_version, result_status, response, created_at
                ) VALUES (?, ?, ?, ?, '1', 'COMPLETED', CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), RUN_ID, USER_ID, SNAPSHOT_ID, response, offset(AS_OF));
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration
    static class ForecastConfiguration {
        @Bean
        BrokerAdapter brokerAdapter() {
            return Mockito.mock(BrokerAdapter.class);
        }
    }
}
