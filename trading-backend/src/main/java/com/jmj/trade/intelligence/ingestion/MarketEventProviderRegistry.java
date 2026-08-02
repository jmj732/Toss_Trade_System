package com.jmj.trade.intelligence.ingestion;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class MarketEventProviderRegistry {

    private final Map<MarketEventProviderId, MarketEventProvider> providers;

    public MarketEventProviderRegistry(List<MarketEventProvider> providers) {
        var indexed = new EnumMap<MarketEventProviderId, MarketEventProvider>(
                MarketEventProviderId.class);
        for (var provider : providers == null ? List.<MarketEventProvider>of() : providers) {
            if (provider == null || provider.id() == null) {
                throw new IllegalArgumentException("market event provider identity is required");
            }
            if (indexed.put(provider.id(), provider) != null) {
                throw new IllegalArgumentException("duplicate market event provider: " + provider.id());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public List<MarketEventProvider> providers() {
        return providers.values().stream()
                .sorted(java.util.Comparator.comparing(provider -> provider.id().name()))
                .toList();
    }
}
