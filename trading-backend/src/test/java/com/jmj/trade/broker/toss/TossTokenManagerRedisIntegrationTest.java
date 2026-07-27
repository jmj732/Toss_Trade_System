package com.jmj.trade.broker.toss;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossTokenManagerRedisIntegrationTest {

    private static final UUID CONNECTION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID OTHER_CONNECTION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000002");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private WireMockServer server;
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @BeforeEach
    void setUp() {
        startRedisTemplate(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        startServer();
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
    void concurrentMissIssuesOneTokenAndSharesItThroughRedis() throws Exception {
        stubToken("shared-token", 600, 250);
        var manager = manager(properties(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(10)));
        var threadCount = 6;
        var executor = Executors.newFixedThreadPool(threadCount);
        var ready = new CountDownLatch(threadCount);
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<String>>();
        for (var i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                ready.countDown();
                assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
                return manager.getAccessToken(CONNECTION_ID).value();
            });
        }

        var futures = tasks.stream().map(executor::submit).toList();
        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        for (var future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("shared-token");
        }
        executor.shutdownNow();
        server.verify(1, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void tokensAreIsolatedByBrokerConnectionId() {
        stubToken("token-one", 600, 0);
        var manager = manager(defaultProperties());

        assertThat(manager.getAccessToken(CONNECTION_ID).value()).isEqualTo("token-one");

        server.resetRequests();
        stubToken("token-two", 600, 0);
        assertThat(manager.getAccessToken(OTHER_CONNECTION_ID).value()).isEqualTo("token-two");
        assertThat(manager.getAccessToken(CONNECTION_ID).value()).isEqualTo("token-one");
        server.verify(1, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void lockLoserWaitsUntilWinnerCachesToken() throws Exception {
        stubToken("slow-token", 600, 300);
        var manager = manager(properties(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10)));
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        Callable<String> call = () -> {
            ready.countDown();
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            return manager.getAccessToken(CONNECTION_ID).value();
        };

        var first = executor.submit(call);
        var second = executor.submit(call);

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("slow-token");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("slow-token");
        executor.shutdownNow();
        server.verify(1, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void lockLoserTimeoutDoesNotIssueUnlockedToken() throws Exception {
        var lockKey = tokenKey(CONNECTION_ID, 1) + ":lock";
        redis.opsForValue().set(lockKey, "other-owner", Duration.ofSeconds(2));
        stubToken("must-not-be-issued", 600, 0);
        var manager = manager(properties(Duration.ofMillis(300), Duration.ofMillis(500), Duration.ofSeconds(2)));

        assertThatThrownBy(() -> manager.getAccessToken(CONNECTION_ID))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.TEMPORARY);
                    assertThat(exception.isRetriable()).isTrue();
                });
        server.verify(0, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void redisFailureFailsClosedWithoutOAuthCall() {
        var brokenFactory = redisConnectionFactory("127.0.0.1", 1);
        try {
            var manager = new TossTokenManager(
                    new StringRedisTemplate(brokenFactory),
                    provider(),
                    new TossOAuthClient(defaultProperties()),
                    defaultProperties());

            assertThatThrownBy(() -> manager.getAccessToken(CONNECTION_ID))
                    .isInstanceOfSatisfying(BrokerException.class, exception -> {
                        assertThat(exception.category()).isEqualTo(BrokerErrorCategory.TEMPORARY);
                        assertThat(exception.isRetriable()).isTrue();
                    });
            server.verify(0, postRequestedFor(urlEqualTo("/oauth2/token")));
        } finally {
            brokenFactory.destroy();
        }
    }

    @Test
    void cachesWithExpirySkewAndOneSecondFloor() {
        stubToken("short-token", 3, 0);
        var manager = manager(properties(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(10)));

        assertThat(manager.getAccessToken(CONNECTION_ID).value()).isEqualTo("short-token");

        var ttlSeconds = redis.getExpire(tokenKey(CONNECTION_ID, 1));
        assertThat(ttlSeconds).isBetween(0L, 1L);
    }

    @Test
    void invalidateIfCurrentOnlyDeletesMatchingTokenSnapshot() {
        redis.opsForValue().set(tokenKey(CONNECTION_ID, 1), "old-token", Duration.ofSeconds(60));
        redis.opsForValue().set(tokenKey(CONNECTION_ID, 2), "new-token", Duration.ofSeconds(60));
        var manager = manager(defaultProperties());

        manager.invalidateIfCurrent(CONNECTION_ID, 1, "wrong-token");

        assertThat(redis.opsForValue().get(tokenKey(CONNECTION_ID, 1))).isEqualTo("old-token");
        assertThat(redis.opsForValue().get(tokenKey(CONNECTION_ID, 2))).isEqualTo("new-token");

        manager.invalidateIfCurrent(CONNECTION_ID, 1, "old-token");

        assertThat(redis.opsForValue().get(tokenKey(CONNECTION_ID, 1))).isNull();
        assertThat(redis.opsForValue().get(tokenKey(CONNECTION_ID, 2))).isEqualTo("new-token");
    }

    @Test
    void staleLockOwnerReleaseDoesNotDeleteNewerLockOwner() throws Exception {
        stubToken("issued-token", 600, 0);
        var lockAcquired = new CountDownLatch(1);
        var unblockCredentials = new CountDownLatch(1);
        var manager = new TossTokenManager(
                redis,
                blockingProvider(lockAcquired, unblockCredentials),
                new TossOAuthClient(defaultProperties()),
                defaultProperties());
        var executor = Executors.newSingleThreadExecutor();

        try {
            var future = executor.submit(() -> manager.getAccessToken(CONNECTION_ID).value());
            assertThat(lockAcquired.await(2, TimeUnit.SECONDS)).isTrue();
            var lockKey = tokenKey(CONNECTION_ID, 1) + ":lock";
            assertThat(redis.opsForValue().get(lockKey)).isNotBlank();

            redis.opsForValue().set(lockKey, "new-owner", Duration.ofSeconds(10));
            unblockCredentials.countDown();

            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("issued-token");
            assertThat(redis.opsForValue().get(lockKey)).isEqualTo("new-owner");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oauthPrimaryFailureIsNotReplacedByLockReleaseFailure() {
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":"invalid_client","error_description":"bad client"}
                                """)));
        var manager = new TossTokenManager(
                cleanupFailingRedis(),
                provider(),
                new TossOAuthClient(defaultProperties()),
                defaultProperties());

        assertThatThrownBy(() -> manager.getAccessToken(CONNECTION_ID))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.AUTHENTICATION);
                    assertThat(exception.isRetriable()).isFalse();
                    assertThat(exception.getSuppressed())
                            .hasSize(1)
                            .allSatisfy(suppressed -> assertThat(suppressed)
                                    .isInstanceOfSatisfying(BrokerException.class, cleanup -> {
                                        assertThat(cleanup.category()).isEqualTo(BrokerErrorCategory.TEMPORARY);
                                        assertThat(cleanup.isRetriable()).isTrue();
                                    }));
                });
    }

    @Test
    void lockReleaseFailureAfterSuccessfulIssueStaysTemporary() {
        stubToken("issued-token", 600, 0);
        var manager = new TossTokenManager(
                cleanupFailingRedis(),
                provider(),
                new TossOAuthClient(defaultProperties()),
                defaultProperties());

        assertThatThrownBy(() -> manager.getAccessToken(CONNECTION_ID))
                .isInstanceOfSatisfying(BrokerException.class, exception -> {
                    assertThat(exception.category()).isEqualTo(BrokerErrorCategory.TEMPORARY);
                    assertThat(exception.isRetriable()).isTrue();
                    assertThat(exception.getSuppressed()).isEmpty();
                });
    }

    @Test
    void interruptedWaitRestoresInterruptFlag() throws Exception {
        var lockKey = tokenKey(CONNECTION_ID, 1) + ":lock";
        redis.opsForValue().set(lockKey, "other-owner", Duration.ofSeconds(2));
        var manager = manager(properties(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4)));
        var interrupted = new AtomicBoolean();
        var completed = new CountDownLatch(1);
        var thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> manager.getAccessToken(CONNECTION_ID))
                    .isInstanceOf(BrokerException.class);
            interrupted.set(Thread.currentThread().isInterrupted());
            completed.countDown();
        });

        thread.start();

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void cacheHitReadsCurrentButDoesNotDecryptOrIssueToken() {
        redis.opsForValue().set(tokenKey(CONNECTION_ID, 7), "cached-token", Duration.ofSeconds(60));
        var provider = new CountingProvider(7);
        var manager = new TossTokenManager(redis, provider, new TossOAuthClient(defaultProperties()), defaultProperties());

        var token = manager.getAccessToken(CONNECTION_ID);

        assertThat(token.value()).isEqualTo("cached-token");
        assertThat(token.credentialRevision()).isEqualTo(7);
        assertThat(provider.currentCalls).hasValue(1);
        assertThat(provider.decryptCalls).hasValue(0);
        server.verify(0, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void missLocksRevisionRechecksCacheThenDecryptsOnce() {
        stubToken("issued-token", 600, 0);
        var provider = new CountingProvider(3);
        var manager = new TossTokenManager(redis, provider, new TossOAuthClient(defaultProperties()), defaultProperties());

        var token = manager.getAccessToken(CONNECTION_ID);

        assertThat(token.value()).isEqualTo("issued-token");
        assertThat(token.credentialRevision()).isEqualTo(3);
        assertThat(redis.opsForValue().get(tokenKey(CONNECTION_ID, 3))).isEqualTo("issued-token");
        assertThat(provider.currentCalls).hasValue(2);
        assertThat(provider.decryptCalls).hasValue(1);
        server.verify(1, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void exactRevisionMismatchBeforeDecryptFailsWithoutOAuth() {
        var provider = new CountingProvider(4) {
            @Override
            public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
                super.decrypt(brokerConnectionId, expectedRevision);
                throw temporary("credential revision changed");
            }
        };
        var manager = new TossTokenManager(redis, provider, new TossOAuthClient(defaultProperties()), defaultProperties());

        assertThatThrownBy(() -> manager.getAccessToken(CONNECTION_ID))
                .isInstanceOf(BrokerException.class);
        assertThat(provider.decryptCalls).hasValue(1);
        assertThat(redis.opsForValue().get(tokenKey(CONNECTION_ID, 4))).isNull();
        server.verify(0, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void replacementBeforeOauthCompletionDiscardsTokenAndWritesNoCache() {
        stubToken("stale-token", 600, 0);
        var revision = new AtomicLong(5);
        var provider = new CountingProvider(revision);
        provider.afterDecrypt = () -> revision.set(6);
        var manager = new TossTokenManager(redis, provider, new TossOAuthClient(defaultProperties()), defaultProperties());

        assertThatThrownBy(() -> manager.getAccessToken(CONNECTION_ID))
                .isInstanceOf(BrokerException.class);

        assertThat(provider.currentCalls).hasValue(2);
        assertThat(provider.decryptCalls).hasValue(1);
        assertThat(redis.opsForValue().get(tokenKey(CONNECTION_ID, 5))).isNull();
        assertThat(redis.opsForValue().get(tokenKey(CONNECTION_ID, 6))).isNull();
        server.verify(1, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void tokenSnapshotToStringIsMasked() {
        assertThat(new TossAccessToken("secret-token", 9).toString())
                .doesNotContain("secret-token")
                .contains("****");
    }

    private TossTokenManager manager(TossApiProperties properties) {
        return new TossTokenManager(redis, provider(), new TossOAuthClient(properties), properties);
    }

    private TossCredentialProvider provider() {
        return new TossCredentialProvider() {
            @Override
            public TossCredentialMetadata current(UUID brokerConnectionId) {
                return new TossCredentialMetadata(1);
            }

            @Override
            public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
                return new TossCredentials("client-" + brokerConnectionId, "secret-" + brokerConnectionId);
            }
        };
    }

    private TossCredentialProvider blockingProvider(CountDownLatch lockAcquired, CountDownLatch unblockCredentials) {
        return new TossCredentialProvider() {
            @Override
            public TossCredentialMetadata current(UUID brokerConnectionId) {
                return new TossCredentialMetadata(1);
            }

            @Override
            public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
                lockAcquired.countDown();
                try {
                    assertThat(unblockCredentials.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return new TossCredentials("client-" + brokerConnectionId, "secret-" + brokerConnectionId);
            }
        };
    }

    private TossApiProperties defaultProperties() {
        return properties(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    private TossApiProperties properties(Duration tokenRequestTimeout, Duration tokenWaitTimeout, Duration tokenLockTtl) {
        return properties(tokenRequestTimeout, tokenWaitTimeout, tokenLockTtl, Duration.ZERO);
    }

    private TossApiProperties properties(
            Duration tokenRequestTimeout,
            Duration tokenWaitTimeout,
            Duration tokenLockTtl,
            Duration tokenExpirySkew) {
        return new TossApiProperties(
                java.net.URI.create(server.baseUrl()),
                Duration.ofMillis(200),
                Duration.ofSeconds(1),
                tokenRequestTimeout,
                tokenLockTtl,
                tokenWaitTimeout,
                tokenExpirySkew);
    }

    private void stubToken(String token, long expiresInSeconds, int fixedDelayMillis) {
        server.stubFor(post("/oauth2/token")
                .willReturn(aResponse()
                        .withFixedDelay(fixedDelayMillis)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"%s","token_type":"Bearer","expires_in":%d}
                                """.formatted(token, expiresInSeconds))));
    }

    private String tokenKey(UUID brokerConnectionId, long credentialRevision) {
        return "broker:toss:oauth:v2:" + brokerConnectionId + ":" + credentialRevision;
    }

    private static BrokerException temporary(String message) {
        return new BrokerException(BrokerErrorCategory.TEMPORARY, null, null, null, null, true, message);
    }

    private static class CountingProvider implements TossCredentialProvider {
        final AtomicInteger currentCalls = new AtomicInteger();
        final AtomicInteger decryptCalls = new AtomicInteger();
        final AtomicLong revision;
        Runnable afterDecrypt = () -> { };

        CountingProvider(long revision) {
            this(new AtomicLong(revision));
        }

        CountingProvider(AtomicLong revision) {
            this.revision = revision;
        }

        @Override
        public TossCredentialMetadata current(UUID brokerConnectionId) {
            currentCalls.incrementAndGet();
            return new TossCredentialMetadata(revision.get());
        }

        @Override
        public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
            decryptCalls.incrementAndGet();
            if (expectedRevision != revision.get()) {
                throw temporary("credential revision changed");
            }
            afterDecrypt.run();
            return new TossCredentials("client-" + brokerConnectionId, "secret-" + brokerConnectionId);
        }
    }

    private void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    private void startRedisTemplate(String host, int port) {
        redisConnectionFactory = redisConnectionFactory(host, port);
        redis = new StringRedisTemplate(redisConnectionFactory);
    }

    private StringRedisTemplate cleanupFailingRedis() {
        return new StringRedisTemplate(redisConnectionFactory) {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new DataAccessResourceFailureException("script failed");
            }
        };
    }

    private LettuceConnectionFactory redisConnectionFactory(String host, int port) {
        var factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        return factory;
    }
}
