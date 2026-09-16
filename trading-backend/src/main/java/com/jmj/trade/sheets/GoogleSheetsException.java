package com.jmj.trade.sheets;

/** Safe Google Sheets client failure; response bodies are intentionally not retained. */
public final class GoogleSheetsException extends RuntimeException {

    private final Integer httpStatus;

    private GoogleSheetsException(String message, Integer httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    static GoogleSheetsException http(String message, int status) {
        return new GoogleSheetsException(message + " (HTTP " + status + ")", status);
    }

    static GoogleSheetsException network(String message) {
        return new GoogleSheetsException(message + " (network)", null);
    }

    static GoogleSheetsException contract(String message) {
        return new GoogleSheetsException(message + " (contract)", 200);
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
