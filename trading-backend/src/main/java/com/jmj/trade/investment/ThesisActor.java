package com.jmj.trade.investment;

/**
 * Actor type for thesis revisions. Determines the source of thesis changes.
 */
enum ThesisActor {
    USER_SESSION("USER_SESSION"),
    CONNECTOR_MCP("CONNECTOR_MCP"),
    TELEGRAM("TELEGRAM");

    private final String value;

    ThesisActor(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
