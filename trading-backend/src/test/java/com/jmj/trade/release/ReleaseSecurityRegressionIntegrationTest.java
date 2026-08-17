package com.jmj.trade.release;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.security.AccessTokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security regression sweep over the real pipeline: default Spring Security response headers
 * stay present, no credential value leaks into any response body collected across a full
 * connect-sync-analyze-event-order run, and every entity created by one user is unreachable
 * (owner-scoped 404) to another user with zero side effects. Reuses the exact same
 * {@code @SpringBootTest} signature as {@link ReleaseWorkflowE2EIntegrationTest} so Spring's test
 * context cache serves both classes from one pooled connection set instead of adding another.
 */
@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "analysis.service.connect-timeout=PT1S",
                "analysis.service.read-timeout=PT1S",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
@Import(ReleaseWorkflowE2EIntegrationTest.PipelineBrokerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReleaseSecurityRegressionIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CANARY_CLIENT_ID = "release-canary-client-id";
    private static final String CANARY_CLIENT_SECRET = "release-canary-client-secret";
    private static final WireMockServer ANALYSIS =
            new WireMockServer(options().dynamicPort().globalTemplating(true));

    static {
        ANALYSIS.start();
    }

    @DynamicPropertySource
    static void analysisProperties(DynamicPropertyRegistry registry) {
        registry.add("analysis.service.base-url", ANALYSIS::baseUrl);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReleaseWorkflowE2EIntegrationTest.PipelineBrokerAdapter broker;

    @Autowired
    private AccessTokenService accessTokens;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbc.execute("""
                TRUNCATE paper_order_workflow_commands,
                         pre_trade_risk_decisions,
                         order_intent_audit_logs,
                         broker_orders,
                         real_order_daily_reservations, real_order_account_allowlist, order_intents,
                         broker_accounts,
                         event_review_commands,
                         event_reviews,
                         intelligence_events,
                         analysis_results,
                         analysis_runs,
                         account_capacity_snapshots,
                         position_snapshots,
                         account_snapshots,
                         account_sync_runs,
                         broker_connections,
                         users
                CASCADE
                """);
        broker.reset();
        ANALYSIS.resetAll();
        stubAnalysisSuccess();
    }

    @AfterAll
    static void stopWireMock() {
        ANALYSIS.stop();
    }

    @Test
    void authenticatedResponseCarriesDefaultSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/session").header(
                        "Authorization", bearer(USER_ID).authorization()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                .andExpect(header().string("Cache-Control",
                        "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void unauthenticatedResponseStillCarriesDefaultSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void noResponseBodyAcrossTheFullPipelineLeaksTheCredentialSecret() throws Exception {
        var csrf = bootstrapSession(USER_ID);
        var bodies = new ArrayList<String>();

        var createdBody = mockMvc.perform(csrf.post("/api/v1/broker-connections/toss")
                        .content(credentialsJson(CANARY_CLIENT_ID, CANARY_CLIENT_SECRET)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        bodies.add(createdBody);
        var connectionId = UUID.fromString(field(createdBody, "id"));

        bodies.add(mockMvc.perform(csrf.post("/api/v1/broker-connections/toss")
                        .content(credentialsJson(CANARY_CLIENT_ID, CANARY_CLIENT_SECRET)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(csrf.post("/api/v1/broker-connections/{id}/verify", connectionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(csrf.post(
                        "/api/v1/broker-connections/{id}/portfolio-syncs", connectionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(csrf.post(
                        "/api/v1/broker-connections/{id}/portfolio-analyses", connectionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(csrf.put("/api/v1/broker-connections/{id}/credentials", connectionId)
                        .content(credentialsJson("rotated-client", CANARY_CLIENT_SECRET)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(dbCredentialText()).doesNotContain(CANARY_CLIENT_ID, CANARY_CLIENT_SECRET);
        for (var body : bodies) {
            assertThat(body).doesNotContain(CANARY_CLIENT_ID, CANARY_CLIENT_SECRET);
        }
    }

    @Test
    void crossUserCannotReadOrMutateAnyEntityCreatedInAnotherUsersPipeline() throws Exception {
        var owner = bootstrapSession(USER_ID);

        var connectionId = UUID.fromString(field(mockMvc.perform(owner.post(
                        "/api/v1/broker-connections/toss")
                        .content(credentialsJson("owner-client", "owner-secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "id"));
        mockMvc.perform(owner.post("/api/v1/broker-connections/{id}/verify", connectionId))
                .andExpect(status().isOk());
        mockMvc.perform(owner.post("/api/v1/broker-connections/{id}/portfolio-syncs", connectionId))
                .andExpect(status().isOk());
        mockMvc.perform(owner.post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId))
                .andExpect(status().isOk());
        var eventId = UUID.fromString(field(mockMvc.perform(owner.post(
                        "/api/v1/broker-connections/{id}/events", connectionId)
                        .content("""
                                {"source":"NEWS","sourceEventId":"evt-owner","type":"EARNINGS",
                                 "summary":"owner event","affectedSymbols":["AAPL"],
                                 "occurredAt":"2026-07-28T00:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "id"));
        var orderId = UUID.fromString(field(mockMvc.perform(owner.post("/api/v1/paper-orders")
                        .header("Idempotency-Key", "owner-propose")
                        .content(proposalJson(connectionId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "id"));

        var intruder = bootstrapSession(OTHER_USER_ID);

        mockMvc.perform(get("/api/v1/broker-connections/{id}/portfolio", connectionId)
                        .header("Authorization", intruder.authorization()))
                .andExpect(status().isNotFound());
        mockMvc.perform(intruder.post("/api/v1/broker-connections/{id}/portfolio-syncs", connectionId))
                .andExpect(status().isNotFound());
        mockMvc.perform(intruder.post("/api/v1/broker-connections/{id}/portfolio-analyses", connectionId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/events/{eventId}",
                        connectionId, eventId).header(
                                "Authorization", intruder.authorization()))
                .andExpect(status().isNotFound());
        mockMvc.perform(intruder.post(
                        "/api/v1/broker-connections/{connectionId}/events/{eventId}/review",
                        connectionId, eventId)
                        .header("Idempotency-Key", "intruder-review")
                        .content("{\"status\":\"CONFIRMED\",\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/paper-orders/{id}", orderId).header(
                        "Authorization", intruder.authorization()))
                .andExpect(status().isNotFound());
        mockMvc.perform(intruder.post("/api/v1/paper-orders/{id}/approve", orderId)
                        .header("Idempotency-Key", "intruder-approve")
                        .header("X-Step-Up-Token", "intruder-token")
                        .content("""
                                {
                                  "channel":"WEB",
                                  "displayedQuantity":1,
                                  "displayedMaxLoss":100,
                                  "displayedCurrency":"USD",
                                  "proposalVersion":null
                                }
                                """))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM order_intents WHERE id = ?", String.class, orderId))
                .isEqualTo("PROPOSED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM broker_connections WHERE id = ?", String.class, connectionId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM event_reviews WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    private Bearer bootstrapSession(UUID userId) throws Exception {
        var access = accessTokens.issue(userId, UUID.randomUUID(), java.time.Instant.now());
        mockMvc.perform(get("/api/v1/session").header(
                        "Authorization", "Bearer " + access.value()))
                .andExpect(status().isOk())
                .andReturn();
        return new Bearer(userId, access.value());
    }

    private Bearer bearer(UUID userId) {
        var access = accessTokens.issue(userId, UUID.randomUUID(), java.time.Instant.now());
        return new Bearer(userId, access.value());
    }

    private void stubAnalysisSuccess() {
        ANALYSIS.stubFor(post("/internal/v1/portfolio-analyses")
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "requestId":"{{jsonPath request.body '$.requestId'}}",
                                  "schemaVersion":"1",
                                  "asOf":"{{jsonPath request.body '$.asOf'}}",
                                  "status":"COMPLETED",
                                  "quality":{"stale":false,"partial":false,"unknownFields":[]},
                                  "positions":[{
                                    "symbol":"AAPL",
                                    "currency":"USD",
                                    "marketValue":120,
                                    "profitLoss":20,
                                    "weight":0.012
                                  }],
                                  "currencyTotals":[{
                                    "currency":"USD",
                                    "marketValue":10000,
                                    "profitLoss":20,
                                    "concentration":0.012
                                  }]
                                }
                                """)));
    }

    private String dbCredentialText() {
        return jdbc.queryForObject("""
                SELECT coalesce(string_agg(
                    coalesce(encode(credential_ciphertext, 'escape'), '') || ' ' ||
                    coalesce(encode(credential_nonce, 'escape'), '') || ' ' ||
                    coalesce(credential_key_version::text, ''),
                    ' '
                ), '')
                FROM broker_connections
                """, String.class);
    }

    private static String proposalJson(UUID connectionId) {
        return """
                {
                  "connectionId":"%s",
                  "side":"BUY",
                  "type":"MARKET",
                  "symbol":"AAPL",
                  "quantity":1,
                  "limitPrice":null,
                  "currency":"USD",
                  "channel":"WEB"
                }
                """.formatted(connectionId);
    }

    private static String credentialsJson(String clientId, String clientSecret) {
        return """
                {"clientId":"%s","clientSecret":"%s"}
                """.formatted(clientId, clientSecret);
    }

    private static String field(String json, String name) {
        var marker = "\"" + name + "\":\"";
        var start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError(name + " missing: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    /** Threads one short-lived bearer token across requests, as a genuine browser client would. */
    private final class Bearer {
        private final UUID userId;
        private final String token;

        private Bearer(UUID userId, String token) {
            this.userId = userId;
            this.token = token;
        }

        private String authorization() {
            return "Bearer " + token;
        }

        MockHttpServletRequestBuilder post(String urlTemplate, Object... vars) {
            return MockMvcRequestBuilders.post(urlTemplate, vars)
                    .header("Authorization", authorization())
                    .contentType(MediaType.APPLICATION_JSON);
        }

        MockHttpServletRequestBuilder put(String urlTemplate, Object... vars) {
            return MockMvcRequestBuilders.put(urlTemplate, vars)
                    .header("Authorization", authorization())
                    .contentType(MediaType.APPLICATION_JSON);
        }
    }
}
