package com.jmj.trade.broker.toss;

import java.util.UUID;

public interface TossCredentialProvider {

    TossCredentials get(UUID brokerConnectionId);
}
