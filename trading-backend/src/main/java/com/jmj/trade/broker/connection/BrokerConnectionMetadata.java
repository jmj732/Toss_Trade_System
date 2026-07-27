package com.jmj.trade.broker.connection;

import java.time.Instant;
import java.util.UUID;

public record BrokerConnectionMetadata(
        UUID id,
        UUID userId,
        BrokerType brokerType,
        BrokerConnectionStatus status,
        long credentialRevision,
        Instant lastValidatedAt,
        Instant deletedAt
) {
}
