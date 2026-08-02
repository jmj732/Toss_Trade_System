-- Per-consumer idempotency ledger for the in-process outbox relays. One row records that a named
-- consumer finished a specific outbox event, so a re-delivery (the relay re-claims a row whose
-- publish never got stamped) or a second subscriber can be reasoned about independently: the
-- consumer's domain change and its inbox row commit together, and UNIQUE (consumer_name, event_id)
-- makes a concurrent double-consume roll the loser back instead of duplicating side effects.
--
-- event_id is the id of the originating outbox row. Several outbox ledgers
-- (order_intent_outbox_events, order_submission_outbox_events, notification_outbox_events) feed this
-- one table, so there is deliberately no FK to any of them; event_type disambiguates the source.
CREATE TABLE inbox_messages (
    id UUID PRIMARY KEY,
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT uq_inbox_consumer_event UNIQUE (consumer_name, event_id)
);

-- Supports scanning for not-yet-finished work per consumer without walking finished rows.
CREATE INDEX ix_inbox_unprocessed
    ON inbox_messages (consumer_name, event_id)
    WHERE processed_at IS NULL;
