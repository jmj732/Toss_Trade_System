package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class TossTokenManager {

    private static final String KEY_PREFIX = "broker:toss:oauth:v2:";
    private static final DefaultRedisScript<Long> DELETE_IF_VALUE_MATCHES = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final StringRedisTemplate redis;
    private final TossCredentialProvider credentialProvider;
    private final TossOAuthClient oauthClient;
    private final TossApiProperties properties;

    TossTokenManager(
            StringRedisTemplate redis,
            TossCredentialProvider credentialProvider,
            TossOAuthClient oauthClient,
            TossApiProperties properties) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider");
        this.oauthClient = Objects.requireNonNull(oauthClient, "oauthClient");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    TossAccessToken getAccessToken(UUID brokerConnectionId) {
        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId");
        var metadata = credentialProvider.current(brokerConnectionId);
        var credentialRevision = metadata.credentialRevision();
        var tokenKey = tokenKey(brokerConnectionId, credentialRevision);
        var cached = redisGet(tokenKey);
        if (cached != null) {
            return new TossAccessToken(cached, credentialRevision);
        }

        var lockKey = tokenKey + ":lock";
        var lockOwner = UUID.randomUUID().toString();
        if (tryAcquire(lockKey, lockOwner)) {
            RuntimeException primary = null;
            try {
                cached = redisGet(tokenKey);
                if (cached != null) {
                    return new TossAccessToken(cached, credentialRevision);
                }
                var credentials = credentialProvider.decrypt(brokerConnectionId, credentialRevision);
                var token = oauthClient.issueToken(brokerConnectionId, credentials);
                var latest = credentialProvider.current(brokerConnectionId);
                if (latest.credentialRevision() != credentialRevision) {
                    throw temporary("Toss credential revision changed during OAuth token issue");
                }
                redisSet(tokenKey, token.accessToken(), cacheTtl(token.expiresIn()));
                return new TossAccessToken(token.accessToken(), credentialRevision);
            } catch (RuntimeException exception) {
                primary = exception;
                throw exception;
            } finally {
                releaseLock(lockKey, lockOwner, primary);
            }
        }

        return waitForCachedToken(tokenKey, credentialRevision);
    }

    void invalidateIfCurrent(UUID brokerConnectionId, long credentialRevision, String accessToken) {
        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId");
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        deleteIfValueMatches(tokenKey(brokerConnectionId, credentialRevision), accessToken);
    }

    private boolean tryAcquire(String lockKey, String owner) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, owner, properties.tokenLockTtl()));
        } catch (DataAccessException exception) {
            throw redisFailure("Toss OAuth token lock could not be acquired");
        }
    }

    private TossAccessToken waitForCachedToken(String tokenKey, long credentialRevision) {
        var deadline = System.nanoTime() + properties.tokenWaitTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            var cached = redisGet(tokenKey);
            if (cached != null) {
                return new TossAccessToken(cached, credentialRevision);
            }
            sleepUntilNextPoll(deadline);
        }
        var cached = redisGet(tokenKey);
        if (cached != null) {
            return new TossAccessToken(cached, credentialRevision);
        }
        throw temporary("Toss OAuth token lock wait timed out");
    }

    private void sleepUntilNextPoll(long deadline) {
        var remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(POLL_INTERVAL.toMillis(), Duration.ofNanos(remaining).toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw temporary("Toss OAuth token lock wait was interrupted");
        }
    }

    private String redisGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException exception) {
            throw redisFailure("Toss OAuth token cache could not be read");
        }
    }

    private void redisSet(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (DataAccessException exception) {
            throw redisFailure("Toss OAuth token cache could not be written");
        }
    }

    private void deleteIfValueMatches(String key, String value) {
        try {
            redis.execute(DELETE_IF_VALUE_MATCHES, List.of(key), value);
        } catch (DataAccessException exception) {
            throw redisFailure("Toss OAuth token cache could not be invalidated");
        }
    }

    private void releaseLock(String lockKey, String owner, RuntimeException primary) {
        try {
            deleteIfValueMatches(lockKey, owner);
        } catch (RuntimeException cleanup) {
            if (primary == null) {
                throw cleanup;
            }
            primary.addSuppressed(cleanup);
        }
    }

    private Duration cacheTtl(Duration expiresIn) {
        var ttl = expiresIn.minus(properties.tokenExpirySkew());
        return ttl.isPositive() ? ttl : Duration.ofSeconds(1);
    }

    private String tokenKey(UUID brokerConnectionId, long credentialRevision) {
        return KEY_PREFIX + brokerConnectionId + ":" + credentialRevision;
    }

    private BrokerException redisFailure(String message) {
        return new BrokerException(BrokerErrorCategory.TEMPORARY, null, null, null, null, true, message);
    }

    private BrokerException temporary(String message) {
        return new BrokerException(BrokerErrorCategory.TEMPORARY, null, null, null, null, true, message);
    }
}

record TossAccessToken(String value, long credentialRevision) {

    TossAccessToken {
        if (Objects.requireNonNull(value, "value").isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    @Override
    public String toString() {
        return "TossAccessToken[****]";
    }
}
