package com.jmj.trade.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/connector/oauth")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
final class ConnectorMcpOAuthController {

    private final ConnectorMcpOAuthService oauth;

    ConnectorMcpOAuthController(ConnectorMcpOAuthService oauth) {
        this.oauth = oauth;
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> register(@RequestBody ObjectNode request) {
        try {
            var redirectUris = request == null || !request.path("redirect_uris").isArray()
                    ? List.<String>of()
                    : request.path("redirect_uris").valueStream().map(node -> node.asText(null)).toList();
            var client = oauth.register(redirectUris);
            return ResponseEntity.ok(Map.of(
                    "client_id", client.clientId(),
                    "redirect_uris", client.redirectUris(),
                    "token_endpoint_auth_method", "none"));
        } catch (ConnectorMcpOAuthService.OAuthException exception) {
            return oauthError(exception);
        }
    }

    @GetMapping(value = "/authorize", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<?> authorize(
            @RequestParam MultiValueMap<String, String> parameters,
            Authentication authentication
    ) {
        try {
            return redirect(oauth.beginAuthorization(parameters, authentication));
        } catch (ConnectorMcpOAuthService.OAuthException exception) {
            if (exception.redirectUri() != null) {
                var builder = UriComponentsBuilder.fromUriString(exception.redirectUri())
                        .queryParam("error", exception.code());
                if (exception.state() != null && !exception.state().isBlank()) {
                    builder.queryParam("state", exception.state());
                }
                return redirect(builder.build().encode().toUriString());
            }
            return oauthError(exception);
        }
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> token(@RequestParam MultiValueMap<String, String> parameters) {
        try {
            if (!"authorization_code".equals(parameters.getFirst("grant_type"))) {
                throw new ConnectorMcpOAuthService.OAuthException(
                        "unsupported_grant_type", "grant_type=authorization_code is required", null, null);
            }
            var token = oauth.exchangeCode(
                    parameters.getFirst("client_id"),
                    parameters.getFirst("code"),
                    parameters.getFirst("redirect_uri"),
                    parameters.getFirst("code_verifier"));
            return ResponseEntity.ok(Map.of(
                    "access_token", token.accessToken(),
                    "token_type", token.tokenType(),
                    "expires_in", token.expiresIn(),
                    "scope", token.scope()));
        } catch (ConnectorMcpOAuthService.OAuthException exception) {
            return oauthError(exception);
        }
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private static ResponseEntity<Map<String, String>> oauthError(
            ConnectorMcpOAuthService.OAuthException exception
    ) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", exception.code(),
                "error_description", exception.description()));
    }
}
