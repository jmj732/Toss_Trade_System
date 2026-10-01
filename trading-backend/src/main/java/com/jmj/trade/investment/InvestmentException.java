package com.jmj.trade.investment;

public final class InvestmentException extends RuntimeException {

    private final Code code;

    public InvestmentException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_USER,
        INVALID_INPUT,
        NOT_FOUND,
        CONFLICT
    }
}
