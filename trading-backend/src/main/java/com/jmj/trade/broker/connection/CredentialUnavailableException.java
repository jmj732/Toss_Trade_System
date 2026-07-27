package com.jmj.trade.broker.connection;

public class CredentialUnavailableException extends RuntimeException {

    public CredentialUnavailableException() {
        super("broker credential unavailable");
    }
}
