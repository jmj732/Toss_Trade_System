package com.jmj.trade.broker.connection;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@Import(BrokerConnectionControllerIntegrationTest.TestBrokerAdapterConfiguration.class)
class BrokerConnectionControllerIntegrationTest extends PostgresIntegrationTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String CANARY_ID = "controller-canary-client-id";
    private static final String CANARY_SECRET = "controller-canary-client-secret";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingBrokerAdapter brokerAdapter;

    @BeforeEach
    void cleanConnections() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbcTemplate.execute("TRUNCATE broker_connections, users CASCADE");
        brokerAdapter.reset();
    }

    @Test
    void unauthenticatedAndMissingCsrfRequestsAreRejectedAtSecurityBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isForbidden());
    }

    @Test
    void basicAndFormLoginAreUnavailable() throws Exception {
        mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(httpBasic(USER_ID, "password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/login"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonUuidPrincipalGetsStableForbiddenCodeWithoutUsingRequestBodyAsUserSource() throws Exception {
        var body = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user("not-a-uuid"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHENTICATED_USER_INVALID"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(CANARY_ID, CANARY_SECRET);
        assertThat(userRows()).isZero();
    }

    @Test
    void createReplaceVerifyAndDeleteUsePrincipalUuidAndNeverReturnCredentialHints() throws Exception {
        var created = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerType").value("TOSS_INVEST"))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.credentialRevision").value(1))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain(CANARY_ID, CANARY_SECRET, "secret", "client");
        var connectionId = idFrom(created);

        var replaced = mockMvc.perform(put("/api/v1/broker-connections/{id}/credentials", connectionId)
                        .with(user(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("replacement-client", "replacement-secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialRevision").value(2))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(replaced).doesNotContain("replacement-client", "replacement-secret", "secret", "client");

        mockMvc.perform(post("/api/v1/broker-connections/{id}/verify", connectionId)
                        .with(user(USER_ID))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").doesNotExist());
        assertThat(brokerAdapter.connectionRefs()).containsExactly(new BrokerConnectionRef(UUID.fromString(connectionId)));

        mockMvc.perform(delete("/api/v1/broker-connections/{id}", connectionId)
                        .with(user(USER_ID))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(ownerFor(connectionId)).isEqualTo(UUID.fromString(USER_ID));
    }

    @Test
    void crossUserReplaceVerifyAndDeleteMatchMissing404() throws Exception {
        var connectionId = idFrom(mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("owner-client", "owner-secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(put("/api/v1/broker-connections/{id}/credentials", connectionId)
                        .with(user(OTHER_USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/broker-connections/{id}/verify", connectionId)
                        .with(user(OTHER_USER_ID))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/broker-connections/{id}", connectionId)
                        .with(user(OTHER_USER_ID))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
    }

    @Test
    void publicErrorsMapStableCodesWithoutSecrets() throws Exception {
        var duplicate = post("/api/v1/broker-connections/toss")
                .with(user(USER_ID))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(CANARY_ID, CANARY_SECRET));
        mockMvc.perform(duplicate).andExpect(status().isOk());
        assertSecretFree(mockMvc.perform(duplicate)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_ALREADY_EXISTS"))
                .andReturn().getResponse().getContentAsString());

        brokerAdapter.respondWithBrokerException(new BrokerException(
                BrokerErrorCategory.RATE_LIMITED,
                429,
                "rate-limited",
                "request-1",
                Duration.ofSeconds(1),
                true,
                "broker message " + CANARY_SECRET));
        var connectionId = jdbcTemplate.queryForObject("SELECT id FROM broker_connections LIMIT 1", UUID.class);
        assertSecretFree(mockMvc.perform(post("/api/v1/broker-connections/{id}/verify", connectionId)
                        .with(user(USER_ID))
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("BROKER_RATE_LIMITED"))
                .andReturn().getResponse().getContentAsString());

        var missingSecretBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user(UUID.randomUUID().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("", CANARY_SECRET)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertSecretFree(missingSecretBody);
    }

    @Test
    void requestValidationAndMalformedJsonReturnSecretFreeValidationFailure() throws Exception {
        var nullBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user(UUID.randomUUID().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":null,\"clientSecret\":\"" + CANARY_SECRET + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(nullBody).doesNotContain(CANARY_SECRET, "clientSecret");

        var oversizedSecret = "x".repeat(4097);
        var oversizedBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user(UUID.randomUUID().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, oversizedSecret)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(oversizedSecret.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(4096);
        assertThat(oversizedBody).doesNotContain(CANARY_ID, oversizedSecret, "clientSecret");

        var malformedBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .with(user(UUID.randomUUID().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"" + CANARY_ID + "\",\"clientSecret\":"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(malformedBody).doesNotContain(CANARY_ID, "clientSecret");
    }

    @Test
    void unexpectedIllegalStateIsNotMappedAsCredentialUnavailable() {
        assertThat(exceptionHandlerTypes()).doesNotContain(IllegalStateException.class);
    }

    private void assertSecretFree(String body) {
        assertThat(body).doesNotContain(CANARY_ID, CANARY_SECRET, "broker message");
    }

    private int userRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
    }

    private UUID ownerFor(String connectionId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM broker_connections WHERE id = ?",
                UUID.class,
                UUID.fromString(connectionId));
    }

    private static String credentialsJson(String clientId, String clientSecret) {
        return """
                {"clientId":"%s","clientSecret":"%s"}
                """.formatted(clientId, clientSecret);
    }

    private static String idFrom(String json) {
        var marker = "\"id\":\"";
        var start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("response id missing: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }

    @TestConfiguration
    static class TestBrokerAdapterConfiguration {

        @Bean
        RecordingBrokerAdapter brokerAdapter() {
            return new RecordingBrokerAdapter();
        }
    }

    static final class RecordingBrokerAdapter implements BrokerAdapter {
        private final java.util.ArrayList<BrokerConnectionRef> connectionRefs = new java.util.ArrayList<>();
        private BrokerException brokerException;

        List<BrokerConnectionRef> connectionRefs() {
            return List.copyOf(connectionRefs);
        }

        void respondWithBrokerException(BrokerException brokerException) {
            this.brokerException = brokerException;
        }

        void reset() {
            connectionRefs.clear();
            brokerException = null;
        }

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            connectionRefs.add(connection);
            if (brokerException != null) {
                throw brokerException;
            }
            return new BrokerResponse<>(List.of(), new BrokerCallMetadata("controller-request", Instant.now(), Optional.empty()));
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(BrokerAccountRef account, Currency currency) {
            throw new UnsupportedOperationException();
        }
    }

    private static List<Class<? extends Throwable>> exceptionHandlerTypes() {
        return java.util.Arrays.stream(BrokerConnectionErrorHandler.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(annotation -> java.util.Arrays.stream(annotation.value()))
                .toList();
    }
}
