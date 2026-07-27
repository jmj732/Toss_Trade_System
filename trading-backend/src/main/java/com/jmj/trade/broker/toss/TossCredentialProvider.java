package com.jmj.trade.broker.toss;

import java.util.UUID;

public interface TossCredentialProvider {

    TossCredentialMetadata current(UUID brokerConnectionId);

    TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision);
}
