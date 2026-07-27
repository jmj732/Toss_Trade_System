package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.BrokerAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TossBrokerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TossBrokerConfiguration.class));

    @Test
    void doesNotCreateTossBeansWithoutCredentialProvider() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(TossCredentialProvider.class);
            assertThat(context).doesNotHaveBean(TossApiProperties.class);
            assertThat(context).doesNotHaveBean(BrokerAdapter.class);
        });
    }

    @Test
    void createsReadOnlyTossBeanGraphWhenCredentialProviderAndRedisExist() {
        contextRunner
                .withUserConfiguration(TestCredentialProviderConfig.class, TestRedisConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(TossCredentialProvider.class);
                    assertThat(context).hasSingleBean(TossApiProperties.class);
                    assertThat(context).hasSingleBean(TossOAuthClient.class);
                    assertThat(context).hasSingleBean(TossTokenManager.class);
                    assertThat(context).hasSingleBean(TossApiClient.class);
                    assertThat(context).hasSingleBean(TossResponseMapper.class);
                    assertThat(context).hasSingleBean(BrokerAdapter.class);
                    assertThat(context).getBean(BrokerAdapter.class).isInstanceOf(TossInvestBrokerAdapter.class);

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
                .withUserConfiguration(TestCredentialProviderConfig.class)
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
                .withUserConfiguration(TestCredentialProviderConfig.class)
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
                .withUserConfiguration(TestCredentialProviderConfig.class)
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
                .withUserConfiguration(TestCredentialProviderConfig.class)
                .withPropertyValues(
                        "broker.toss.base-url=ftp://example.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "baseUrl must use http or https");
                });
    }

    @Test
    void rejectsMalformedBaseUrlWithoutHost() {
        contextRunner
                .withUserConfiguration(TestCredentialProviderConfig.class)
                .withPropertyValues(
                        "broker.toss.base-url=http:/bad")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "baseUrl must include a host");
                });
    }

    @Test
    void rejectsBaseUrlWithUserInfoPathQueryOrFragment() {
        assertInvalidBaseUrl("https://user@example.test", "baseUrl must not include user info");
        assertInvalidBaseUrl("https://example.test/api", "baseUrl path must be empty or /");
        assertInvalidBaseUrl("https://example.test?x=1", "baseUrl must not include query");
        assertInvalidBaseUrl("https://example.test#docs", "baseUrl must not include fragment");
    }

    @Test
    void rejectsNonPositiveTimeoutsAndNegativeExpirySkew() {
        contextRunner
                .withUserConfiguration(TestCredentialProviderConfig.class)
                .withPropertyValues("broker.toss.connect-timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "connectTimeout must be positive");
                });

        contextRunner
                .withUserConfiguration(TestCredentialProviderConfig.class)
                .withPropertyValues("broker.toss.token-expiry-skew=-1s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "tokenExpirySkew must not be negative");
                });
    }

    private void assertInvalidBaseUrl(String baseUrl, String message) {
        contextRunner
                .withUserConfiguration(TestCredentialProviderConfig.class)
                .withPropertyValues("broker.toss.base-url=" + baseUrl)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(message);
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
        var methods = Arrays.stream(TossCredentialProvider.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        assertThat(methods).extracting("name")
                .containsExactlyInAnyOrder("current", "decrypt");
        assertThat(methods)
                .anySatisfy(method -> {
                    assertThat(method.getName()).isEqualTo("current");
                    assertThat(method.getReturnType()).isEqualTo(TossCredentialMetadata.class);
                    assertThat(method.getParameterTypes()).containsExactly(UUID.class);
                })
                .anySatisfy(method -> {
                    assertThat(method.getName()).isEqualTo("decrypt");
                    assertThat(method.getReturnType()).isEqualTo(TossCredentials.class);
                    assertThat(method.getParameterTypes()).containsExactly(UUID.class, long.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestCredentialProviderConfig {

        @Bean
        TossCredentialProvider tossCredentialProvider() {
            return new TossCredentialProvider() {
                @Override
                public TossCredentialMetadata current(UUID brokerConnectionId) {
                    return new TossCredentialMetadata(1);
                }

                @Override
                public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
                    return new TossCredentials("client-id", "client-secret");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestRedisConfig {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
