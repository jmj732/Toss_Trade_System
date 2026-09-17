package com.jmj.trade.sheets;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Parsed service-account credentials. The private key is intentionally not exposed in text form. */
public final class GoogleServiceAccountCredentials {

    static final URI DEFAULT_TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    static final String SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String clientEmail;
    private final PrivateKey privateKey;
    private final URI tokenUri;

    private GoogleServiceAccountCredentials(String clientEmail, PrivateKey privateKey, URI tokenUri) {
        this.clientEmail = requireText(clientEmail, "client_email");
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.tokenUri = requireEndpoint(tokenUri, "tokenUri");
    }

    public static GoogleServiceAccountCredentials fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Google service-account JSON is required");
        }
        try {
            var root = JSON.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("service-account JSON must be an object");
            }
            var email = text(root, "client_email");
            var pem = text(root, "private_key");
            var tokenUri = root.path("token_uri").isMissingNode()
                    || root.path("token_uri").isNull()
                    || root.path("token_uri").asText().isBlank()
                    ? DEFAULT_TOKEN_URI
                    : URI.create(root.path("token_uri").asText());
            return new GoogleServiceAccountCredentials(email, parsePrivateKey(pem), tokenUri);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Google service-account JSON", exception);
        }
    }

    public String clientEmail() {
        return clientEmail;
    }

    URI tokenUri() {
        return tokenUri;
    }

    String createAssertion(Instant now) {
        return createAssertion(now, tokenUri);
    }

    String createAssertion(Instant now, URI audience) {
        Objects.requireNonNull(now, "now");
        audience = requireEndpoint(audience, "audience");
        var issuedAt = now.getEpochSecond();
        var header = encode(Map.of("alg", "RS256", "typ", "JWT"));
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", clientEmail);
        claims.put("scope", SHEETS_SCOPE);
        claims.put("aud", audience.toString());
        claims.put("iat", issuedAt);
        claims.put("exp", issuedAt + 3600);
        var unsigned = header + "." + encode(claims);
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign Google service-account assertion", exception);
        }
    }

    private static String encode(Object value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to encode Google service-account assertion", exception);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        var value = requireText(pem, "private_key")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            var bytes = Base64.getDecoder().decode(value);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Google service-account private key", exception);
        }
    }

    private static String text(tools.jackson.databind.JsonNode root, String field) {
        var value = root.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("Missing Google service-account field: " + field);
        }
        return requireText(value.asText(), field);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
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
}
