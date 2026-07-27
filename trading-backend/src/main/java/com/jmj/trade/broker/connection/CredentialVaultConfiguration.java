package com.jmj.trade.broker.connection;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CredentialVaultProperties.class)
public class CredentialVaultConfiguration {

    @Bean
    SecureRandom credentialSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    CredentialKeyring credentialKeyring(CredentialVaultProperties properties) {
        return new CredentialKeyring(properties);
    }

    @Bean
    CredentialCipher credentialCipher(CredentialKeyring keyring, SecureRandom credentialSecureRandom) {
        return new CredentialCipher(keyring, credentialSecureRandom);
    }

    @Bean
    BrokerConnectionService brokerConnectionService(
            UserAnchorRepository userAnchorRepository,
            BrokerConnectionRepository connectionRepository,
            CredentialCipher credentialCipher
    ) {
        return new BrokerConnectionService(userAnchorRepository, connectionRepository, credentialCipher);
    }

    @Bean
    BrokerConnectionTransactions brokerConnectionTransactions(BrokerConnectionRepository repository) {
        return new BrokerConnectionTransactions(repository);
    }

    @Bean
    TossCredentialProvider tossCredentialProvider(BrokerConnectionRepository repository, CredentialCipher cipher) {
        return new DatabaseTossCredentialProvider(repository, cipher);
    }

    @Bean
    BrokerConnectionValidationService brokerConnectionValidationService(
            BrokerConnectionTransactions transactions,
            BrokerAdapter brokerAdapter
    ) {
        return new BrokerConnectionValidationService(transactions, brokerAdapter);
    }
}
