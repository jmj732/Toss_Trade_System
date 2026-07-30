package com.jmj.trade.prediction;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PredictionIngestionApiKeyRateLimiter {

    private static final String KEY_PREFIX = "prediction:ingestion:rate:v1:";
    private static final DefaultRedisScript<String> ACQUIRE = new DefaultRedisScript<>("""
            local existed = redis.call('EXISTS', KEYS[1])
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local weight = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local count
            if current + weight <= limit then
              count = redis.call('INCRBY', KEYS[1], weight)
              if existed == 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
              end
            else
              count = limit + 1
              if existed == 0 then
                redis.call('SET', KEYS[1], '0', 'PX', ARGV[1])
              end
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return tostring(count) .. ':' .. tostring(ttl)
            """, String.class);

    private final StringRedisTemplate redis;
    private final int limit;
    private final Duration window;

    public PredictionIngestionApiKeyRateLimiter(
            StringRedisTemplate redis,
            int limit,
            Duration window
    ) {
        this.redis = Objects.requireNonNull(redis, "redis");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window == null || !window.isPositive()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.limit = limit;
        this.window = window;
    }

    Decision acquire(UUID keyId, int weight) {
        Objects.requireNonNull(keyId, "keyId");
        if (weight < 0) {
            throw new IllegalArgumentException("weight must not be negative");
        }
        try {
            var result = Objects.requireNonNull(
                    redis.execute(
                            ACQUIRE,
                            List.of(KEY_PREFIX + keyId),
                            Long.toString(window.toMillis()),
                            Integer.toString(weight),
                            Integer.toString(limit)),
                    "Redis rate limit script returned null");
            var separator = result.indexOf(':');
            var count = Long.parseLong(result.substring(0, separator));
            var ttlMillis = Math.max(1, Long.parseLong(result.substring(separator + 1)));
            var retryAfter = Duration.ofMillis(ttlMillis);
            return new Decision(
                    count <= limit,
                    retryAfter,
                    Instant.now().plus(retryAfter));
        } catch (DataAccessException | IllegalArgumentException | NullPointerException exception) {
            throw new RateLimitUnavailableException();
        }
    }

    record Decision(boolean allowed, Duration retryAfter, Instant retryAt) {
    }

    static final class RateLimitUnavailableException extends RuntimeException {
    }
}
