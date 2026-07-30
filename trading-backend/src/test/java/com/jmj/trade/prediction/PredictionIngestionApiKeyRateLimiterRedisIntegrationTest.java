package com.jmj.trade.prediction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PredictionIngestionApiKeyRateLimiterRedisIntegrationTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void multipleInstancesAtomicallyShareOneKeyLimit() throws Exception {
        var first = new PredictionIngestionApiKeyRateLimiter(
                redis, 5, Duration.ofMinutes(1));
        var second = new PredictionIngestionApiKeyRateLimiter(
                redis, 5, Duration.ofMinutes(1));
        var keyId = UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(10);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (var i = 0; i < 20; i++) {
            var limiter = i % 2 == 0 ? first : second;
            tasks.add(() -> limiter.acquire(keyId).allowed());
        }

        var results = executor.invokeAll(tasks).stream()
                .map(future -> {
                    try {
                        return future.get(2, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .toList();
        executor.shutdownNow();

        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(5);
        assertThat(first.acquire(keyId).retryAfter()).isPositive();
    }

    @Test
    void redisFailureFailsClosed() {
        var brokenFactory = new LettuceConnectionFactory("127.0.0.1", 1);
        brokenFactory.afterPropertiesSet();
        try {
            var limiter = new PredictionIngestionApiKeyRateLimiter(
                    new StringRedisTemplate(brokenFactory), 1, Duration.ofMinutes(1));

            assertThatThrownBy(() -> limiter.acquire(UUID.randomUUID()))
                    .isInstanceOf(PredictionIngestionApiKeyRateLimiter
                            .RateLimitUnavailableException.class);
        } finally {
            brokenFactory.destroy();
        }
    }
}
