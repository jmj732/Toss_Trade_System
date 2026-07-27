package com.jmj.trade.analysis;

public final class PortfolioAnalysisException extends RuntimeException {

    private final Code code;

    PortfolioAnalysisException(Code code) {
        super(code.name());
        this.code = code;
    }

    PortfolioAnalysisException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_USER,
        NOT_FOUND,
        SNAPSHOT_NOT_FOUND,
        RESULT_NOT_FOUND,
        ALREADY_RUNNING,
        TIMEOUT,
        CONTRACT_ERROR,
        UPSTREAM_UNAVAILABLE
    }
}
