package com.jmj.trade.investment;

/**
 * Who performs a {@code putThesis} write; stored verbatim as {@code investment_thesis_revisions.actor_type}.
 * Package-private so only the investment domain can select an actor; connector/MCP proposal writes never choose
 * {@code AI_POLICY}, which is reserved for the transactionally verified external-evidence workflow.
 */
enum ThesisActor {
    USER_SESSION,
    TELEGRAM,
    AI_POLICY
}
