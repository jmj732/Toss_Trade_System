CREATE TABLE production_readiness_checks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    symbol VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (
        status IN ('HEALTHY', 'DEGRADED', 'STALE', 'UNAVAILABLE', 'SECRET_MISSING', 'NOT_CONFIGURED')
    ),
    degraded BOOLEAN NOT NULL,
    max_lag_ms BIGINT CHECK (max_lag_ms IS NULL OR max_lag_ms >= 0),
    evidence JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_production_readiness_user_latest
    ON production_readiness_checks (user_id, created_at DESC, id DESC);

CREATE FUNCTION prevent_production_readiness_check_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'production readiness evidence is append-only';
END;
$$;

CREATE TRIGGER trg_production_readiness_check_immutable
BEFORE UPDATE OR DELETE ON production_readiness_checks
FOR EACH ROW
EXECUTE FUNCTION prevent_production_readiness_check_mutation();
