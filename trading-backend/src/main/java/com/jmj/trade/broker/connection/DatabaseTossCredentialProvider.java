package com.jmj.trade.broker.connection;

import com.jmj.trade.broker.toss.TossCredentialMetadata;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import com.jmj.trade.broker.toss.TossCredentials;

import java.util.Objects;
import java.util.UUID;

final class DatabaseTossCredentialProvider implements TossCredentialProvider {

    private final BrokerConnectionRepository repository;
    private final CredentialCipher cipher;

    DatabaseTossCredentialProvider(BrokerConnectionRepository repository, CredentialCipher cipher) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    @Override
    public TossCredentialMetadata current(UUID brokerConnectionId) {
        return repository.findTossCredentialMetadata(
                        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId"),
                        BrokerType.TOSS_INVEST)
                .orElseThrow(CredentialUnavailableException::new);
    }

    @Override
    public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
        if (expectedRevision <= 0) {
            throw new CredentialUnavailableException();
        }
        var connection = repository.findByIdAndBrokerTypeAndCredentialRevision(
                        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId"),
                        BrokerType.TOSS_INVEST,
                        expectedRevision)
                .orElseThrow(CredentialUnavailableException::new);
        return cipher.decrypt(
                connection.getId(),
                connection.getUserId(),
                connection.getBrokerType(),
                expectedRevision,
                connection.getEncryptedCredentials());
    }
}
