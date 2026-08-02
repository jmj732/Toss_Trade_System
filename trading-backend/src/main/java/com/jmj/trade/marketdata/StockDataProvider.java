package com.jmj.trade.marketdata;

import java.util.List;
import java.util.Set;

public interface StockDataProvider {

    StockDataProviderId id();

    DataProviderRole role();

    Set<String> fields();

    List<ProviderValue> fetch(ProviderRequest request);
}
