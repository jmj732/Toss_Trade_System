package com.jmj.trade.account;

import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AccountSyncService {

    private final AccountSyncTransactions transactions;
    private final BrokerAdapter broker;

    public AccountSyncService(AccountSyncTransactions transactions, BrokerAdapter broker) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.broker = Objects.requireNonNull(broker, "broker");
    }

    public AccountSyncResult sync(UUID userId, UUID connectionId) {
        var target = transactions.start(userId, connectionId);
        try {
            var accounts = broker.getAccounts(new BrokerConnectionRef(target.connectionId())).value();
            if (accounts.size() != 1) {
                throw new AccountSyncException(AccountSyncException.Code.ACCOUNT_COUNT_UNSUPPORTED);
            }

            var account = accounts.getFirst().account();
            requireSameConnection(account, target.connectionId());
            var snapshot = broker.getAccount(account).value();
            var positions = List.copyOf(broker.getPositions(account).value());
            var krwCapacity = broker.getAccountCapacity(account, Currency.KRW).value();
            var usdCapacity = broker.getAccountCapacity(account, Currency.USD).value();
            requireSameAccount(account, snapshot, positions, krwCapacity, usdCapacity);

            return transactions.complete(
                    target,
                    account,
                    snapshot,
                    positions,
                    List.of(krwCapacity, usdCapacity));
        } catch (RuntimeException exception) {
            try {
                transactions.fail(target, errorCode(exception));
            } catch (RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    public Optional<AccountSyncResult> latestSuccessful(UUID userId, UUID connectionId) {
        return transactions.latestSuccessful(userId, connectionId);
    }

    private static void requireSameConnection(BrokerAccountRef account, UUID connectionId) {
        if (!account.brokerConnectionId().equals(connectionId)) {
            throw new AccountSyncException(AccountSyncException.Code.BROKER_CONTRACT_MISMATCH);
        }
    }

    private static void requireSameAccount(
            BrokerAccountRef account,
            AccountSnapshot snapshot,
            List<Position> positions,
            AccountCapacitySnapshot krwCapacity,
            AccountCapacitySnapshot usdCapacity
    ) {
        if (!snapshot.account().equals(account)
                || positions.stream().anyMatch(position -> !position.account().equals(account))
                || !krwCapacity.account().equals(account)
                || krwCapacity.currency() != Currency.KRW
                || !usdCapacity.account().equals(account)
                || usdCapacity.currency() != Currency.USD) {
            throw new AccountSyncException(AccountSyncException.Code.BROKER_CONTRACT_MISMATCH);
        }
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof AccountSyncException syncException) {
            return syncException.code().name();
        }
        if (exception instanceof BrokerException brokerException) {
            return "BROKER_" + brokerException.category().name();
        }
        return "INTERNAL_ERROR";
    }
}
