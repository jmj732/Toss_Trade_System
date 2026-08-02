-- Give both order outbox tables a dead-letter shape so the relay can stop retrying a row that
-- keeps failing, and a partial index over the rows the relay actually claims (unpublished AND
-- not dead-lettered) so the claim query stays index-only as the tables grow. The existing
-- payload/created_at/published_at/attempts columns keep their meaning; nothing here rewrites them.
ALTER TABLE order_intent_outbox_events
    ADD COLUMN failed_at TIMESTAMPTZ,
    ADD COLUMN last_error TEXT;

ALTER TABLE order_submission_outbox_events
    ADD COLUMN failed_at TIMESTAMPTZ,
    ADD COLUMN last_error TEXT;

CREATE INDEX ix_order_intent_outbox_unpublished
    ON order_intent_outbox_events (created_at, id)
    WHERE published_at IS NULL AND failed_at IS NULL;

CREATE INDEX ix_order_submission_outbox_unpublished
    ON order_submission_outbox_events (created_at, id)
    WHERE published_at IS NULL AND failed_at IS NULL;
