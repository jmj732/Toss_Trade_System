package com.jmj.trade.intelligence.ingestion;

public enum MarketEventProviderId {
    SEC,
    IR,
    FED,
    FRED,
    BLS,
    BEA;

    public static MarketEventProviderId parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("market event provider is required");
        }
        try {
            return value.trim().toUpperCase().replace('-', '_').transform(value1 ->
                    valueOf(value1));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown market event provider: " + value, exception);
        }
    }
}
