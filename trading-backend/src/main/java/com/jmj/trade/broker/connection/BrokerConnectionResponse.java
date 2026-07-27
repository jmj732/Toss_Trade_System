package com.jmj.trade.broker.connection;

import java.time.Instant;
import java.util.UUID;

public record BrokerConnectionResponse(
        UUID id,
        BrokerType brokerType,
        BrokerConnectionStatus status,
        long credentialRevision,
        Instant lastValidatedAt
) {

    static BrokerConnectionResponse from(BrokerConnectionView view) {
        return new BrokerConnectionResponse(
                view.id(),
                view.brokerType(),
                view.status(),
                view.credentialRevision(),
                view.lastValidatedAt());
    }
}
