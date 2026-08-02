package com.jmj.trade.marketdata;

public enum StockDataProviderId {
    TOSS,
    SEC,
    FRED,
    BLS,
    BEA,
    FED,
    FMP,
    FINNHUB,
    POLYGON,
    TWELVE_DATA;

    public static StockDataProviderId parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("provider id is required");
        }
        try {
            return value.trim().toUpperCase().replace('-', '_').replace(' ', '_')
                    .transform(StockDataProviderId::valueOf);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown stock data provider: " + value, exception);
        }
    }
}
