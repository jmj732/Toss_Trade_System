package com.jmj.trade.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** A signed, short-lived OAuth transaction cookie; no authentication state is stored in a session. */
@Component
final class CookieAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE = "trade_oidc_request";
    private static final String ATTRIBUTE = CookieAuthorizationRequestRepository.class.getName();
    private final AccessTokenService signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    CookieAuthorizationRequestRepository(AccessTokenService signer, ObjectMapper objectMapper) {
        this(signer, objectMapper, Clock.systemUTC());
    }

    CookieAuthorizationRequestRepository(
            AccessTokenService signer,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        var value = cookie(request);
        if (value == null) {
            return null;
        }
        try {
            var parts = value.split("\\.", -1);
            if (parts.length != 2 || !signer.validCookieSignature(parts[0], parts[1])) {
                return null;
            }
            var json = new String(java.util.Base64.getUrlDecoder().decode(parts[0]),
                    java.nio.charset.StandardCharsets.UTF_8);
            var node = objectMapper.readTree(json);
            var issuedAt = Instant.ofEpochSecond(number(node, "issuedAt"));
            var expiresAt = Instant.ofEpochSecond(number(node, "expiresAt"));
            if (issuedAt.isAfter(clock.instant().plusSeconds(60))
                    || !expiresAt.isAfter(clock.instant())
                    || !expiresAt.isAfter(issuedAt)) {
                return null;
            }
            var authorization = OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(text(node, "authorizationUri"))
                    .clientId(text(node, "clientId"))
                    .redirectUri(text(node, "redirectUri"))
                    .scopes(strings(node.path("scopes"), true))
                    .state(text(node, "state"))
                    .additionalParameters(strings(node.path("additionalParameters")))
                    .attributes(strings(node.path("attributes")))
                    .authorizationRequestUri(text(node, "authorizationRequestUri"))
                    .build();
            request.setAttribute(ATTRIBUTE, authorization);
            return authorization;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (authorizationRequest == null) {
            clear(response);
            return;
        }
        try {
            var data = new LinkedHashMap<String, Object>();
            var issuedAt = clock.instant();
            data.put("authorizationUri", authorizationRequest.getAuthorizationUri());
            data.put("clientId", authorizationRequest.getClientId());
            data.put("redirectUri", authorizationRequest.getRedirectUri());
            data.put("scopes", authorizationRequest.getScopes());
            data.put("state", authorizationRequest.getState());
            data.put("additionalParameters", stringMap(authorizationRequest.getAdditionalParameters()));
            data.put("attributes", stringMap(authorizationRequest.getAttributes()));
            data.put("authorizationRequestUri", authorizationRequest.getAuthorizationRequestUri());
            data.put("issuedAt", issuedAt.getEpochSecond());
            data.put("expiresAt", issuedAt.plus(Duration.ofMinutes(5)).getEpochSecond());
            var encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsString(data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            set(response, encoded + "." + signer.cookieSignature(encoded), Duration.ofMinutes(5));
        } catch (JacksonException exception) {
            throw new IllegalStateException("could not store OIDC authorization request", exception);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        var value = loadAuthorizationRequest(request);
        clear(response);
        return value;
    }

    static OAuth2AuthorizationRequest request(HttpServletRequest request) {
        var value = request.getAttribute(ATTRIBUTE);
        return value instanceof OAuth2AuthorizationRequest authorization ? authorization : null;
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void set(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(maxAge).build().toString());
    }

    private void clear(HttpServletResponse response) {
        set(response, "", Duration.ZERO);
    }

    private static String text(JsonNode node, String name) {
        var value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static long number(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("missing authorization request timestamp");
        }
        return Long.parseLong(value.asText());
    }

    private static Map<String, Object> strings(JsonNode node) {
        var values = new LinkedHashMap<String, Object>();
        if (node != null && node.isObject()) {
            for (var entry : node.properties()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return values;
    }

    private static LinkedHashSet<String> strings(JsonNode node, boolean array) {
        var values = new LinkedHashSet<String>();
        if (node != null && node.isArray()) {
            for (var item : node) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static Object stringMap(Map<String, Object> source) {
        var result = new LinkedHashMap<String, String>();
        source.forEach((key, value) -> {
            if (value instanceof String string) {
                result.put(key, string);
            }
        });
        return result;
    }
}
