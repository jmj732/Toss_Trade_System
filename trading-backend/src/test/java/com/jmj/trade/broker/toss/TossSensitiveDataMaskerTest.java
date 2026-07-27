package com.jmj.trade.broker.toss;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class TossSensitiveDataMaskerTest {

    private final TossSensitiveDataMasker masker = new TossSensitiveDataMasker();

    @Test
    void masksAuthorizationSecretsTokensAndAccountNumbers() {
        var secret = "super-secret-client-value";
        var token = "eyJhbGciOiJIUzI1NiJ9.token";
        var account = "12345678901234";

        assertThat(masker.maskHeader("Authorization", "Bearer " + token))
                .isEqualTo("Bearer ***")
                .doesNotContain(token);
        assertThat(masker.maskCredential(secret))
                .isEqualTo("***")
                .doesNotContain(secret);
        assertThat(masker.maskToken(token))
                .isEqualTo("***")
                .doesNotContain(token);
        assertThat(masker.maskAccountNumber(account))
                .isEqualTo("**********1234")
                .doesNotContain("1234567890");
    }

    @Test
    void queryLoggingKeepsOnlyAllowedParameters() {
        var safe = masker.maskUri(URI.create("https://openapi.tossinvest.com/api/v1/prices?symbols=AAPL,NVDA&currency=USD&client_secret=secret&accountNo=12345678901234"));

        assertThat(safe).isEqualTo("/api/v1/prices?symbols=AAPL,NVDA&currency=USD");
        assertThat(safe)
                .doesNotContain("secret")
                .doesNotContain("12345678901234")
                .doesNotContain("client_secret")
                .doesNotContain("accountNo");
    }

    @Test
    void credentialsToStringDoesNotLeakSecret() {
        var credentials = new TossCredentials("test-client", "super-secret-client-value");

        assertThat(credentials.toString())
                .doesNotContain("test-client")
                .doesNotContain("super-secret-client-value");
    }
}
