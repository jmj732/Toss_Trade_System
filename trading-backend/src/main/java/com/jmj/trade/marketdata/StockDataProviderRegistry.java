package com.jmj.trade.marketdata;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class StockDataProviderRegistry {

    private final Map<StockDataProviderId, StockDataProvider> providers;

    public StockDataProviderRegistry(List<StockDataProvider> providers) {
        var indexed = new EnumMap<StockDataProviderId, StockDataProvider>(StockDataProviderId.class);
        for (var provider : providers == null ? List.<StockDataProvider>of() : providers) {
            if (provider == null || provider.id() == null || provider.role() == null) {
                throw new IllegalArgumentException("provider identity is required");
            }
            if (ProviderCatalog.roleOf(provider.id()) != provider.role()) {
                throw new IllegalArgumentException("provider role does not match catalog");
            }
            if (indexed.put(provider.id(), provider) != null) {
                throw new IllegalArgumentException("duplicate provider: " + provider.id());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public static StockDataProviderRegistry optIn(
            Map<StockDataProviderId, Boolean> enabled,
            List<StockDataProvider> providers
    ) {
        return new StockDataProviderRegistry((providers == null ? List.<StockDataProvider>of() : providers)
                .stream()
                .filter(provider -> Boolean.TRUE.equals(enabled == null ? null : enabled.get(provider.id())))
                .toList());
    }

    public List<StockDataProvider> providers() {
        return providers.values().stream().sorted((left, right) -> left.id().compareTo(right.id())).toList();
    }
}
