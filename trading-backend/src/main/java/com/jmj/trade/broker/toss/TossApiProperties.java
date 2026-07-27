package com.jmj.trade.broker.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("broker.toss")
public record TossApiProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration tokenRequestTimeout,
        Duration tokenLockTtl,
        Duration tokenWaitTimeout,
        Duration tokenExpirySkew) {

    public TossApiProperties {
        baseUrl = baseUrl == null ? URI.create("https://openapi.tossinvest.com") : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        tokenRequestTimeout = tokenRequestTimeout == null ? Duration.ofSeconds(5) : tokenRequestTimeout;
        tokenLockTtl = tokenLockTtl == null ? Duration.ofSeconds(10) : tokenLockTtl;
        tokenWaitTimeout = tokenWaitTimeout == null ? Duration.ofSeconds(7) : tokenWaitTimeout;
        tokenExpirySkew = tokenExpirySkew == null ? Duration.ofSeconds(60) : tokenExpirySkew;

        validateBaseUrl(baseUrl);
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requirePositive(tokenRequestTimeout, "tokenRequestTimeout");
        requirePositive(tokenLockTtl, "tokenLockTtl");
        requirePositive(tokenWaitTimeout, "tokenWaitTimeout");
        if (tokenExpirySkew.isNegative()) {
            throw new IllegalArgumentException("tokenExpirySkew must not be negative");
        }
        if (!tokenWaitTimeout.minus(tokenRequestTimeout).isPositive()) {
            throw new IllegalArgumentException("tokenWaitTimeout must be greater than tokenRequestTimeout");
        }
        if (!tokenLockTtl.minus(tokenRequestTimeout).isPositive()) {
            throw new IllegalArgumentException("tokenLockTtl must be greater than tokenRequestTimeout");
        }
    }

    private static void validateBaseUrl(URI uri) {
        Objects.requireNonNull(uri, "baseUrl must not be null");
        var scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("baseUrl must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must not include user info");
        }
        var path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("baseUrl path must be empty or /");
        }
        if (uri.getQuery() != null) {
            throw new IllegalArgumentException("baseUrl must not include query");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not include fragment");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
