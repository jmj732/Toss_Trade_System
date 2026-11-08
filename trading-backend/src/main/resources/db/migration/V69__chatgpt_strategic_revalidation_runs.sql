ALTER TABLE connector_api_keys
    DROP CONSTRAINT ck_connector_api_key_scope,
    ADD CONSTRAINT ck_connector_api_key_scope
        CHECK (scope IN ('connector:read', 'connector:review', 'connector:trade'));

ALTER TABLE connector_oauth_refresh_tokens
    DROP CONSTRAINT connector_oauth_refresh_tokens_scope_check,
    ADD CONSTRAINT ck_connector_oauth_refresh_scope
        CHECK (scope IN ('connector:read', 'connector:review', 'connector:trade'));

CREATE TABLE investment_strategic_buy_revalidation_runs (
    run_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    connection_id UUID NOT NULL,
    api_key_id UUID NOT NULL REFERENCES connector_api_keys (id),
    decision_id UUID NOT NULL,
    ticker VARCHAR(32) NOT NULL,
    thesis_revision_id UUID NOT NULL,
    mandate_updated_at TIMESTAMPTZ NOT NULL,
    reviewed_market_price NUMERIC(20, 8) NOT NULL,
    market_observed_at TIMESTAMPTZ NOT NULL,
    market_health VARCHAR(24) NOT NULL,
    price_risk_state VARCHAR(24) NOT NULL,
    challenge_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_strategic_revalidation_run_status
        CHECK (status IN ('OPEN', 'COMPLETED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_strategic_revalidation_run_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_strategic_revalidation_run_completion
        CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX ix_strategic_revalidation_run_expiry
    ON investment_strategic_buy_revalidation_runs (expires_at)
    WHERE status = 'OPEN';

ALTER TABLE investment_strategic_buy_revalidations
    ADD COLUMN run_id UUID UNIQUE
        REFERENCES investment_strategic_buy_revalidation_runs (run_id),
    DROP CONSTRAINT ck_buy_revalidation_review_origin,
    ADD CONSTRAINT ck_buy_revalidation_review_origin
        CHECK (review_origin IN ('CALLER_ASSERTED', 'CHATGPT_MCP', 'SERVER_GENERATED'));

COMMENT ON COLUMN investment_strategic_buy_revalidations.review_origin IS
    'CALLER_ASSERTED cannot authorize submission. CHATGPT_MCP requires a completed server-issued review run. SERVER_GENERATED is reserved for a server-managed reviewer.';
