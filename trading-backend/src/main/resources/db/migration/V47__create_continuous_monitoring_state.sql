CREATE TABLE monitoring_watchlist (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    symbol VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'WATCH'
        CHECK (status IN ('WATCH', 'PREPARE', 'ACTION_CANDIDATE', 'INVALIDATED')),
    levels JSONB NOT NULL CHECK (
        jsonb_typeof(levels) = 'object'
        AND levels ?& ARRAY['prepare', 'confirm', 'pullback', 'invalidate']
    ),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(evidence) = 'object'),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_monitoring_watchlist_symbol CHECK (symbol = upper(symbol)),
    CONSTRAINT uq_monitoring_watchlist_user_symbol UNIQUE (user_id, symbol)
);

CREATE TABLE monitoring_current_states (
    user_id UUID NOT NULL REFERENCES users (id),
    scope VARCHAR(24) NOT NULL CHECK (scope IN ('MARKET', 'PORTFOLIO', 'WATCHLIST', 'EVENT')),
    subject_key VARCHAR(128) NOT NULL,
    state VARCHAR(40) NOT NULL,
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    observed_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, scope, subject_key)
);

CREATE TABLE monitoring_state_history (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    scope VARCHAR(24) NOT NULL CHECK (scope IN ('MARKET', 'PORTFOLIO', 'WATCHLIST', 'EVENT')),
    subject_key VARCHAR(128) NOT NULL,
    previous_state VARCHAR(40),
    new_state VARCHAR(40) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_event_id VARCHAR(240),
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_monitoring_state_source_event
        UNIQUE (user_id, scope, source_type, source_event_id)
);

CREATE INDEX ix_monitoring_state_history_owner
    ON monitoring_state_history (user_id, scope, subject_key, observed_at DESC);

CREATE TABLE monitoring_position_contexts (
    user_id UUID NOT NULL REFERENCES users (id),
    symbol VARCHAR(32) NOT NULL,
    sector VARCHAR(100),
    factor VARCHAR(100),
    beta NUMERIC(16, 8),
    correlation NUMERIC(16, 8) CHECK (correlation IS NULL OR correlation BETWEEN -1 AND 1),
    thesis TEXT,
    primary_alpha TEXT,
    conditions JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(conditions) = 'object'),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_monitoring_position_context_symbol CHECK (symbol = upper(symbol)),
    PRIMARY KEY (user_id, symbol)
);

CREATE TABLE monitoring_evaluation_cursors (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    request_hash CHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE monitoring_event_cursors (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    baseline_collected_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE monitoring_market_observations (
    user_id UUID NOT NULL REFERENCES users (id),
    metric VARCHAR(100) NOT NULL,
    source VARCHAR(160) NOT NULL,
    unit VARCHAR(40) NOT NULL,
    cadence VARCHAR(16) NOT NULL CHECK (cadence IN ('INTRADAY', 'DAILY', 'WEEKLY', 'MONTHLY')),
    value NUMERIC,
    as_of TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, metric, as_of)
);

CREATE INDEX ix_monitoring_market_observations_latest
    ON monitoring_market_observations (user_id, metric, as_of DESC);

CREATE FUNCTION reject_monitoring_state_history_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'monitoring state history is append-only';
END;
$$;

CREATE TRIGGER trg_monitoring_state_history_append_only
BEFORE UPDATE OR DELETE ON monitoring_state_history
FOR EACH ROW
EXECUTE FUNCTION reject_monitoring_state_history_change();
