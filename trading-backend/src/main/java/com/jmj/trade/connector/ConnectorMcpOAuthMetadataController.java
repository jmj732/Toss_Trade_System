package com.jmj.trade.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/.well-known")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
final class ConnectorMcpOAuthMetadataController {

    private final ConnectorMcpOAuthService oauth;

    ConnectorMcpOAuthMetadataController(ConnectorMcpOAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping(value = {
            "/oauth-protected-resource",
            "/oauth-protected-resource/api/v1/connector/mcp",
            "/oauth-protected-resource/api/v1/connector/mcp/sse"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> protectedResource() {
        return protectedResource(ConnectorMcpOAuthService.RESOURCE_PATH,
                List.of(ConnectorMcpOAuthService.READ_SCOPE,
                        ConnectorMcpOAuthService.REVIEW_SCOPE, ConnectorMcpOAuthService.TRADE_SCOPE));
    }

    @GetMapping(value = "/oauth-protected-resource/api/v1/connector/mcp/review",
            produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> reviewProtectedResource() {
        return protectedResource(ConnectorMcpOAuthService.REVIEW_RESOURCE_PATH,
                List.of(ConnectorMcpOAuthService.REVIEW_SCOPE));
    }

    private Map<String, Object> protectedResource(String resourcePath, List<String> scopes) {
        return Map.of(
                "resource", oauth.publicUrl(resourcePath),
                "authorization_servers", List.of(oauth.publicUrl("/.well-known/oauth-authorization-server")),
                "scopes_supported", scopes);
    }

    @GetMapping(value = "/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> authorizationServer() {
        return Map.of(
                "issuer", oauth.publicUrl("/"),
                "authorization_endpoint", oauth.publicUrl(ConnectorMcpOAuthService.AUTHORIZE_PATH),
                "token_endpoint", oauth.publicUrl(ConnectorMcpOAuthService.TOKEN_PATH),
                "registration_endpoint", oauth.publicUrl(ConnectorMcpOAuthService.REGISTER_PATH),
                "response_types_supported", List.of("code"),
                "grant_types_supported", List.of("authorization_code", "refresh_token"),
                "code_challenge_methods_supported", List.of("S256"),
                "scopes_supported", List.of(ConnectorMcpOAuthService.READ_SCOPE,
                        ConnectorMcpOAuthService.REVIEW_SCOPE, ConnectorMcpOAuthService.TRADE_SCOPE),
                "token_endpoint_auth_methods_supported", List.of("none"));
    }
}
