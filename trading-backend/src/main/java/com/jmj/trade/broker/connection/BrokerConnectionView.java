package com.jmj.trade.broker.connection;

import java.time.Instant;
import java.util.UUID;

public record BrokerConnectionView(
        UUID id,
        UUID userId,
        BrokerType brokerType,
        BrokerConnectionStatus status,
        long credentialRevision,
        Instant lastValidatedAt
) {

    static BrokerConnectionView from(BrokerConnection connection) {
        return new BrokerConnectionView(
                connection.getId(),
                connection.getUserId(),
                connection.getBrokerType(),
                connection.getStatus(),
                connection.getCredentialRevision(),
                connection.getLastValidatedAt());
    }
}
