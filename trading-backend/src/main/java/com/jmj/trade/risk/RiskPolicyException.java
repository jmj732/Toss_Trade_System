package com.jmj.trade.risk;

public final class RiskPolicyException extends RuntimeException {

    private final Code code;

    RiskPolicyException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_USER,
        INVALID_INPUT,
        VERSION_CONFLICT
    }
}
