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

import static org.assertj.core.api.Assertions.assertThat;

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

        SecurityConfiguration.dashboardSuccessHandler("https://dashboard.example")
                .onAuthenticationSuccess(
                        new MockHttpServletRequest("GET", "/login/oauth2/code/oidc"),
                        response,
                        new TestingAuthenticationToken("user", null));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://dashboard.example");
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
