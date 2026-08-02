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
}
