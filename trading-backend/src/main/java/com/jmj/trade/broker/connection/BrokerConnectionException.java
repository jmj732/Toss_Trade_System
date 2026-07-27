package com.jmj.trade.broker.connection;

public class BrokerConnectionException extends RuntimeException {

    private final Code code;

    private BrokerConnectionException(Code code) {
        super(code.publicCode());
        this.code = code;
    }

    public static BrokerConnectionException notFound() {
        return new BrokerConnectionException(Code.NOT_FOUND);
    }

    public static BrokerConnectionException alreadyExists() {
        return new BrokerConnectionException(Code.ALREADY_EXISTS);
    }

    public static BrokerConnectionException conflict() {
        return new BrokerConnectionException(Code.CONFLICT);
    }

    public static BrokerConnectionException validationFailed() {
        return new BrokerConnectionException(Code.VALIDATION_FAILED);
    }

    public Code code() {
        return code;
    }

    public enum Code {
        NOT_FOUND("BROKER_CONNECTION_NOT_FOUND"),
        ALREADY_EXISTS("BROKER_CONNECTION_ALREADY_EXISTS"),
        CONFLICT("BROKER_CONNECTION_CONFLICT"),
        VALIDATION_FAILED("BROKER_CONNECTION_VALIDATION_FAILED");

        private final String publicCode;

        Code(String publicCode) {
            this.publicCode = publicCode;
        }

        public String publicCode() {
            return publicCode;
        }
    }
}
