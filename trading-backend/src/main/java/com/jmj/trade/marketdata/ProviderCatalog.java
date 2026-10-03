package com.jmj.trade.marketdata;

import java.util.Map;

public final class ProviderCatalog {

    private static final Map<StockDataProviderId, DataProviderRole> ROLES = Map.ofEntries(
            Map.entry(StockDataProviderId.TOSS, DataProviderRole.BROKER_ACCOUNT),
            Map.entry(StockDataProviderId.SEC, DataProviderRole.REGULATORY_FILINGS),
            Map.entry(StockDataProviderId.FRED, DataProviderRole.MACRO),
            Map.entry(StockDataProviderId.BLS, DataProviderRole.MACRO),
            Map.entry(StockDataProviderId.BEA, DataProviderRole.MACRO),
            Map.entry(StockDataProviderId.FED, DataProviderRole.MACRO),
            Map.entry(StockDataProviderId.FMP, DataProviderRole.FUNDAMENTALS),
            Map.entry(StockDataProviderId.ALPHA_VANTAGE, DataProviderRole.FUNDAMENTALS),
            Map.entry(StockDataProviderId.FINNHUB, DataProviderRole.NEWS),
            Map.entry(StockDataProviderId.POLYGON, DataProviderRole.MARKET_DATA),
            Map.entry(StockDataProviderId.TWELVE_DATA, DataProviderRole.MARKET_DATA));

    private static final Map<StockDataProviderId, ProviderTransportProfile> TRANSPORTS = Map.ofEntries(
            Map.entry(StockDataProviderId.TOSS, new ProviderTransportProfile("Authorization", "", false)),
            Map.entry(StockDataProviderId.SEC, new ProviderTransportProfile("", "", true)),
            Map.entry(StockDataProviderId.FRED, new ProviderTransportProfile("", "api_key", false)),
            Map.entry(StockDataProviderId.BLS, new ProviderTransportProfile("", "", false)),
            Map.entry(StockDataProviderId.BEA, new ProviderTransportProfile("", "UserID", false)),
            Map.entry(StockDataProviderId.FED, new ProviderTransportProfile("", "", false)),
            Map.entry(StockDataProviderId.FMP, new ProviderTransportProfile("", "apikey", false)),
            Map.entry(StockDataProviderId.ALPHA_VANTAGE,
                    new ProviderTransportProfile("", "apikey", false)),
            Map.entry(StockDataProviderId.FINNHUB, new ProviderTransportProfile("", "token", false)),
            Map.entry(StockDataProviderId.POLYGON, new ProviderTransportProfile("", "apiKey", false)),
            Map.entry(StockDataProviderId.TWELVE_DATA, new ProviderTransportProfile("", "apikey", false)));

    private ProviderCatalog() {
    }

    public static DataProviderRole roleOf(StockDataProviderId provider) {
        return ROLES.get(provider);
    }

    public static Map<StockDataProviderId, DataProviderRole> roles() {
        return ROLES;
    }

    public static boolean requiresCredential(StockDataProviderId provider) {
        return requiresApiKey(provider) || requiresUserAgent(provider);
    }

    public static boolean requiresApiKey(StockDataProviderId provider) {
        var profile = TRANSPORTS.get(provider);
        return profile != null && (!profile.defaultApiKeyHeader().isBlank()
                || !profile.defaultApiKeyQueryParameter().isBlank());
    }

    public static boolean requiresUserAgent(StockDataProviderId provider) {
        var profile = TRANSPORTS.get(provider);
        return profile != null && profile.userAgentRequired();
    }

    public static boolean credentialsPresent(
            StockDataProviderId provider,
            String apiKey,
            String userAgent
    ) {
        return (!requiresApiKey(provider) || nonBlank(apiKey))
                && (!requiresUserAgent(provider) || nonBlank(userAgent));
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    static ProviderTransportProfile transportOf(StockDataProviderId provider) {
        return TRANSPORTS.get(provider);
    }
}
