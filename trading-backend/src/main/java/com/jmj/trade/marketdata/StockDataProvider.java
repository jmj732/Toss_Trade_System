package com.jmj.trade.marketdata;

import java.util.List;
import java.util.Set;

public interface StockDataProvider {

    StockDataProviderId id();

    DataProviderRole role();

    Set<String> fields();

    List<ProviderValue> fetch(ProviderRequest request);

    default List<ProviderValue> fetch(ProviderRequest request, Set<String> selectedFields) {
        if (selectedFields == null || selectedFields.isEmpty()) return List.of();
        return fetch(request).stream().filter(value -> selectedFields.contains(value.field())).toList();
    }
}
