package com.jmj.trade.account;

public final class AccountSyncException extends RuntimeException {

    public enum Code {
        NOT_FOUND,
        SYNC_ALREADY_RUNNING,
        ACCOUNT_COUNT_UNSUPPORTED,
        CREDENTIAL_REVISION_CHANGED,
        BROKER_CONTRACT_MISMATCH
    }

    private final Code code;

    AccountSyncException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
