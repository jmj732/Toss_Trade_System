package com.jmj.trade.broker.connection;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class BrokerConnectionTransactions {

    private final BrokerConnectionRepository repository;

    BrokerConnectionTransactions(BrokerConnectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Transactional
    public ValidationTarget loadOwnedTarget(UUID userId, UUID connectionId) {
        var connection = openConnection(userId, connectionId);
        return new ValidationTarget(connection.getId(), connection.getCredentialRevision());
    }

    @Transactional
    public BrokerConnectionView markValidated(UUID userId, UUID id, long expectedRevision) {
        var connection = openConnection(userId, id);
        try {
            connection.markValidated(expectedRevision, Instant.now());
            return BrokerConnectionView.from(repository.saveAndFlush(connection));
        } catch (IllegalStateException | OptimisticLockingFailureException exception) {
            throw BrokerConnectionException.conflict();
        }
    }

    @Transactional
    public BrokerConnectionView markInvalid(UUID userId, UUID id, long expectedRevision) {
        var connection = openConnection(userId, id);
        try {
            connection.markInvalid(expectedRevision, Instant.now());
            return BrokerConnectionView.from(repository.saveAndFlush(connection));
        } catch (IllegalStateException | OptimisticLockingFailureException exception) {
            throw BrokerConnectionException.conflict();
        }
    }

    private BrokerConnection openConnection(UUID userId, UUID connectionId) {
        var ownerId = requireId(userId, "userId");
        var id = requireId(connectionId, "connectionId");
        var connection = repository.findByIdAndUserId(id, ownerId)
                .orElseThrow(BrokerConnectionException::notFound);
        if (connection.getBrokerType() != BrokerType.TOSS_INVEST
                || connection.getDeletedAt() != null
                || connection.getStatus() == BrokerConnectionStatus.DELETED) {
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
