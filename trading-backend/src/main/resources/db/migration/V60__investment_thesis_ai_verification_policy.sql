-- External AI verification evidence and scoped, user-visible autoapproval policy.
-- The evidence and policy tables are append-only; Spring stores caller-supplied evidence
-- but does not produce an AI verdict or choose a trigger.

CREATE TABLE investment_thesis_ai_policy_revisions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    revision BIGSERIAL NOT NULL,
    enabled BOOLEAN NOT NULL,
    policy_version VARCHAR(80) NOT NULL CHECK (length(btrim(policy_version)) > 0),
    max_evidence_age_seconds INTEGER NOT NULL CHECK (max_evidence_age_seconds BETWEEN 60 AND 604800),
    minimum_source_count SMALLINT NOT NULL CHECK (minimum_source_count BETWEEN 1 AND 10),
    max_trigger_below_price_pct NUMERIC(8, 6) NOT NULL
        CHECK (max_trigger_below_price_pct BETWEEN 0 AND 0.500000),
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, revision),
    UNIQUE (user_id, id)
);

CREATE INDEX ix_thesis_ai_policy_latest
    ON investment_thesis_ai_policy_revisions(user_id, revision DESC);

ALTER TABLE investment_thesis_revisions
    ADD CONSTRAINT uq_thesis_revision_owner_ticker_id UNIQUE (id, user_id, ticker),
    ADD COLUMN policy_version VARCHAR(80),
    ADD COLUMN verification_event_id UUID,
    ADD COLUMN asserted_run_id VARCHAR(160);

CREATE TABLE investment_thesis_ai_verification_events (
    verification_event_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    proposal_revision_id UUID NOT NULL,
    proposal_run_id VARCHAR(160),
    verification_run_id VARCHAR(160) NOT NULL CHECK (length(btrim(verification_run_id)) > 0),
    provider VARCHAR(120) NOT NULL CHECK (length(btrim(provider)) > 0),
    model VARCHAR(160) NOT NULL CHECK (length(btrim(model)) > 0),
    verdict VARCHAR(16) NOT NULL CHECK (verdict IN ('PASS', 'REJECT')),
    source_as_of TIMESTAMPTZ NOT NULL,
    source_urls JSONB NOT NULL CHECK (jsonb_typeof(source_urls) = 'array'),
    rationale TEXT NOT NULL CHECK (length(btrim(rationale)) > 0 AND char_length(rationale) <= 4000),
    counterevidence TEXT NOT NULL CHECK (length(btrim(counterevidence)) > 0 AND char_length(counterevidence) <= 4000),
    selected_trigger_price NUMERIC(24, 8) CHECK (selected_trigger_price IS NULL OR selected_trigger_price > 0),
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('AUTO_APPROVED', 'BLOCKED', 'REJECTED')),
    reason_code VARCHAR(64) NOT NULL,
    policy_version VARCHAR(80) NOT NULL,
    policy_snapshot JSONB NOT NULL CHECK (jsonb_typeof(policy_snapshot) = 'object'),
    evaluation_snapshot JSONB NOT NULL CHECK (jsonb_typeof(evaluation_snapshot) = 'object'),
    authenticated_actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    authenticated_actor_key_id UUID,
    body_hash CHAR(64) NOT NULL CHECK (body_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_thesis_ai_verification_proposal_revision
        FOREIGN KEY (proposal_revision_id, user_id, ticker)
        REFERENCES investment_thesis_revisions(id, user_id, ticker),
    UNIQUE (user_id, verification_event_id),
    UNIQUE (user_id, ticker, verification_event_id)
);

CREATE INDEX ix_thesis_ai_verification_revision
    ON investment_thesis_ai_verification_events(user_id, ticker, proposal_revision_id, created_at DESC);

ALTER TABLE investment_thesis_revisions
    DROP CONSTRAINT investment_thesis_revisions_actor_type_check;

ALTER TABLE investment_thesis_revisions
    ADD CONSTRAINT investment_thesis_revisions_actor_type_check
        CHECK (actor_type IN ('USER_SESSION', 'CONNECTOR_MCP', 'TELEGRAM', 'AI_POLICY')),
    ADD CONSTRAINT investment_thesis_revisions_ai_policy_provenance_check
        CHECK ((actor_type = 'AI_POLICY' AND policy_version IS NOT NULL AND verification_event_id IS NOT NULL
                    AND source_as_of IS NOT NULL)
            OR (actor_type <> 'AI_POLICY' AND policy_version IS NULL AND verification_event_id IS NULL)),
    ADD CONSTRAINT fk_thesis_revision_verification_event
        FOREIGN KEY (user_id, ticker, verification_event_id)
        REFERENCES investment_thesis_ai_verification_events(user_id, ticker, verification_event_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TRIGGER trg_thesis_ai_policy_revisions_append_only
BEFORE UPDATE OR DELETE ON investment_thesis_ai_policy_revisions
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();

CREATE TRIGGER trg_thesis_ai_verification_events_append_only
BEFORE UPDATE OR DELETE ON investment_thesis_ai_verification_events
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();
