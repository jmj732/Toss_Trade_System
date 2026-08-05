package com.jmj.trade.broker.connection;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.SellableQuantitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.toss.TossApiProperties;
import com.jmj.trade.broker.toss.TossCredentialMetadata;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import com.jmj.trade.broker.toss.TossCredentials;
import com.jmj.trade.security.AccessTokenService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@Import(BrokerConnectionSecurityIntegrationTest.AcceptanceBrokerAdapterConfiguration.class)
class BrokerConnectionSecurityIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CANARY_ID = "task9-canary-client-id";
    private static final String CANARY_SECRET = "task9-canary-client-secret";

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private MockMvc mockMvc;
    private WireMockServer server;
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate redis;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private BrokerConnectionService connectionService;

    @Autowired
    private BrokerConnectionValidationService validationService;

    @Autowired
    private BrokerConnectionRepository repository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private CredentialCipher credentialCipher;

    @Autowired
    private RecordingBrokerAdapter brokerAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccessTokenService accessTokens;

    private String authorization(UUID userId) {
        return "Bearer " + accessTokens.issue(
                userId, UUID.randomUUID(), Instant.now()).value();
    }

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        jdbcTemplate.execute("TRUNCATE broker_connections, users CASCADE");
        brokerAdapter.reset();
        startRedisTemplate();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @Test
    void crossUserReadEndpointIsAbsentAndMutationsReturnOwnerScopedNotFound() throws Exception {
        var connectionId = idFrom(mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("owner-client", "owner-secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/v1/broker-connections/{id}", connectionId)
                        .with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/v1/broker-connections/{id}/credentials", connectionId)
                        .header("Authorization", authorization(OTHER_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/broker-connections/{id}/verify", connectionId)
                        .header("Authorization", authorization(OTHER_USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/broker-connections/{id}", connectionId)
                        .header("Authorization", authorization(OTHER_USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));

        assertThat(row(UUID.fromString(connectionId)).userId()).isEqualTo(USER_ID);
        assertThat(brokerAdapter.connectionRefs()).isEmpty();
    }

    @Test
    void plaintextCanaryIsAbsentFromStoragePublicSurfacesAndRedisCredentialCache() throws Exception {
        var createdBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var connectionId = UUID.fromString(idFrom(createdBody));

        var duplicateBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(CANARY_ID, CANARY_SECRET)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        var validationBody = mockMvc.perform(post("/api/v1/broker-connections/toss")
                        .header("Authorization", authorization(UUID.randomUUID()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("", CANARY_SECRET)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();

        stubToken("public-token", 600);
        invokeGetAccessToken(tokenManager(new DatabaseTossCredentialProvider(repository, credentialCipher), properties()), connectionId);

        assertSecretFree(createdBody, duplicateBody, validationBody, dbCredentialText(), redisText());
        assertThat(new BrokerConnectionRequest(CANARY_ID, CANARY_SECRET).toString())
                .doesNotContain(CANARY_ID, CANARY_SECRET)
                .contains("****");
        assertThat(new TossCredentials(CANARY_ID, CANARY_SECRET).toString())
                .doesNotContain(CANARY_ID, CANARY_SECRET)
                .contains("****");
        assertThat(BrokerConnectionResponse.from(BrokerConnectionView.from(repository.findById(connectionId).orElseThrow())).toString())
                .doesNotContain(CANARY_ID, CANARY_SECRET, "ciphertext", "nonce");
        assertThat(new CredentialUnavailableException().toString())
                .doesNotContain(CANARY_ID, CANARY_SECRET);
    }

    @Test
    void missingKeyAndGcmTagFailureCauseZeroBrokerOauthHttpCalls() {
        var missingKeyId = insertConnectionEncryptedWith(cipher(2), USER_ID, 1, CANARY_ID, CANARY_SECRET);
        var corruptId = insertCorruptConnection(OTHER_USER_ID);
        var provider = new DatabaseTossCredentialProvider(repository, cipherWithOnlyKey(1));
        var tokenManager = tokenManager(provider, properties());

        assertThatThrownBy(() -> invokeGetAccessToken(tokenManager, missingKeyId))
                .isInstanceOf(CredentialUnavailableException.class);
        assertThatThrownBy(() -> invokeGetAccessToken(tokenManager, corruptId))
                .isInstanceOf(CredentialUnavailableException.class);

        server.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    void cacheHitPerformsCurrentLookupAndZeroDecryptOrOauthCalls() {
        var connectionId = UUID.randomUUID();
        redis.opsForValue().set(tokenKey(connectionId, 7), "cached-token", Duration.ofSeconds(60));
        var provider = new CountingProvider(7);

        var token = invokeGetAccessToken(tokenManager(provider, properties()), connectionId);

        assertThat(accessTokenValue(token)).isEqualTo("cached-token");
        assertThat(accessTokenRevision(token)).isEqualTo(7);
        assertThat(provider.currentCalls).hasValue(1);
        assertThat(provider.decryptCalls).hasValue(0);
        server.verify(0, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void crudRollbackAndOptimisticRacesLeaveExactlyOneValidAggregateState() throws Exception {
        assertThatThrownBy(() -> connectionService.createToss(USER_ID, "", CANARY_SECRET))
                .hasMessageNotContaining(CANARY_SECRET);
        assertThat(userRows()).isZero();

        var created = connectionService.createToss(USER_ID, "old-client", "old-secret");
        assertBrokerConnectionException(
                () -> connectionService.createToss(USER_ID, CANARY_ID, CANARY_SECRET),
                BrokerConnectionException.Code.ALREADY_EXISTS);
        assertThat(activeRows(USER_ID)).isOne();

        var stale = repository.findById(created.id()).orElseThrow();
        var replace = connectionService.replaceCredentials(USER_ID, created.id(), "new-client", "new-secret");
        stale.replaceCredentials(encryptedFor(stale, "stale-client", "stale-secret"), Instant.now());
        assertThatThrownBy(() -> repository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(row(created.id())).satisfies(row -> {
            assertThat(row.status()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
            assertThat(row.credentialRevision()).isEqualTo(replace.credentialRevision());
            assertThat(row.deletedAt()).isNull();
        });

        var raced = connectionService.createToss(UUID.randomUUID(), "race-client", "race-secret");
        var results = deterministicReplaceDeleteRace(raced.id());
        assertThat(results).containsExactlyInAnyOrder(AttemptResult.COMMITTED, AttemptResult.OPTIMISTIC_LOCK_CONFLICT);
        assertThat(validAggregateRows(raced.id())).isOne();
        assertThat(row(raced.id())).satisfies(row -> {
            assertThat(row.status()).isIn(BrokerConnectionStatus.UNVERIFIED, BrokerConnectionStatus.DELETED);
            assertThat(row.credentialRevision()).isEqualTo(2);
        });
    }

    @Test
    void validationRaceNeverMarksReplacedRevisionActive() {
        var created = connectionService.createToss(USER_ID, "old-client", "old-secret");
        brokerAdapter.respondWith(() -> {
            connectionService.replaceCredentials(USER_ID, created.id(), CANARY_ID, CANARY_SECRET);
            return success();
        });

        assertBrokerConnectionException(
                () -> validationService.validateToss(USER_ID, created.id()),
                BrokerConnectionException.Code.CONFLICT);

        assertThat(row(created.id())).satisfies(row -> {
            assertThat(row.status()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
            assertThat(row.credentialRevision()).isEqualTo(2);
            assertThat(row.lastValidatedAt()).isNull();
        });
    }

    @Test
    void orderMutationBrokerMethodsAndApiEndpointsAreAbsent() throws Exception {
        assertThat(Arrays.stream(BrokerAdapter.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("placeOrder", "modifyOrder", "cancelOrder");

        mockMvc.perform(post("/api/v1/orders")
                        .with(user(USER_ID.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private List<AttemptResult> deterministicReplaceDeleteRace(UUID connectionId) throws Exception {
        var loaded = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> replaceLoadedConnection(connectionId, loaded, start));
            var secondFuture = executor.submit(() -> deleteLoadedConnection(connectionId, loaded, start));

            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(result(firstFuture), result(secondFuture));
        }
    }

    private AttemptResult replaceLoadedConnection(
            UUID connectionId,
            CountDownLatch loaded,
            CountDownLatch start
    ) throws InterruptedException {
        return updateLoadedConnection(connectionId, loaded, start, connection ->
                connection.replaceCredentials(encryptedFor(connection, "client-a", "secret-a"), Instant.now()));
    }

    private AttemptResult deleteLoadedConnection(
            UUID connectionId,
            CountDownLatch loaded,
            CountDownLatch start
    ) throws InterruptedException {
        return updateLoadedConnection(connectionId, loaded, start, connection -> connection.delete(Instant.now()));
    }

    private AttemptResult updateLoadedConnection(
            UUID connectionId,
            CountDownLatch loaded,
            CountDownLatch start,
            ConnectionMutation mutation
    ) throws InterruptedException {
        var entityManager = entityManagerFactory.createEntityManager();
        var transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            var connection = entityManager.find(BrokerConnection.class, connectionId);
            loaded.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent broker connection mutation start timed out");
            }
            mutation.apply(connection);
            transaction.commit();
            return AttemptResult.COMMITTED;
        } catch (RuntimeException exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (hasOptimisticLockCause(exception)) {
                return AttemptResult.OPTIMISTIC_LOCK_CONFLICT;
            }
            throw exception;
        } finally {
            entityManager.close();
        }
    }

    private AttemptResult result(java.util.concurrent.Future<AttemptResult> future) throws Exception {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private boolean hasOptimisticLockCause(Throwable throwable) {
        for (var current = throwable; current != null; current = current.getCause()) {
            if (current instanceof OptimisticLockException
                    || current instanceof OptimisticLockingFailureException
                    || current instanceof ObjectOptimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }

    private UUID insertConnectionEncryptedWith(
            CredentialCipher cipher,
            UUID userId,
            int keyVersion,
            String clientId,
            String clientSecret
    ) {
        jdbcTemplate.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        var id = UUID.randomUUID();
        repository.saveAndFlush(BrokerConnection.create(
                id,
                userId,
                cipher.encrypt(id, userId, BrokerType.TOSS_INVEST, keyVersion, new TossCredentials(clientId, clientSecret)),
                Instant.now()));
        return id;
    }

    private UUID insertCorruptConnection(UUID userId) {
        jdbcTemplate.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        var id = UUID.randomUUID();
        var encrypted = credentialCipher.encrypt(
                id,
                userId,
                BrokerType.TOSS_INVEST,
                1,
                new TossCredentials(CANARY_ID, CANARY_SECRET));
        repository.saveAndFlush(BrokerConnection.create(
                id,
                userId,
                new EncryptedCredentials(tamper(encrypted.ciphertext()), encrypted.nonce(), encrypted.keyVersion()),
                Instant.now()));
        return id;
    }

    private EncryptedCredentials encryptedFor(BrokerConnection connection, String clientId, String clientSecret) {
        return credentialCipher.encrypt(
                connection.getId(),
                connection.getUserId(),
                connection.getBrokerType(),
                connection.getCredentialRevision() + 1,
                new TossCredentials(clientId, clientSecret));
    }

    private Object tokenManager(TossCredentialProvider provider, TossApiProperties properties) {
        try {
            var oauthClientClass = Class.forName("com.jmj.trade.broker.toss.TossOAuthClient");
            var oauthConstructor = oauthClientClass.getDeclaredConstructor(TossApiProperties.class);
            oauthConstructor.setAccessible(true);
            var oauthClient = oauthConstructor.newInstance(properties);

            var managerClass = Class.forName("com.jmj.trade.broker.toss.TossTokenManager");
            var constructor = managerClass.getDeclaredConstructor(
                    StringRedisTemplate.class,
                    TossCredentialProvider.class,
                    oauthClientClass,
                    TossApiProperties.class);
            constructor.setAccessible(true);
            return constructor.newInstance(redis, provider, oauthClient, properties);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private Object invokeGetAccessToken(Object tokenManager, UUID connectionId) {
        try {
            var method = tokenManager.getClass().getDeclaredMethod("getAccessToken", UUID.class);
            method.setAccessible(true);
            return method.invoke(tokenManager, connectionId);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private String accessTokenValue(Object token) {
        return invokeAccessor(token, "value", String.class);
    }

    private long accessTokenRevision(Object token) {
        return invokeAccessor(token, "credentialRevision", Long.class);
    }

    private <T> T invokeAccessor(Object target, String methodName, Class<T> type) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return type.cast(method.invoke(target));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private TossApiProperties properties() {
        return new TossApiProperties(
                java.net.URI.create(server.baseUrl()),
                Duration.ofMillis(200),
                Duration.ofMillis(500),
                Duration.ofMillis(500),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ZERO);
    }

    private static CredentialCipher cipher(int activeKeyVersion) {
        return new CredentialCipher(
                new CredentialKeyring(CredentialKeyringTest.properties(activeKeyVersion, Map.of(
                        1, CredentialKeyringTest.key(11, 32),
                        2, CredentialKeyringTest.key(22, 32)))),
                new SecureRandom());
    }

    private static CredentialCipher cipherWithOnlyKey(int activeKeyVersion) {
        return new CredentialCipher(
                new CredentialKeyring(CredentialKeyringTest.properties(activeKeyVersion, Map.of(
                        activeKeyVersion, CredentialKeyringTest.key(11, 32)))),
                new SecureRandom());
    }

    private static BrokerResponse<List<BrokerAccountView>> success() {
        return new BrokerResponse<>(List.of(), new BrokerCallMetadata("task9-request", Instant.now(), Optional.empty()));
    }

    private void stubToken(String token, long expiresInSeconds) {
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/oauth2/token")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"%s","token_type":"Bearer","expires_in":%d}
                                """.formatted(token, expiresInSeconds))));
    }

    private String dbCredentialText() {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(string_agg(
                    coalesce(encode(credential_ciphertext, 'escape'), '') || ' ' ||
                    coalesce(encode(credential_nonce, 'escape'), '') || ' ' ||
                    coalesce(credential_key_version::text, ''),
                    ' '
                ), '')
                FROM broker_connections
                """, String.class);
    }

    private String redisText() {
        var keys = redis.keys("*");
        if (keys == null || keys.isEmpty()) {
            return "";
        }
        return keys.stream()
                .sorted()
                .map(key -> key + "=" + redis.opsForValue().get(key))
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private ConnectionRow row(UUID id) {
        return jdbcTemplate.queryForObject("""
                SELECT user_id, status, credential_revision, last_validated_at, deleted_at
                FROM broker_connections
                WHERE id = ?
                """, (rs, rowNum) -> new ConnectionRow(
                rs.getObject("user_id", UUID.class),
                BrokerConnectionStatus.valueOf(rs.getString("status")),
                rs.getLong("credential_revision"),
                instant(rs.getObject("last_validated_at", OffsetDateTime.class)),
                instant(rs.getObject("deleted_at", OffsetDateTime.class))), id);
    }

    private int userRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
    }

    private int activeRows(UUID userId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM broker_connections
                WHERE user_id = ? AND broker_type = 'TOSS_INVEST' AND deleted_at IS NULL
                """, Integer.class, userId);
    }

    private int validAggregateRows(UUID id) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM broker_connections
                WHERE id = ?
                  AND credential_revision > 0
                  AND (
                      (status <> 'DELETED'
                       AND credential_ciphertext IS NOT NULL
                       AND credential_nonce IS NOT NULL
                       AND credential_key_version IS NOT NULL
                       AND deleted_at IS NULL)
                      OR
                      (status = 'DELETED'
                       AND credential_ciphertext IS NULL
                       AND credential_nonce IS NULL
                       AND credential_key_version IS NULL
                       AND deleted_at IS NOT NULL)
                  )
                """, Integer.class, id);
    }

    private void startRedisTemplate() {
        redisConnectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnectionFactory);
    }

    private static byte[] tamper(byte[] bytes) {
        var tampered = bytes.clone();
        tampered[0] ^= 1;
        return tampered;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String tokenKey(UUID brokerConnectionId, long credentialRevision) {
        return "broker:toss:oauth:v2:" + brokerConnectionId + ":" + credentialRevision;
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

    private static void assertSecretFree(String... values) {
        assertThat(String.join("\n", values)).doesNotContain(CANARY_ID, CANARY_SECRET);
    }

    private static void assertBrokerConnectionException(
            ThrowingSupplier<?> action,
            BrokerConnectionException.Code code
    ) {
        assertThatThrownBy(action::get)
                .isInstanceOfSatisfying(BrokerConnectionException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception).hasMessageNotContaining(CANARY_ID);
                    assertThat(exception).hasMessageNotContaining(CANARY_SECRET);
                });
    }

    @TestConfiguration
    static class AcceptanceBrokerAdapterConfiguration {

        @Bean
        RecordingBrokerAdapter brokerAdapter() {
            return new RecordingBrokerAdapter();
        }
    }

    static final class RecordingBrokerAdapter implements BrokerAdapter {
        private final java.util.ArrayList<BrokerConnectionRef> connectionRefs = new java.util.ArrayList<>();
        private java.util.function.Supplier<BrokerResponse<List<BrokerAccountView>>> response =
                BrokerConnectionSecurityIntegrationTest::success;

        void respondWith(java.util.function.Supplier<BrokerResponse<List<BrokerAccountView>>> response) {
            this.response = response;
        }

        List<BrokerConnectionRef> connectionRefs() {
            return List.copyOf(connectionRefs);
        }

        void reset() {
            connectionRefs.clear();
            response = BrokerConnectionSecurityIntegrationTest::success;
        }

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            connectionRefs.add(connection);
            return response.get();
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

        @Override
        public BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CountingProvider implements TossCredentialProvider {
        private final AtomicInteger currentCalls = new AtomicInteger();
        private final AtomicInteger decryptCalls = new AtomicInteger();
        private final long revision;

        private CountingProvider(long revision) {
            this.revision = revision;
        }

        @Override
        public TossCredentialMetadata current(UUID brokerConnectionId) {
            currentCalls.incrementAndGet();
            return new TossCredentialMetadata(revision);
        }

        @Override
        public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
            decryptCalls.incrementAndGet();
            return new TossCredentials(CANARY_ID, CANARY_SECRET);
        }
    }

    private record ConnectionRow(
            UUID userId,
            BrokerConnectionStatus status,
            long credentialRevision,
            Instant lastValidatedAt,
            Instant deletedAt
    ) {
    }

    private enum AttemptResult {
        COMMITTED,
        OPTIMISTIC_LOCK_CONFLICT
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ConnectionMutation {
        void apply(BrokerConnection connection);
    }
}
