package com.jmj.trade.broker.connection;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;

import java.util.Objects;
import java.util.UUID;

public class BrokerConnectionValidationService {

    private final BrokerConnectionTransactions transactions;
    private final BrokerAdapter brokerAdapter;

    BrokerConnectionValidationService(BrokerConnectionTransactions transactions, BrokerAdapter brokerAdapter) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.brokerAdapter = Objects.requireNonNull(brokerAdapter, "brokerAdapter");
    }

    public BrokerConnectionView validateToss(UUID userId, UUID connectionId) {
        var target = transactions.loadOwnedTarget(userId, connectionId);
        try {
            brokerAdapter.getAccounts(new BrokerConnectionRef(target.connectionId()));
            return transactions.markValidated(userId, target.connectionId(), target.credentialRevision());
        } catch (BrokerException exception) {
            if (isInvalidCredential(exception)) {
                transactions.markInvalid(userId, target.connectionId(), target.credentialRevision());
                throw BrokerConnectionException.validationFailed();
            }
            throw exception;
        }
    }

    private static boolean isInvalidCredential(BrokerException exception) {
        return exception.category() == BrokerErrorCategory.AUTHENTICATION
                || exception.category() == BrokerErrorCategory.AUTHORIZATION;
    }
}
