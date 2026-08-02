package com.jmj.trade.analysis;

public final class StockAnalysisException extends RuntimeException {

    private final Code code;

    StockAnalysisException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_USER,
        INVALID_SYMBOL,
        NOT_FOUND,
        ALREADY_RUNNING,
        TIMEOUT,
        CONTRACT_ERROR,
        UPSTREAM_UNAVAILABLE
    }
}
