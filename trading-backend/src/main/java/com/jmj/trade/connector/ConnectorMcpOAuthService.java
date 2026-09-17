package com.jmj.trade.connector;

import com.jmj.trade.broker.connection.BrokerConnectionService;
import com.jmj.trade.broker.connection.BrokerConnectionStatus;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectorMcpOAuthService {

    public static final String READ_SCOPE = "connector:read";
    public static final String TRADE_SCOPE = "connector:trade";
    public static final String RESOURCE_PATH = "/api/v1/connector/mcp";
    public static final String AUTHORIZE_PATH = "/api/v1/connector/oauth/authorize";
    public static final String TOKEN_PATH = "/api/v1/connector/oauth/token";
    public static final String REGISTER_PATH = "/api/v1/connector/oauth/register";
    public static final String CONTINUE_PATH = "/api/v1/connector/oauth/authorize/complete";
    static final Duration AUTHORIZATION_CODE_TTL = Duration.ofMinutes(5);
    static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);

    private final BrokerConnectionService connections;
    private final ConnectorApiKeyService keys;
    private final SecureRandom random;
    private final Clock clock;
    private final String publicBaseUrl;
    private final String oidcRegistrationId;
    private final ConnectorOAuthClientStore clients;
    private final Map<String, AuthorizationCode> codes = new ConcurrentHashMap<>();

    ConnectorMcpOAuthService(
            BrokerConnectionService connections,
            ConnectorApiKeyService keys,
            SecureRandom random,
            Clock clock,
            String publicBaseUrl,
            String oidcRegistrationId,
            ConnectorOAuthClientStore clients
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
        if (oidcRegistrationId == null || !oidcRegistrationId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("OIDC registration id is invalid");
        }
        this.oidcRegistrationId = oidcRegistrationId;
    }

    public String publicUrl(String path) {
        return publicBaseUrl + path;
    }

    RegisteredClient register(List<String> redirectUris) {
        var normalized = validateRedirectUris(redirectUris);
        var client = new RegisteredClient(opaque("mcp_client_"), normalized);
        clients.save(client);
        return client;
    }

    String beginAuthorization(MultiValueMap<String, String> parameters, Authentication authentication) {
        var request = parseAuthorization(parameters);
        var userId = authenticatedUser(authentication);
        if (userId != null) {
            return issueAuthorizationCode(request, userId);
        }
        return UriComponentsBuilder.fromPath("/oauth2/authorization/" + oidcRegistrationId)
                .queryParam("returnTo", continuation(request))
                .build()
                .encode()
                .toUriString();
    }

    public String completeAfterLogin(String returnTo, Authentication authentication) {
        var request = parseContinuation(returnTo);
        var userId = authenticatedUser(authentication);
        if (userId == null) {
            throw oauthError("access_denied", "authenticated user required", request);
        }
        return issueAuthorizationCode(request, userId);
    }

    TokenResponse exchangeCode(String clientId, String code, String redirectUri, String verifier) {
        var client = client(clientId);
        if (code == null || code.isBlank() || redirectUri == null || verifier == null) {
            throw oauthError("invalid_request", "authorization code, redirect_uri and code_verifier are required", null);
        }
        if (!client.redirectUris().contains(redirectUri)) {
            throw oauthError("invalid_grant", "redirect_uri does not match the client", null);
        }

        AuthorizationCode authorization;
        synchronized (codes) {
            authorization = codes.get(code);
            if (authorization == null || !authorization.clientId().equals(clientId)
                    || !authorization.redirectUri().equals(redirectUri)
                    || !authorization.expiresAt().isAfter(clock.instant())
                    || !validPkce(verifier, authorization.codeChallenge())) {
                throw oauthError("invalid_grant", "authorization code is invalid or expired", null);
            }
            codes.remove(code);
        }

        var scope = normalizedScope(authorization.scope());
        var issued = TRADE_SCOPE.equals(scope)
                ? keys.issue(authorization.userId(), authorization.connectionId(),
                        clock.instant().plus(ACCESS_TOKEN_TTL), scope)
                : keys.issue(authorization.userId(), authorization.connectionId(),
                        clock.instant().plus(ACCESS_TOKEN_TTL));
        return new TokenResponse(issued.apiKey(), "Bearer", ACCESS_TOKEN_TTL.toSeconds(), scope);
    }

    public boolean isContinuation(String returnTo) {
        return returnTo != null && returnTo.startsWith(CONTINUE_PATH + "?");
    }

    public String loginFailureRedirect(String returnTo, String error) {
        try {
            var request = parseContinuation(returnTo);
            return errorRedirect(request.redirectUri(), error, request.state());
        } catch (OAuthException exception) {
            return publicUrl("/");
        }
    }

    String errorRedirect(String returnTo, String error) {
        var request = parseContinuation(returnTo);
        return errorRedirect(request.redirectUri(), error, request.state());
    }

    private String issueAuthorizationCode(AuthorizationRequest request, UUID userId) {
        var active = connections.list(userId).stream()
                .filter(connection -> connection.status() == BrokerConnectionStatus.ACTIVE)
                .toList();
        if (active.size() != 1) {
            throw oauthError("access_denied", "exactly one active broker connection is required", request);
        }
        var code = opaque("mcp_code_");
        codes.put(code, new AuthorizationCode(
                code,
                request.clientId(),
                request.redirectUri(),
                userId,
                active.getFirst().id(),
                request.codeChallenge(),
                normalizedScope(request.scope()),
                clock.instant().plus(AUTHORIZATION_CODE_TTL)));
        trimExpiredCodes();
        return errorOrCodeRedirect(request.redirectUri(), code, request.state());
    }

    private AuthorizationRequest parseAuthorization(MultiValueMap<String, String> parameters) {
        var clientId = required(parameters, "client_id");
        var redirectUri = required(parameters, "redirect_uri");
        var client = client(clientId);
        if (!client.redirectUris().contains(redirectUri)) {
            throw oauthError("invalid_request", "redirect_uri does not match the client", null);
        }
        var request = new AuthorizationRequest(
                clientId,
                redirectUri,
                first(parameters, "state"),
                required(parameters, "code_challenge"),
                first(parameters, "code_challenge_method"),
                first(parameters, "scope"));
        validateAuthorization(request, parameters);
        return request;
    }

    private AuthorizationRequest parseContinuation(String returnTo) {
        if (!isContinuation(returnTo)) {
            throw oauthError("invalid_request", "OAuth authorization continuation is invalid", null);
        }
        var uri = UriComponentsBuilder.fromUriString(returnTo).build();
        return parseAuthorization(uri.getQueryParams());
    }

    private static void validateAuthorization(
            AuthorizationRequest request,
            MultiValueMap<String, String> parameters
    ) {
        if (!"code".equals(first(parameters, "response_type"))) {
            throw oauthError("unsupported_response_type", "response_type=code is required", request);
        }
        if (!"S256".equals(request.codeChallengeMethod())) {
            throw oauthError("invalid_request", "code_challenge_method=S256 is required", request);
        }
        if (request.scope() != null && !request.scope().isBlank() && !supportedScope(request.scope())) {
            throw oauthError("invalid_scope", "only connector:read or connector:trade is supported", request);
        }
    }

    private RegisteredClient client(String clientId) {
        var client = clients.find(clientId);
        if (client == null) {
            throw oauthError("invalid_client", "client_id is not registered", null);
        }
        return client;
    }

    private static UUID authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getClass().getSimpleName().contains("Anonymous")) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void trimExpiredCodes() {
        var now = clock.instant();
        codes.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (codes.size() > 1000) {
            codes.keySet().stream().limit(codes.size() - 1000).forEach(codes::remove);
        }
    }

    private static boolean validPkce(String verifier, String challenge) {
        var digest = sha256(verifier);
        return MessageDigest.isEqual(
                digest.getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII));
    }

    private static String required(MultiValueMap<String, String> parameters, String name) {
        var value = first(parameters, name);
        if (value == null || value.isBlank()) {
            throw oauthError("invalid_request", name + " is required", null);
        }
        return value;
    }

    private static String first(MultiValueMap<String, String> parameters, String name) {
        return parameters == null ? null : parameters.getFirst(name);
    }

    private static List<String> validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > 10) {
            throw oauthError("invalid_client_metadata", "one to ten redirect_uris are required", null);
        }
        return redirectUris.stream().map(ConnectorMcpOAuthService::validateRedirectUri).distinct().toList();
    }

    private static String validateRedirectUri(String value) {
        try {
            var uri = URI.create(value);
            var loopback = "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if ((uri.getHost() == null || (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback))
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (RuntimeException exception) {
            throw oauthError("invalid_client_metadata", "redirect_uri must be an HTTPS URI", null);
        }
    }

    private String continuation(AuthorizationRequest request) {
        return UriComponentsBuilder.fromPath(CONTINUE_PATH)
                .queryParam("response_type", "code")
                .queryParam("client_id", request.clientId())
                .queryParam("redirect_uri", request.redirectUri())
                .queryParam("state", request.state())
                .queryParam("code_challenge", request.codeChallenge())
                .queryParam("code_challenge_method", request.codeChallengeMethod())
                .queryParam("scope", request.scope())
                .build()
                .encode()
                .toUriString();
    }

    private static String normalizedScope(String scope) {
        if (scope == null || scope.isBlank()) return READ_SCOPE;
        return java.util.Arrays.stream(scope.trim().split("\\s+"))
                .anyMatch(TRADE_SCOPE::equals) ? TRADE_SCOPE : READ_SCOPE;
    }

    private static boolean supportedScope(String scope) {
        for (var token : scope.trim().split("\\s+")) {
            if (!READ_SCOPE.equals(token) && !TRADE_SCOPE.equals(token)) return false;
        }
        return true;
    }

    private static String errorOrCodeRedirect(String redirectUri, String code, String state) {
        var builder = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("code", code);
        if (state != null && !state.isBlank()) builder.queryParam("state", state);
        return builder.build().encode().toUriString();
    }

    private static String errorRedirect(String redirectUri, String error, String state) {
        var builder = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", error == null || error.isBlank() ? "access_denied" : error);
        if (state != null && !state.isBlank()) builder.queryParam("state", state);
        return builder.build().encode().toUriString();
    }

    private String opaque(String prefix) {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String normalizeBaseUrl(String value) {
        try {
            var uri = URI.create(Objects.requireNonNull(value, "publicBaseUrl").trim());
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null) {
                var base = uri.toString();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                return base;
            }
        } catch (RuntimeException ignored) {
            // Normalize into one constructor failure below.
        }
        throw new IllegalArgumentException("public base URL must be an HTTP(S) origin");
    }

    private static OAuthException oauthError(String code, String description, AuthorizationRequest request) {
        return new OAuthException(code, description, request == null ? null : request.redirectUri(),
                request == null ? null : request.state());
    }

    record RegisteredClient(String clientId, List<String> redirectUris) {
        RegisteredClient {
            redirectUris = List.copyOf(redirectUris);
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, String scope) {
    }

    private record AuthorizationRequest(
            String clientId,
            String redirectUri,
            String state,
            String codeChallenge,
            String codeChallengeMethod,
            String scope
    ) {
    }

    private record AuthorizationCode(
            String value,
            String clientId,
            String redirectUri,
            UUID userId,
            UUID connectionId,
            String codeChallenge,
            String scope,
            Instant expiresAt
    ) {
    }

    public static final class OAuthException extends RuntimeException {
        private final String code;
        private final String description;
        private final String redirectUri;
        private final String state;

        OAuthException(String code, String description, String redirectUri, String state) {
            super(description);
            this.code = code;
            this.description = description;
            this.redirectUri = redirectUri;
            this.state = state;
        }

        public String code() { return code; }

        public String description() { return description; }

        public String redirectUri() { return redirectUri; }

        public String state() { return state; }
    }
}
