package com.jmj.trade.account;

import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.SellableQuantitySnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            var sellableQuantities = readSellableQuantities(account, positions);
            var krwCapacity = broker.getAccountCapacity(account, Currency.KRW).value();
            var usdCapacity = broker.getAccountCapacity(account, Currency.USD).value();
            requireSameAccount(
                    account, snapshot, positions, sellableQuantities, krwCapacity, usdCapacity);

            return transactions.complete(
                    target,
                    account,
                    snapshot,
                    positions,
                    sellableQuantities,
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
            Map<String, SellableQuantitySnapshot> sellableQuantities,
            AccountCapacitySnapshot krwCapacity,
            AccountCapacitySnapshot usdCapacity
    ) {
        if (!snapshot.account().equals(account)
                || positions.stream().anyMatch(position -> !position.account().equals(account))
                || sellableQuantities.size() != positions.size()
                || positions.stream().anyMatch(position -> {
                    var sellable = sellableQuantities.get(position.symbol());
                    return sellable == null
                            || !sellable.account().equals(account)
                            || !sellable.symbol().equals(position.symbol());
                })
                || !krwCapacity.account().equals(account)
                || krwCapacity.currency() != Currency.KRW
                || !usdCapacity.account().equals(account)
                || usdCapacity.currency() != Currency.USD) {
            throw new AccountSyncException(AccountSyncException.Code.BROKER_CONTRACT_MISMATCH);
        }
    }

    private Map<String, SellableQuantitySnapshot> readSellableQuantities(
            BrokerAccountRef account,
            List<Position> positions
    ) {
        var result = new LinkedHashMap<String, SellableQuantitySnapshot>();
        for (var position : positions) {
            var sellable = broker.getSellableQuantity(account, position.symbol()).value();
            if (sellable == null || result.putIfAbsent(position.symbol(), sellable) != null) {
                throw new AccountSyncException(AccountSyncException.Code.BROKER_CONTRACT_MISMATCH);
            }
            if (sellable.availability() == SellableQuantitySnapshot.Availability.KNOWN
                    && sellable.quantity().compareTo(position.quantity()) > 0) {
                result.put(position.symbol(), SellableQuantitySnapshot.unknown(
                    account, position.symbol(), sellable.observedAt()));
            }
        }
        return Map.copyOf(result);
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
