package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.BrokerAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossBrokerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TossBrokerConfiguration.class);

    @Test
    void doesNotCreateTossBeansWithoutCredentialProvider() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(TossCredentialProvider.class);
            assertThat(context).doesNotHaveBean(TossApiProperties.class);
            assertThat(context).doesNotHaveBean(BrokerAdapter.class);
        });
    }

    @Test
    void createsValidatedPropertiesOnlyWhenCredentialProviderExists() {
        contextRunner
                .withBean(TossCredentialProvider.class, () -> brokerConnectionId ->
                        new TossCredentials("client-id", "client-secret"))
                .run(context -> {
                    assertThat(context).hasSingleBean(TossCredentialProvider.class);
                    assertThat(context).hasSingleBean(TossApiProperties.class);
                    assertThat(context).doesNotHaveBean(BrokerAdapter.class);

                    var properties = context.getBean(TossApiProperties.class);
                    assertThat(properties.baseUrl()).isEqualTo(URI.create("https://openapi.tossinvest.com"));
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.tokenRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.tokenLockTtl()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(properties.tokenWaitTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(properties.tokenExpirySkew()).isEqualTo(Duration.ofSeconds(60));
                });
    }

    @Test
    void bindsCustomHttpProperties() {
        contextRunner
                .withBean(TossCredentialProvider.class, () -> brokerConnectionId ->
                        new TossCredentials("client-id", "client-secret"))
                .withPropertyValues(
                        "broker.toss.base-url=https://example.test",
                        "broker.toss.connect-timeout=3s",
                        "broker.toss.read-timeout=4s",
                        "broker.toss.token-request-timeout=6s",
                        "broker.toss.token-lock-ttl=11s",
                        "broker.toss.token-wait-timeout=8s",
                        "broker.toss.token-expiry-skew=30s")
                .run(context -> {
                    var properties = context.getBean(TossApiProperties.class);

                    assertThat(properties.baseUrl()).isEqualTo(URI.create("https://example.test"));
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(properties.tokenRequestTimeout()).isEqualTo(Duration.ofSeconds(6));
                    assertThat(properties.tokenLockTtl()).isEqualTo(Duration.ofSeconds(11));
                    assertThat(properties.tokenWaitTimeout()).isEqualTo(Duration.ofSeconds(8));
                    assertThat(properties.tokenExpirySkew()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void rejectsTokenWaitTimeoutThatDoesNotExceedTokenRequestTimeout() {
        contextRunner
                .withBean(TossCredentialProvider.class, () -> brokerConnectionId ->
                        new TossCredentials("client-id", "client-secret"))
                .withPropertyValues(
                        "broker.toss.token-request-timeout=5s",
                        "broker.toss.token-wait-timeout=5s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "tokenWaitTimeout must be greater than tokenRequestTimeout");
                });
    }

    @Test
    void rejectsTokenLockTtlThatDoesNotExceedTokenRequestTimeout() {
        contextRunner
                .withBean(TossCredentialProvider.class, () -> brokerConnectionId ->
                        new TossCredentials("client-id", "client-secret"))
                .withPropertyValues(
                        "broker.toss.token-request-timeout=5s",
                        "broker.toss.token-lock-ttl=5s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "tokenLockTtl must be greater than tokenRequestTimeout");
                });
    }

    @Test
    void rejectsInvalidBaseUrlScheme() {
        contextRunner
                .withBean(TossCredentialProvider.class, () -> brokerConnectionId ->
                        new TossCredentials("client-id", "client-secret"))
                .withPropertyValues(
                        "broker.toss.base-url=ftp://example.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "baseUrl must use http or https");
                });
    }

    @Test
    void rejectsNonPositiveTimeoutsAndNegativeExpirySkew() {
        contextRunner
                .withBean(TossCredentialProvider.class, () -> brokerConnectionId ->
                        new TossCredentials("client-id", "client-secret"))
                .withPropertyValues("broker.toss.connect-timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "connectTimeout must be positive");
                });

        contextRunner
                .withBean(TossCredentialProvider.class, () -> brokerConnectionId ->
                        new TossCredentials("client-id", "client-secret"))
                .withPropertyValues("broker.toss.token-expiry-skew=-1s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "tokenExpirySkew must not be negative");
                });
    }

    @Test
    void credentialsRequireSecretsAndRedactToString() {
        var credentials = new TossCredentials("client-id", "client-secret");

        assertThat(credentials.clientId()).isEqualTo("client-id");
        assertThat(credentials.clientSecret()).isEqualTo("client-secret");
        assertThatThrownBy(() -> new TossCredentials(" ", "client-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientId must not be blank");
        assertThatThrownBy(() -> new TossCredentials("client-id", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientSecret must not be blank");
        assertThat(credentials.toString())
                .doesNotContain("client-id")
                .doesNotContain("client-secret")
                .contains("****");
    }

    @Test
    void credentialProviderIsOnlyAPort() {
        assertThat(TossCredentialProvider.class).isInterface();
        assertThat(TossCredentialProvider.class.getDeclaredMethods())
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getName()).isEqualTo("get");
                    assertThat(method.getReturnType()).isEqualTo(TossCredentials.class);
                    assertThat(method.getParameterTypes()).containsExactly(UUID.class);
                });
    }
}
