package com.jmj.trade.account;

public final class PortfolioReadException extends RuntimeException {

    public PortfolioReadException() {
        super("PORTFOLIO_SNAPSHOT_NOT_FOUND");
    }
}
