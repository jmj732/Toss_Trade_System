package com.jmj.trade.account;

public final class PortfolioHistoryException extends RuntimeException {

    private final Code code;

    PortfolioHistoryException(Code code) {
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
