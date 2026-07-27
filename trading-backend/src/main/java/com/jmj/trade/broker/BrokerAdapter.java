package com.jmj.trade.broker;

import java.util.List;

public interface BrokerAdapter {

    BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection);

    BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account);

    BrokerResponse<List<Position>> getPositions(BrokerAccountRef account);

    BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol);

    BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(BrokerAccountRef account, Currency currency);
}
