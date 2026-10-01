package com.jmj.trade.marketdata;

public final class ProviderUnavailableException extends RuntimeException {

    private final StockDataProviderId provider;

    public ProviderUnavailableException(StockDataProviderId provider, String reason) {
        super(reason == null || reason.isBlank() ? "provider unavailable" : reason);
        this.provider = provider;
    }

    public StockDataProviderId provider() {
        return provider;
    }

    public String reasonCode() {
        return getMessage().replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(java.util.Locale.ROOT);
    }
}
