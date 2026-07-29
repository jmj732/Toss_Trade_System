package com.jmj.trade.prediction;

final class PredictionModelRegistryException extends RuntimeException {

    private final Code code;

    PredictionModelRegistryException(Code code) {
        super(code.name());
        this.code = code;
    }

    Code code() {
        return code;
    }

    enum Code {
        INVALID_INPUT,
        ALREADY_EXISTS,
        NOT_FOUND,
        IN_USE
    }
}
