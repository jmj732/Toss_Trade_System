package com.jmj.trade.prediction;

public final class AnalysisPredictionException extends RuntimeException {

    private final Code code;

    AnalysisPredictionException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_INPUT,
        QUOTE_CURRENCY_MISMATCH,
        QUOTE_UNAVAILABLE
    }
}
