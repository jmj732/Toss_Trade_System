-- Append-only thesis revision audit trail: records every thesis write with actor details, previous state, and reason.
CREATE TABLE investment_thesis_revisions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ticker text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    previous_status text,
    new_status text NOT NULL CHECK (new_status IN (
        'NOT_REVIEWED', 'SUSPECTED', 'CONFIRMED', 'CLEARED',
        'AI_PROPOSED', 'UNVERIFIED', 'INVALIDATION_UNDEFINED'
    )),
    previous_trigger_price numeric,
    new_trigger_price numeric,
    thesis_snapshot jsonb NOT NULL,
    actor_type text NOT NULL CHECK (actor_type IN ('USER_SESSION', 'CONNECTOR_MCP')),
    actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_session_id uuid,
    source_as_of timestamptz,
    reason text CHECK (char_length(reason) <= 2000),
    recorded_at timestamptz NOT NULL,
    UNIQUE(user_id, ticker, revision)
);

CREATE INDEX idx_investment_thesis_revisions_user_ticker
    ON investment_thesis_revisions(user_id, ticker, revision DESC);
