package com.jmj.trade.monitoring;

final class MonitoringException extends RuntimeException {

    enum Code {
        INVALID_USER,
        INVALID_INPUT,
        NOT_FOUND
    }

    private final Code code;

    MonitoringException(Code code) {
        super(code.name());
        this.code = code;
    }

    Code code() {
        return code;
    }
}
