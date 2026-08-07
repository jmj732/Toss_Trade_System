-- D-42: record when a proposal was created and when it expires.
-- Both columns are nullable and existing rows are deliberately left NULL: a synthesized
-- creation time would be a false assertion about order history. Legacy rows keep NULL and
-- behave exactly as before (no expiry enforcement).
ALTER TABLE order_intents
    ADD COLUMN created_at TIMESTAMPTZ,
    ADD COLUMN expires_at TIMESTAMPTZ;
