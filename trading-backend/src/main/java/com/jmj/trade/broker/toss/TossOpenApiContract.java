package com.jmj.trade.broker.toss;

/** Pinned Toss OpenAPI version. The dispatch-evidence label is derived from it, so it cannot drift. */
public final class TossOpenApiContract {

    public static final String VERSION = "1.2.28";

    private TossOpenApiContract() {
    }

    /** Label recorded in persisted dispatch evidence for every live Toss dispatch. */
    public static String dispatchEvidenceLabel() {
        return "toss-" + VERSION;
    }
}
