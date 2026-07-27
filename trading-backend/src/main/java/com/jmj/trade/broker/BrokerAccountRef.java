package com.jmj.trade.broker;

import java.util.Objects;
import java.util.UUID;

public record BrokerAccountRef(
        UUID brokerConnectionId,
        String brokerAccountId,
        String accountType,
        String displayAccountNumber) {

    public BrokerAccountRef {
        Objects.requireNonNull(brokerConnectionId, "brokerConnectionId");
        brokerAccountId = BrokerPreconditions.nonBlank(brokerAccountId, "brokerAccountId");
        accountType = BrokerPreconditions.nonBlank(accountType, "accountType");
        displayAccountNumber = BrokerPreconditions.nonBlank(displayAccountNumber, "displayAccountNumber");
        if (!displayAccountNumber.contains("*") || visibleSuffixLength(displayAccountNumber) > 4) {
            throw new IllegalArgumentException("displayAccountNumber must be masked and reveal at most the final four characters");
        }
    }

    private static int visibleSuffixLength(String value) {
        var lastMask = value.lastIndexOf('*');
        return value.length() - lastMask - 1;
    }
}
