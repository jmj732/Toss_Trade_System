package com.jmj.trade.prediction;

public final class StockForecastException extends RuntimeException {

    private final Code code;

    StockForecastException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_INPUT,
        CONTRACT_ERROR,
        UPSTREAM_UNAVAILABLE,
        TIMEOUT,
        NOT_FOUND
    }
}
