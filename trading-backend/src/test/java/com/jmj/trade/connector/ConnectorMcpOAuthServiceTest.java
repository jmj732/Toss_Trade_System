package com.jmj.trade.connector;

import com.jmj.trade.broker.connection.BrokerConnectionService;
import com.jmj.trade.broker.connection.BrokerConnectionStatus;
import com.jmj.trade.broker.connection.BrokerConnectionView;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorMcpOAuthServiceTest {

    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final String REDIRECT = "https://chatgpt.com/oauth/callback";
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    private final BrokerConnectionService connections = mock(BrokerConnectionService.class);
    private final ConnectorApiKeyService keys = mock(ConnectorApiKeyService.class);
    private final ConnectorMcpOAuthService service = new ConnectorMcpOAuthService(
            connections,
            keys,
            new java.security.SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "https://dashboard.example",
            "oidc");

    @Test
    void authorizationRedirectPreservesPkceRequestForOidcLogin() {
        var client = service.register(List.of(REDIRECT));
        var params = authorizationParams(client.clientId(), REDIRECT);

        var location = service.beginAuthorization(params, null);

        assertThat(location).startsWith("/oauth2/authorization/oidc?returnTo=");
        assertThat(location).contains("authorize/complete");
        assertThat(location).contains("code_challenge");
    }

    @Test
    void authorizationCodeExchangeIssuesAReadOnlyConnectorBearer() {
        var client = service.register(List.of(REDIRECT));
        var params = authorizationParams(client.clientId(), REDIRECT);
        var loginLocation = service.beginAuthorization(params, null);
        var returnTo = org.springframework.web.util.UriComponentsBuilder
                .fromUriString(loginLocation)
                .build()
                .getQueryParams()
                .getFirst("returnTo");
        returnTo = java.net.URLDecoder.decode(returnTo, java.nio.charset.StandardCharsets.UTF_8);
        when(connections.list(USER)).thenReturn(List.of(new BrokerConnectionView(
                CONNECTION, USER, com.jmj.trade.broker.connection.BrokerType.TOSS_INVEST,
                BrokerConnectionStatus.ACTIVE, 1, NOW)));
        when(keys.issue(org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(CONNECTION),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(new ConnectorApiKeyService.IssuedKey(
                        UUID.randomUUID(), "ckey_oauth", CONNECTION, "ckey_oauth",
                        ConnectorApiKeyService.Status.ACTIVE, NOW, NOW.plusSeconds(3600)));

        var callback = service.completeAfterLogin(returnTo,
                new TestingAuthenticationToken(USER.toString(), null, "ROLE_USER"));
        var code = org.springframework.web.util.UriComponentsBuilder.fromUriString(callback)
                .build().getQueryParams().getFirst("code");

        var token = service.exchangeCode(client.clientId(), code, REDIRECT, "verifier");

        assertThat(token.accessToken()).isEqualTo("ckey_oauth");
        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.scope()).isEqualTo("connector:read");
        verify(keys).issue(org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(CONNECTION),
                org.mockito.ArgumentMatchers.any(Instant.class));
    }

    private static LinkedMultiValueMap<String, String> authorizationParams(
            String clientId,
            String redirectUri
    ) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("response_type", "code");
        params.add("client_id", clientId);
        params.add("redirect_uri", redirectUri);
        params.add("state", "state-1");
        params.add("code_challenge", codeChallenge("verifier"));
        params.add("code_challenge_method", "S256");
        params.add("scope", "connector:read");
        return params;
    }

    private static String codeChallenge(String verifier) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
