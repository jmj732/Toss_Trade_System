package com.jmj.trade.connector;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorApiKeyAuthenticationFilterTest {

    @Test
    void connectorReadRoutesAndMcpMessagesAreApiKeyRequests() {
        var allowed = new MockHttpServletRequest("GET", "/api/v1/connector/portfolio");
        allowed.addHeader("Authorization", "Bearer ckey_test");
        var orders = new MockHttpServletRequest("GET", "/api/v1/connector/orders");
        orders.addHeader("Authorization", "Bearer ckey_test");
        var mcpSse = new MockHttpServletRequest("GET", "/api/v1/connector/mcp/sse");
        mcpSse.addHeader("Authorization", "Bearer ckey_test");
        var mcpMessage = new MockHttpServletRequest("POST", "/api/v1/connector/mcp/messages");
        mcpMessage.addHeader("Authorization", "Bearer ckey_test");
        var mcpStreamable = new MockHttpServletRequest("POST", "/api/v1/connector/mcp");
        mcpStreamable.addHeader("Authorization", "Bearer ckey_test");
        var post = new MockHttpServletRequest("POST", "/api/v1/connector/portfolio");
        post.addHeader("Authorization", "Bearer ckey_test");
        var noKey = new MockHttpServletRequest("GET", "/api/v1/connector/portfolio");

        assertThat(ConnectorApiKeyAuthenticationFilter.isConnectorRequest(allowed)).isTrue();
        assertThat(ConnectorApiKeyAuthenticationFilter.isConnectorRequest(orders)).isTrue();
        assertThat(ConnectorApiKeyAuthenticationFilter.isConnectorRequest(mcpSse)).isTrue();
        assertThat(ConnectorApiKeyAuthenticationFilter.isConnectorRequest(mcpMessage)).isTrue();
        assertThat(ConnectorApiKeyAuthenticationFilter.isConnectorRequest(mcpStreamable)).isTrue();
        assertThat(ConnectorApiKeyAuthenticationFilter.isConnectorRequest(post)).isFalse();
        assertThat(ConnectorApiKeyAuthenticationFilter.isConnectorRequest(noKey)).isFalse();
    }

    @Test
    void validKeySetsReadOnlyAuthorityAndConnectionBinding() throws Exception {
        var keys = mock(ConnectorApiKeyService.class);
        var connection = UUID.randomUUID();
        var key = new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), UUID.randomUUID(), connection, "ckey_test", null, false);
        when(keys.findActive("ckey_secret")).thenReturn(Optional.of(key));
        when(keys.markUsed(key.id())).thenReturn(true);
        var filter = new ConnectorApiKeyAuthenticationFilter(keys);
        var request = new MockHttpServletRequest("GET", "/api/v1/connector/portfolio");
        request.addHeader("Authorization", "Bearer ckey_secret");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactly("SCOPE_CONNECTOR_READ");
        assertThat(authentication.getDetails()).isEqualTo(key);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void validKeyAuthenticatesMcpMessagePost() throws Exception {
        var keys = mock(ConnectorApiKeyService.class);
        var key = new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ckey_test", null, false);
        when(keys.findActive("ckey_secret")).thenReturn(Optional.of(key));
        when(keys.markUsed(key.id())).thenReturn(true);
        var filter = new ConnectorApiKeyAuthenticationFilter(keys);
        var request = new MockHttpServletRequest("POST", "/api/v1/connector/mcp/messages");
        request.addHeader("Authorization", "Bearer ckey_secret");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void tradeScopedKeyAddsTradeAuthorityWhileRetainingReadAuthority() throws Exception {
        var keys = mock(ConnectorApiKeyService.class);
        var key = new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ckey_trade", null, false,
                ConnectorApiKeyService.TRADE_SCOPE);
        when(keys.findActive("ckey_trade_secret")).thenReturn(Optional.of(key));
        when(keys.markUsed(key.id())).thenReturn(true);
        var filter = new ConnectorApiKeyAuthenticationFilter(keys);
        var request = new MockHttpServletRequest("POST", "/api/v1/connector/mcp");
        request.addHeader("Authorization", "Bearer ckey_trade_secret");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("SCOPE_CONNECTOR_READ", "SCOPE_CONNECTOR_TRADE");
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void expiredKeyCannotReachConnector() throws Exception {
        var keys = mock(ConnectorApiKeyService.class);
        var key = new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ckey_test", null, true);
        when(keys.findActive("ckey_expired")).thenReturn(Optional.of(key));
        var filter = new ConnectorApiKeyAuthenticationFilter(keys);
        var request = new MockHttpServletRequest("GET", "/api/v1/connector/portfolio");
        request.addHeader("Authorization", "Bearer ckey_expired");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, org.mockito.Mockito.never()).doFilter(request, response);
    }
}
