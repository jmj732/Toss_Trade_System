CREATE TABLE investment_os_portfolio_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    attempt_status VARCHAR(16) NOT NULL CHECK (attempt_status IN ('SUCCEEDED', 'PARTIAL', 'FAILED')),
    attempted_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(120),
    payload JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((attempt_status = 'FAILED' AND payload IS NULL)
        OR (attempt_status IN ('SUCCEEDED', 'PARTIAL')
            AND payload IS NOT NULL AND jsonb_typeof(payload) = 'object'))
);

CREATE INDEX ix_investment_os_portfolio_snapshots_latest
    ON investment_os_portfolio_snapshots (user_id, attempted_at DESC, id DESC);

CREATE FUNCTION reject_investment_os_portfolio_snapshot_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'investment_os_portfolio_snapshots is append-only';
END;
$$;

CREATE TRIGGER trg_investment_os_portfolio_snapshots_append_only
BEFORE UPDATE OR DELETE ON investment_os_portfolio_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_investment_os_portfolio_snapshot_change();
