package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;

import java.util.List;
import java.util.Objects;

final class TossInvestBrokerAdapter implements BrokerAdapter {

    private final TossApiClient apiClient;
    private final TossResponseMapper mapper;

    TossInvestBrokerAdapter(TossApiClient apiClient, TossResponseMapper mapper) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
        mapper.requireToss(connection);
        var response = apiClient.getAccounts(connection.brokerConnectionId());
        var accounts = List.copyOf(response.value().stream()
                .map(account -> mapper.account(connection, account))
                .toList());
        return new BrokerResponse<>(accounts, response.metadata());
    }

    @Override
    public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
        mapper.requireToss(account);
        var response = apiClient.getHoldings(account.brokerConnectionId(), account.brokerAccountId());
        return new BrokerResponse<>(mapper.accountSnapshot(account, response.value(), response.metadata()), response.metadata());
    }

    @Override
    public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
        mapper.requireToss(account);
        var response = apiClient.getHoldings(account.brokerConnectionId(), account.brokerAccountId());
        return new BrokerResponse<>(mapper.positions(account, response.value(), response.metadata()), response.metadata());
    }

    @Override
    public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
        mapper.requireToss(connection);
        var normalized = mapper.normalizeSymbol(symbol);
        var response = apiClient.getPrices(connection.brokerConnectionId(), normalized);
        return new BrokerResponse<>(mapper.quote(connection, normalized, response.value(), response.metadata()), response.metadata());
    }

    @Override
    public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(BrokerAccountRef account, Currency currency) {
        mapper.requireToss(account);
        var response = apiClient.getBuyingPower(account.brokerConnectionId(), account.brokerAccountId(), currency.name());
        return new BrokerResponse<>(mapper.capacity(account, currency, response.value(), response.metadata()), response.metadata());
    }
}
