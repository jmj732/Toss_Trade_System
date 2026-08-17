package com.jmj.trade.broker.connection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.jmj.trade.broker.toss.TossCredentialMetadata;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

interface BrokerConnectionRepository extends JpaRepository<BrokerConnection, UUID> {

    Optional<BrokerConnection> findByIdAndUserId(UUID id, UUID userId);

    List<BrokerConnection> findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID userId);

    @Query("""
            select new com.jmj.trade.broker.connection.BrokerConnectionMetadata(
                connection.id,
                connection.userId,
                connection.brokerType,
                connection.status,
                connection.credentialRevision,
                connection.lastValidatedAt,
                connection.deletedAt
            )
            from BrokerConnection connection
            where connection.id = :id
              and connection.userId = :userId
            """)
    Optional<BrokerConnectionMetadata> findMetadataByIdAndUserId(UUID id, UUID userId);

    @Query("""
            select new com.jmj.trade.broker.toss.TossCredentialMetadata(connection.credentialRevision)
            from BrokerConnection connection
            where connection.id = :id
              and connection.brokerType = :brokerType
              and connection.deletedAt is null
            """)
    Optional<TossCredentialMetadata> findTossCredentialMetadata(UUID id, BrokerType brokerType);

    Optional<BrokerConnection> findByIdAndBrokerTypeAndCredentialRevisionAndDeletedAtIsNull(
            UUID id,
            BrokerType brokerType,
            long credentialRevision
    );

    default Optional<BrokerConnection> findByIdAndBrokerTypeAndCredentialRevision(
            UUID id,
            BrokerType brokerType,
            long credentialRevision
    ) {
        return findByIdAndBrokerTypeAndCredentialRevisionAndDeletedAtIsNull(id, brokerType, credentialRevision);
    }
}
