ALTER TABLE risk_policies
    ADD COLUMN soft_risk_budget NUMERIC(8, 4)
        CHECK (soft_risk_budget IS NULL OR soft_risk_budget BETWEEN 0.0001 AND 1);

ALTER TABLE risk_policy_history
    ADD COLUMN soft_risk_budget NUMERIC(8, 4)
        CHECK (soft_risk_budget IS NULL OR soft_risk_budget BETWEEN 0.0001 AND 1);

CREATE TABLE investment_price_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    input_snapshot_id UUID NOT NULL,
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    as_of TIMESTAMPTZ NOT NULL,
    session VARCHAR(24) NOT NULL CHECK (session IN ('REGULAR_CLOSE', 'LIVE_REGULAR', 'AFTER_HOURS', 'PREMARKET')),
    latest_price NUMERIC(24, 8) CHECK (latest_price IS NULL OR latest_price > 0),
    latest_price_as_of TIMESTAMPTZ,
    regular_close NUMERIC(24, 8) CHECK (regular_close IS NULL OR regular_close > 0),
    regular_close_as_of TIMESTAMPTZ,
    source VARCHAR(80) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CHECK (latest_price IS NOT NULL OR regular_close IS NOT NULL),
    CHECK (latest_price IS NULL OR latest_price_as_of IS NOT NULL),
    CONSTRAINT fk_investment_price_input FOREIGN KEY (user_id, input_snapshot_id)
        REFERENCES analysis_input_snapshots (user_id, id),
    CONSTRAINT uq_investment_price_source_observation UNIQUE (user_id, ticker, session, source, as_of)
);

CREATE INDEX ix_investment_price_latest
    ON investment_price_snapshots (user_id, ticker, as_of DESC);

CREATE TABLE fundamental_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    input_snapshot_id UUID NOT NULL,
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    fiscal_period VARCHAR(32) NOT NULL,
    fiscal_year VARCHAR(8),
    fiscal_period_code VARCHAR(16),
    reported_at TIMESTAMPTZ NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    source VARCHAR(80) NOT NULL,
    market_cap NUMERIC(28, 8),
    market_cap_as_of TIMESTAMPTZ,
    enterprise_value NUMERIC(28, 8),
    enterprise_value_as_of TIMESTAMPTZ,
    enterprise_value_source VARCHAR(80),
    cash NUMERIC(28, 8),
    debt NUMERIC(28, 8),
    diluted_shares NUMERIC(28, 8),
    diluted_shares_basis VARCHAR(40),
    revenue_ttm NUMERIC(28, 8),
    revenue_growth_yoy NUMERIC(16, 8),
    ebitda_ttm NUMERIC(28, 8),
    eps NUMERIC(24, 8),
    fcf_ttm NUMERIC(28, 8),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_fundamental_input FOREIGN KEY (user_id, input_snapshot_id)
        REFERENCES analysis_input_snapshots (user_id, id),
    CONSTRAINT uq_fundamental_source_snapshot UNIQUE (user_id, ticker, input_snapshot_id, source)
);

CREATE INDEX ix_fundamental_snapshot_latest
    ON fundamental_snapshots (user_id, ticker, as_of DESC);

CREATE TABLE consensus_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    input_snapshot_id UUID NOT NULL,
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    as_of TIMESTAMPTZ NOT NULL,
    horizon VARCHAR(32) NOT NULL,
    revenue_consensus NUMERIC(28, 8),
    eps_consensus NUMERIC(24, 8),
    ebitda_consensus NUMERIC(28, 8),
    fcf_consensus NUMERIC(28, 8),
    source VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_consensus_input FOREIGN KEY (user_id, input_snapshot_id)
        REFERENCES analysis_input_snapshots (user_id, id),
    CONSTRAINT uq_consensus_source_snapshot UNIQUE (user_id, ticker, as_of, horizon, source)
);

CREATE INDEX ix_consensus_snapshot_history
    ON consensus_snapshots (user_id, ticker, horizon, as_of DESC);

CREATE TABLE investment_security_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    as_of TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_investment_security_snapshot UNIQUE (user_id, ticker, as_of)
);

CREATE INDEX ix_investment_security_latest
    ON investment_security_snapshots (user_id, ticker, as_of DESC);

CREATE TABLE investment_thesis_states (
    user_id UUID NOT NULL REFERENCES users (id),
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    core_thesis TEXT NOT NULL,
    upside_driver TEXT,
    expectations_gap TEXT,
    fundamental_invalidation TEXT,
    revision_invalidation TEXT,
    price_risk_trigger TEXT,
    price_risk_trigger_price NUMERIC(24, 8),
    invalidation_status VARCHAR(24) NOT NULL
        CHECK (invalidation_status IN ('NOT_REVIEWED', 'SUSPECTED', 'CONFIRMED', 'CLEARED')),
    expand_trigger TEXT,
    exit_or_discard_trigger TEXT,
    classification VARCHAR(80),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, ticker)
);

CREATE TABLE investment_decision_ledger (
    decision_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    as_of TIMESTAMPTZ NOT NULL,
    asset VARCHAR(32) NOT NULL CHECK (asset = upper(asset)),
    action VARCHAR(16) NOT NULL CHECK (action IN ('ADD', 'HOLD', 'REDUCE', 'EXIT', 'REPLACE')),
    reference_price NUMERIC(24, 8) CHECK (reference_price IS NULL OR reference_price > 0),
    price_session VARCHAR(24)
        CHECK (price_session IS NULL OR price_session IN ('REGULAR_CLOSE', 'LIVE_REGULAR', 'AFTER_HOURS', 'PREMARKET')),
    horizon VARCHAR(80),
    alpha_thesis TEXT,
    invalidation TEXT,
    next_review_trigger TEXT,
    confidence NUMERIC(8, 6) CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    risk_policy_check JSONB NOT NULL CHECK (jsonb_typeof(risk_policy_check) = 'object'),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_investment_decision_ledger_owner
    ON investment_decision_ledger (user_id, as_of DESC, decision_id);

CREATE TABLE investment_pipeline_state (
    user_id UUID NOT NULL REFERENCES users (id),
    pipeline VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    last_attempt_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    last_error VARCHAR(240),
    PRIMARY KEY (user_id, pipeline),
    CHECK ((status = 'SUCCEEDED' AND last_success_at IS NOT NULL AND last_error IS NULL)
        OR status = 'RUNNING'
        OR (status = 'FAILED' AND last_error IS NOT NULL))
);

CREATE FUNCTION reject_investment_snapshot_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_investment_price_snapshot_append_only
BEFORE UPDATE OR DELETE ON investment_price_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();

CREATE TRIGGER trg_fundamental_snapshot_append_only
BEFORE UPDATE OR DELETE ON fundamental_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();

CREATE TRIGGER trg_consensus_snapshot_append_only
BEFORE UPDATE OR DELETE ON consensus_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();

CREATE TRIGGER trg_investment_security_snapshot_append_only
BEFORE UPDATE OR DELETE ON investment_security_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();

CREATE TRIGGER trg_investment_decision_ledger_append_only
BEFORE UPDATE OR DELETE ON investment_decision_ledger
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();
