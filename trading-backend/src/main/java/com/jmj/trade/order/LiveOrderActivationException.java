package com.jmj.trade.order;

public final class LiveOrderActivationException extends RuntimeException {

    private final Code code;

    public LiveOrderActivationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        NOT_FOUND,
        CONFLICT,
        VALIDATION,
        STEP_UP_REQUIRED,
        SAFETY_BLOCKED,
        MANUAL_REVIEW_REQUIRED,
        PROPOSAL_EXPIRED
    }
}
