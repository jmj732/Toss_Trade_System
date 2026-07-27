package com.jmj.trade.intelligence;

final class EventIntelligenceException extends RuntimeException {

    private final Code code;

    EventIntelligenceException(Code code) {
        super(code.name());
        this.code = code;
    }

    Code code() {
        return code;
    }

    enum Code {
        INVALID_USER,
        INVALID_INPUT,
        CONNECTION_NOT_FOUND,
        EVENT_NOT_FOUND,
        EVENT_ALREADY_EXISTS,
        EVENT_ALREADY_ANALYZED,
        COMPARISON_NOT_FOUND,
        REVIEW_CONFLICT
    }
}
