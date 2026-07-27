package com.jmj.trade.analysis;

final class PortfolioAnalysisException extends RuntimeException {

    private final Code code;

    PortfolioAnalysisException(Code code) {
        super(code.name());
        this.code = code;
    }

    PortfolioAnalysisException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    Code code() {
        return code;
    }

    enum Code {
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
