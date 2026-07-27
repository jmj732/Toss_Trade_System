package com.jmj.trade.broker.connection;

import com.jmj.trade.broker.toss.TossCredentials;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class BrokerConnectionService {

    private final UserAnchorRepository userAnchorRepository;
    private final BrokerConnectionRepository connectionRepository;
    private final CredentialCipher credentialCipher;

    BrokerConnectionService(
            UserAnchorRepository userAnchorRepository,
            BrokerConnectionRepository connectionRepository,
            CredentialCipher credentialCipher
    ) {
        this.userAnchorRepository = Objects.requireNonNull(userAnchorRepository, "userAnchorRepository");
        this.connectionRepository = Objects.requireNonNull(connectionRepository, "connectionRepository");
        this.credentialCipher = Objects.requireNonNull(credentialCipher, "credentialCipher");
    }

    @Transactional
    public BrokerConnectionView createToss(UUID userId, String clientId, String clientSecret) {
        var ownerId = requireId(userId, "userId");
        var connectionId = UUID.randomUUID();
        var now = Instant.now();
        try {
            userAnchorRepository.anchor(ownerId);
            var encrypted = credentialCipher.encrypt(
                    connectionId,
                    ownerId,
                    BrokerType.TOSS_INVEST,
                    1,
                    new TossCredentials(clientId, clientSecret));
            var connection = BrokerConnection.create(connectionId, ownerId, encrypted, now);
            return BrokerConnectionView.from(connectionRepository.saveAndFlush(connection));
        } catch (DataIntegrityViolationException exception) {
            throw BrokerConnectionException.alreadyExists();
        }
    }

    @Transactional
    public BrokerConnectionView replaceCredentials(
            UUID userId,
            UUID connectionId,
            String clientId,
            String clientSecret
    ) {
        var connection = openConnection(userId, connectionId);
        var nextRevision = connection.getCredentialRevision() + 1;
        try {
            var encrypted = credentialCipher.encrypt(
                    connection.getId(),
                    connection.getUserId(),
                    connection.getBrokerType(),
                    nextRevision,
                    new TossCredentials(clientId, clientSecret));
            connection.replaceCredentials(encrypted, Instant.now());
            return BrokerConnectionView.from(connectionRepository.saveAndFlush(connection));
        } catch (OptimisticLockingFailureException exception) {
            throw BrokerConnectionException.conflict();
        }
    }

    @Transactional
    public void delete(UUID userId, UUID connectionId) {
        var connection = openConnection(userId, connectionId);
        try {
            connection.delete(Instant.now());
            connectionRepository.saveAndFlush(connection);
        } catch (OptimisticLockingFailureException exception) {
            throw BrokerConnectionException.conflict();
        }
    }

    private BrokerConnection openConnection(UUID userId, UUID connectionId) {
        var ownerId = requireId(userId, "userId");
        var id = requireId(connectionId, "connectionId");
        var connection = connectionRepository.findByIdAndUserId(id, ownerId)
                .orElseThrow(BrokerConnectionException::notFound);
        if (connection.getDeletedAt() != null || connection.getStatus() == BrokerConnectionStatus.DELETED) {
            throw BrokerConnectionException.notFound();
        }
        return connection;
    }

    private static UUID requireId(UUID value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
