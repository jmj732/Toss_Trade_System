ALTER TABLE fundamental_snapshots
    ADD COLUMN basic_shares NUMERIC(28, 8),
    ADD COLUMN basic_shares_basis VARCHAR(80),
    ADD COLUMN market_cap_formula TEXT,
    ADD COLUMN fully_diluted_market_cap NUMERIC(28, 8),
    ADD COLUMN fully_diluted_market_cap_as_of TIMESTAMPTZ,
    ADD COLUMN fully_diluted_market_cap_formula TEXT,
    ADD COLUMN balance_sheet_as_of TIMESTAMPTZ,
    ADD COLUMN currency VARCHAR(12),
    ADD COLUMN ebitda_ttm_type VARCHAR(80),
    ADD COLUMN ebitda_ttm_formula TEXT,
    ADD COLUMN ebitda_ttm_source TEXT,
    ADD COLUMN field_provenance JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(field_provenance) = 'object');

ALTER TABLE consensus_snapshots
    ADD COLUMN estimate_type VARCHAR(32) NOT NULL DEFAULT 'ANNUAL',
    ADD COLUMN estimate_label VARCHAR(80),
    ADD COLUMN period_end DATE,
    ADD COLUMN eps_analyst_count INTEGER CHECK (eps_analyst_count IS NULL OR eps_analyst_count >= 0),
    ADD COLUMN revenue_analyst_count INTEGER CHECK (revenue_analyst_count IS NULL OR revenue_analyst_count >= 0),
    ADD COLUMN currency VARCHAR(12);

ALTER TABLE consensus_snapshots
    DROP CONSTRAINT uq_consensus_source_snapshot,
    ADD CONSTRAINT uq_consensus_source_snapshot UNIQUE
        (user_id, ticker, as_of, horizon, estimate_type, source);

DROP INDEX ix_consensus_snapshot_history;
CREATE INDEX ix_consensus_snapshot_history
    ON consensus_snapshots (user_id, ticker, source, horizon, estimate_type, as_of DESC);

ALTER TABLE investment_pipeline_state
    DROP CONSTRAINT investment_pipeline_state_status_check,
    ADD CONSTRAINT investment_pipeline_state_status_check
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    DROP CONSTRAINT investment_pipeline_state_check,
    ADD CONSTRAINT investment_pipeline_state_check
        CHECK ((status = 'SUCCEEDED' AND last_success_at IS NOT NULL AND last_error IS NULL)
            OR (status = 'PARTIAL' AND last_success_at IS NOT NULL)
            OR status = 'RUNNING'
            OR (status = 'FAILED' AND last_error IS NOT NULL));

CREATE TABLE alpha_vantage_daily_cache (
    request_date DATE NOT NULL,
    credential_fingerprint CHAR(64) NOT NULL CHECK (credential_fingerprint ~ '^[0-9a-f]{64}$'),
    symbol VARCHAR(32) NOT NULL CHECK (symbol = upper(symbol)),
    function VARCHAR(32) NOT NULL,
    state VARCHAR(12) NOT NULL CHECK (state IN ('REQUESTED', 'SUCCEEDED', 'FAILED')),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'array'),
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (request_date, credential_fingerprint, symbol, function),
    CHECK ((state = 'REQUESTED' AND completed_at IS NULL)
        OR (state <> 'REQUESTED' AND completed_at IS NOT NULL))
);

CREATE INDEX ix_alpha_vantage_daily_cache_usage
    ON alpha_vantage_daily_cache (request_date, credential_fingerprint);
