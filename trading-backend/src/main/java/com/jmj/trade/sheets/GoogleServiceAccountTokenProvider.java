package com.jmj.trade.sheets;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Exchanges and briefly caches a service-account JWT; no credential is logged or returned in errors. */
public final class GoogleServiceAccountTokenProvider {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EXPIRY_SKEW = Duration.ofMinutes(1);

    private final GoogleServiceAccountCredentials credentials;
    private final RestClient restClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CachedToken cached;

    public GoogleServiceAccountTokenProvider(GoogleServiceAccountCredentials credentials) {
        this(credentials, credentials.tokenUri(), DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, Clock.systemUTC());
    }

    GoogleServiceAccountTokenProvider(
            GoogleServiceAccountCredentials credentials,
            URI tokenUri,
            Duration connectTimeout,
            Duration readTimeout,
            Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        var httpClient = HttpClient.newBuilder().connectTimeout(positive(connectTimeout, "connectTimeout")).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(positive(readTimeout, "readTimeout"));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.tokenUri = requireEndpoint(tokenUri, "tokenUri");
    }

    private final URI tokenUri;

    public synchronized String accessToken() {
        var now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt().minus(EXPIRY_SKEW))) {
            return cached.value();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", credentials.createAssertion(now, tokenUri));
        try {
            var body = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            var token = decode(body, now);
            cached = token;
            return token.value();
        } catch (RestClientResponseException exception) {
            throw GoogleSheetsException.http("Google OAuth token exchange failed", exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw GoogleSheetsException.network("Google OAuth token exchange failed");
        }
    }

    public synchronized void invalidate() {
        cached = null;
    }

    public synchronized void invalidateIfCurrent(String token) {
        if (cached != null && Objects.equals(cached.value(), token)) {
            cached = null;
        }
    }

    private CachedToken decode(String body, Instant now) {
        if (body == null || body.isBlank()) {
            throw GoogleSheetsException.contract("Google OAuth token response was empty");
        }
        try {
            var root = objectMapper.readTree(body);
            var value = root.path("access_token").asText();
            var expiresIn = root.path("expires_in").asLong(0);
            if (value.isBlank() || expiresIn <= 0) {
                throw GoogleSheetsException.contract("Google OAuth token response was invalid");
            }
            return new CachedToken(value, now.plusSeconds(expiresIn));
        } catch (GoogleSheetsException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw GoogleSheetsException.contract("Google OAuth token response was invalid");
        }
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static URI requireEndpoint(URI uri, String field) {
        Objects.requireNonNull(uri, field);
        var scheme = uri.getScheme();
        var host = uri.getHost();
        var local = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (host == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && local))) {
            throw new IllegalArgumentException(field + " must be HTTPS with a host");
        }
        return uri;
    }

    private record CachedToken(String value, Instant expiresAt) {
    }
}
