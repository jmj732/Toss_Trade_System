-- Append-only review/audit notes. Distinct from investment_decision_ledger: a review record never
-- implies an investment action, so raw values (e.g. action REVIEW, unknown price sessions,
-- non-UUID legacy keys, PORTFOLIO-level notes) are preserved verbatim instead of being coerced.
CREATE TABLE investment_review_records (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    source VARCHAR(32) NOT NULL
        CHECK (source IN ('USER_REST', 'CONNECTOR_MCP', 'SHEET_LEGACY_IMPORT')),
    record_key TEXT NOT NULL CHECK (char_length(record_key) BETWEEN 1 AND 128),
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('SECURITY', 'PORTFOLIO')),
    asset VARCHAR(32),
    as_of TIMESTAMPTZ NOT NULL,
    raw_action TEXT CHECK (raw_action IS NULL OR char_length(raw_action) <= 64),
    outcome TEXT CHECK (outcome IS NULL OR char_length(outcome) <= 2000),
    rationale TEXT CHECK (rationale IS NULL OR char_length(rationale) <= 5000),
    next_review_trigger TEXT CHECK (next_review_trigger IS NULL OR char_length(next_review_trigger) <= 2000),
    reference_price NUMERIC(24, 8) CHECK (reference_price IS NULL OR reference_price > 0),
    raw_price_session TEXT CHECK (raw_price_session IS NULL OR char_length(raw_price_session) <= 64),
    price_session VARCHAR(24),
    source_as_of TIMESTAMPTZ,
    raw_payload JSONB NOT NULL CHECK (jsonb_typeof(raw_payload) = 'object'),
    actor_type VARCHAR(16) NOT NULL CHECK (actor_type IN ('USER_SESSION', 'CONNECTOR_MCP')),
    actor_user_id UUID NOT NULL REFERENCES users (id),
    actor_session_id UUID,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_investment_review_record_key UNIQUE (user_id, source, record_key),
    CONSTRAINT ck_investment_review_scope_asset CHECK (
        (scope = 'SECURITY' AND asset IS NOT NULL AND asset = upper(asset))
        OR (scope = 'PORTFOLIO' AND asset IS NULL)),
    -- price_session is only ever the canonical form of raw_price_session (the service accepts an exact
    -- match after trimming whitespace/control characters and uppercasing), never an alias or a guess.
    CONSTRAINT ck_investment_review_price_session CHECK (
        price_session IS NULL
        OR (price_session IN ('REGULAR_CLOSE', 'LIVE_REGULAR', 'AFTER_HOURS', 'PREMARKET')
            AND raw_price_session IS NOT NULL
            AND strpos(upper(raw_price_session), price_session) > 0)),
    -- Only the connector adapter may write connector-sourced rows, and it always does.
    CONSTRAINT ck_investment_review_source_actor CHECK (
        (source = 'CONNECTOR_MCP') = (actor_type = 'CONNECTOR_MCP'))
);

CREATE INDEX ix_investment_review_records_owner
    ON investment_review_records (user_id, as_of DESC, recorded_at DESC, id DESC);

CREATE TRIGGER trg_investment_review_records_append_only
BEFORE UPDATE OR DELETE ON investment_review_records
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();
