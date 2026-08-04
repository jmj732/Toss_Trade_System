package com.jmj.trade.security;

import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.connection.BrokerConnectionController;
import com.jmj.trade.broker.connection.CredentialVaultConfiguration;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class,
                    SecurityConfiguration.class,
                    CredentialVaultConfiguration.class));

    @Test
    void defaultUserDetailsServiceFallbackIsNotCreated() {
        assertThat(TradingBackendApplication.class.getAnnotation(SpringBootApplication.class).exclude())
                .contains(UserDetailsServiceAutoConfiguration.class);
    }

    @Test
    void oidcSuccessRedirectsToConfiguredDashboard() throws Exception {
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/oidc");
        DashboardAuthorizationRequestResolver.rememberReturnTo(request, "state-1", "/portfolio?view=all");
        request.addParameter("state", "state-1");

        SecurityConfiguration.dashboardSuccessHandler("https://dashboard.example")
                .onAuthenticationSuccess(
                        request,
                        response,
                        new TestingAuthenticationToken("user", null));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://dashboard.example/portfolio?view=all");
        assertThat(DashboardAuthorizationRequestResolver.consumeReturnTo(request)).isNull();
    }

    @Test
    void oidcSuccessWithoutReturnPathKeepsConfiguredDashboardOrigin() throws Exception {
        var response = new MockHttpServletResponse();

        SecurityConfiguration.dashboardSuccessHandler("https://dashboard.example")
                .onAuthenticationSuccess(
                        new MockHttpServletRequest("GET", "/login/oauth2/code/oidc"),
                        response,
                        new TestingAuthenticationToken("user", null));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://dashboard.example");
    }

    @Test
    void oidcFailureRedirectsToConfiguredDashboard() throws Exception {
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/oidc");
        DashboardAuthorizationRequestResolver.rememberReturnTo(request, "state-1", "/settings");
        request.addParameter("state", "state-1");

        SecurityConfiguration.dashboardFailureHandler("https://dashboard.example")
                .onAuthenticationFailure(
                        request,
                        response,
                        new BadCredentialsException("OIDC failed"));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://dashboard.example/login?error=login&returnTo=%2Fsettings");
    }

    @Test
    void accessDeniedAndInvalidStateUseSafeDashboardLoginErrors() throws Exception {
        var accessDeniedResponse = new MockHttpServletResponse();
        var accessDeniedRequest = new MockHttpServletRequest("GET", "/login/oauth2/code/oidc");
        DashboardAuthorizationRequestResolver.rememberReturnTo(
                accessDeniedRequest, "state-1", "/settings");
        accessDeniedRequest.addParameter("state", "state-1");

        SecurityConfiguration.dashboardFailureHandler("https://dashboard.example")
                .onAuthenticationFailure(
                        accessDeniedRequest,
                        accessDeniedResponse,
                        new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(accessDeniedResponse.getRedirectedUrl())
                .isEqualTo("https://dashboard.example/login?error=access_denied&returnTo=%2Fsettings");

        var stateResponse = new MockHttpServletResponse();
        var stateRequest = new MockHttpServletRequest("GET", "/login/oauth2/code/oidc");
        stateRequest.addParameter("state", "unknown-state");
        SecurityConfiguration.dashboardFailureHandler("https://dashboard.example")
                .onAuthenticationFailure(
                        stateRequest,
                        stateResponse,
                        new OAuth2AuthenticationException(new OAuth2Error("invalid_state_parameter")));

        assertThat(stateResponse.getRedirectedUrl())
                .isEqualTo("https://dashboard.example/login?error=state");
    }

    @Test
    void dashboardRedirectsRejectOpenRedirectConfigurationAndReturnPaths() {
        assertThatThrownBy(() -> new DashboardRedirects("//evil.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DashboardRedirects("https://dashboard.example")
                .loginUrl("login", "//evil.example"))
                .isEqualTo("https://dashboard.example/login?error=login");
        assertThat(DashboardRedirects.safeReturnTo("/%2f%2fevil.example")).isEqualTo("/");
    }

    @Test
    void backendLoginRedirectsToConfiguredDashboardLogin() throws Exception {
        var response = new MockHttpServletResponse();
        new LoginRedirectController(new DashboardRedirects("https://dashboard.example"))
                .login("access_denied", "/portfolio", response);

        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://dashboard.example/login?error=access_denied&returnTo=%2Fportfolio");
    }

    @Test
    void vaultDisabledCreatesNoManagementControllerProviderOrTossAdapter() {
        contextRunner
                .withUserConfiguration(BrokerConnectionController.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BrokerConnectionController.class);
                    assertThat(context).doesNotHaveBean(TossCredentialProvider.class);
                    assertThat(context).doesNotHaveBean(BrokerAdapter.class);
                });
    }

    @Test
    void vaultEnabledWithMissingOrInvalidActiveKeyFailsClosedAtStartup() {
        contextRunner
                .withPropertyValues("broker.credentials.enabled=true", "broker.credentials.active-key-version=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("active credential key version is missing");
                });

        contextRunner
                .withPropertyValues(
                        "broker.credentials.enabled=true",
                        "broker.credentials.active-key-version=1",
                        "broker.credentials.keys.1=not-base64")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("credential key configuration is invalid");
                });
    }
}
