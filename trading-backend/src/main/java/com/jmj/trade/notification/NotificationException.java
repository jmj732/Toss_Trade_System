package com.jmj.trade.notification;

public final class NotificationException extends RuntimeException {

    private final Code code;

    NotificationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_USER,
        INVALID_INPUT,
        NOT_FOUND
    }
}
