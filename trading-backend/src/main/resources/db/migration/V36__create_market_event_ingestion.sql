ALTER TABLE intelligence_events
    DROP CONSTRAINT IF EXISTS intelligence_events_affected_symbols_check;

ALTER TABLE intelligence_events
    ADD COLUMN macro_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_intelligence_event_symbols_array CHECK (
        jsonb_typeof(affected_symbols) = 'array'
    ),
    ADD CONSTRAINT ck_intelligence_event_macro_scope_array CHECK (
        jsonb_typeof(macro_scope) = 'array'
    );

CREATE TABLE market_event_ingestion_leases (
    name VARCHAR(40) PRIMARY KEY,
    owner UUID NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_market_event_ingestion_lease_window CHECK (expires_at > acquired_at)
);

CREATE TABLE market_event_ingestion_runs (
    id UUID PRIMARY KEY,
    provider VARCHAR(20) NOT NULL CHECK (provider IN ('SEC', 'IR', 'FED', 'FRED', 'BLS', 'BEA')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    attempt INTEGER NOT NULL CHECK (attempt >= 1),
    requested_since TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    collected_events INTEGER NOT NULL DEFAULT 0 CHECK (collected_events >= 0),
    last_error VARCHAR(1000),
    CONSTRAINT ck_market_event_ingestion_run_state CHECK (
        (status = 'RUNNING' AND completed_at IS NULL AND next_retry_at IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND next_retry_at IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND next_retry_at IS NOT NULL)
    )
);

CREATE FUNCTION enforce_market_event_ingestion_run_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id
        OR NEW.provider <> OLD.provider
        OR NEW.requested_since <> OLD.requested_since
        OR NEW.started_at <> OLD.started_at THEN
        RAISE EXCEPTION 'market event ingestion run identity is immutable';
    END IF;
    IF OLD.status = 'SUCCEEDED' THEN
        RAISE EXCEPTION 'succeeded market event ingestion run is terminal';
    END IF;
    IF OLD.status = 'FAILED'
        AND (NEW.status <> 'FAILED' OR NEW.attempt <> 1) THEN
        RAISE EXCEPTION 'failed market event ingestion run requires explicit reprocessing';
    END IF;
    IF OLD.status = 'RUNNING' AND NEW.status NOT IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'running market event ingestion run has invalid transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_market_event_ingestion_run_transition
BEFORE UPDATE ON market_event_ingestion_runs
FOR EACH ROW
EXECUTE FUNCTION enforce_market_event_ingestion_run_transition();

CREATE INDEX ix_market_event_ingestion_retry
    ON market_event_ingestion_runs (provider, next_retry_at)
    WHERE status = 'FAILED';

CREATE INDEX ix_market_event_ingestion_latest
    ON market_event_ingestion_runs (provider, started_at DESC);
