package com.jmj.trade.investment;

/**
 * Who performs a {@code putThesis} write; stored verbatim as {@code investment_thesis_revisions.actor_type}.
 * Package-private so only the investment domain (user REST, Telegram 2-step approval) can select an actor;
 * the connector/MCP path uses {@code putThesisProposal} and can never reach a CONFIRMED write.
 */
enum ThesisActor {
    USER_SESSION,
    TELEGRAM
}
