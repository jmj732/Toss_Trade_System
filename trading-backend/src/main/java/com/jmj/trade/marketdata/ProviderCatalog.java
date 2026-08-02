package com.jmj.trade.marketdata;

import java.util.Map;

public final class ProviderCatalog {

    private static final Map<StockDataProviderId, DataProviderRole> ROLES = Map.of(
            StockDataProviderId.TOSS, DataProviderRole.BROKER_ACCOUNT,
            StockDataProviderId.SEC, DataProviderRole.REGULATORY_FILINGS,
            StockDataProviderId.FRED, DataProviderRole.MACRO,
            StockDataProviderId.BLS, DataProviderRole.MACRO,
            StockDataProviderId.BEA, DataProviderRole.MACRO,
            StockDataProviderId.FED, DataProviderRole.MACRO,
            StockDataProviderId.FMP, DataProviderRole.FUNDAMENTALS,
            StockDataProviderId.FINNHUB, DataProviderRole.NEWS,
            StockDataProviderId.POLYGON, DataProviderRole.MARKET_DATA,
            StockDataProviderId.TWELVE_DATA, DataProviderRole.MARKET_DATA);

    private static final Map<StockDataProviderId, ProviderTransportProfile> TRANSPORTS = Map.of(
            StockDataProviderId.TOSS, new ProviderTransportProfile("Authorization", "", false),
            StockDataProviderId.SEC, new ProviderTransportProfile("", "", true),
            StockDataProviderId.FRED, new ProviderTransportProfile("", "api_key", false),
            StockDataProviderId.BLS, new ProviderTransportProfile("", "", false),
            StockDataProviderId.BEA, new ProviderTransportProfile("", "UserID", false),
            StockDataProviderId.FED, new ProviderTransportProfile("", "", false),
            StockDataProviderId.FMP, new ProviderTransportProfile("", "apikey", false),
            StockDataProviderId.FINNHUB, new ProviderTransportProfile("", "token", false),
            StockDataProviderId.POLYGON, new ProviderTransportProfile("", "apiKey", false),
            StockDataProviderId.TWELVE_DATA, new ProviderTransportProfile("", "apikey", false));

    private ProviderCatalog() {
    }

    public static DataProviderRole roleOf(StockDataProviderId provider) {
        return ROLES.get(provider);
    }

    public static Map<StockDataProviderId, DataProviderRole> roles() {
        return ROLES;
    }

    static ProviderTransportProfile transportOf(StockDataProviderId provider) {
        return TRANSPORTS.get(provider);
    }
}
