package com.jmj.trade.broker;

import java.util.Objects;

public record BrokerAccountView(BrokerAccountRef account, String displayName) {

    public BrokerAccountView {
        Objects.requireNonNull(account, "account");
        displayName = BrokerPreconditions.nonBlank(displayName, "displayName");
    }
}
