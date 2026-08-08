package com.jmj.trade.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

/**
 * Owns the {@code credentialSecureRandom} bean shared by {@code predictionIngestionApiKeyService}
 * (prediction) and {@code orderApprovalStepUpService} (order). It shares the credential vault's
 * activation property so the bean set stays byte-identical to when it lived in
 * {@code CredentialVaultConfiguration}; a follow-up delta can drop the condition to make this
 * randomness unconditional.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class CredentialRandomnessConfiguration {

    @Bean
    SecureRandom credentialSecureRandom() {
        return new SecureRandom();
    }
}
