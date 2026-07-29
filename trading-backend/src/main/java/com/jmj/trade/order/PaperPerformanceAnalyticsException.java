package com.jmj.trade.order;

public final class PaperPerformanceAnalyticsException extends RuntimeException {

    private final Code code;

    PaperPerformanceAnalyticsException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_INPUT
    }
}
