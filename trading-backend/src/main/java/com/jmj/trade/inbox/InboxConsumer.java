package com.jmj.trade.inbox;

/**
 * A named consumer of outbox events, run through the inbox idempotency ledger. Each event type may
 * have several consumers; the relay executes every one of them independently against a single
 * claimed outbox row.
 *
 * <p>{@link #name()} is a code constant that identifies this consumer in {@code inbox_messages}. It
 * must never change once the consumer has processed events, because the ledger matches past work by
 * {@code (consumer_name, event_id)} — a renamed consumer would re-run every historical event.
 *
 * <p>{@link #consume(Object)} runs inside a transaction that also records the inbox row, so a
 * consumer only needs to make its own domain writes; recording completion and (on failure) rolling
 * both back together is the relay's responsibility. A consumer's writes should still be idempotent
 * on their own — if the inbox row is ever lost (a crash between the domain commit and nothing else,
 * or a manual purge) the event is re-delivered and the consumer runs again.
 *
 * @param <E> the event view a consumer reads (e.g. a claimed outbox record)
 */
public interface InboxConsumer<E> {

    String name();

    void consume(E event);
}
