CREATE TABLE analysis_input_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    symbol VARCHAR(32) NOT NULL CHECK (symbol ~ '^[A-Z0-9._-]+$'),
    schema_version VARCHAR(10) NOT NULL,
    payload JSONB NOT NULL,
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    collected_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_analysis_input_snapshot_owner_id UNIQUE (user_id, id)
);

CREATE INDEX ix_analysis_input_snapshot_latest
    ON analysis_input_snapshots (user_id, symbol, collected_at DESC, id DESC);

CREATE TABLE stock_analysis_runs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    input_snapshot_id UUID,
    symbol VARCHAR(32) NOT NULL CHECK (symbol ~ '^[A-Z0-9._-]+$'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_code VARCHAR(60),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_stock_analysis_run_owner_id UNIQUE (user_id, id),
    CONSTRAINT uq_stock_analysis_run_owner_id_snapshot UNIQUE (user_id, id, input_snapshot_id),
    CONSTRAINT fk_stock_analysis_run_snapshot
        FOREIGN KEY (user_id, input_snapshot_id)
        REFERENCES analysis_input_snapshots (user_id, id),
    CONSTRAINT ck_stock_analysis_run_completion CHECK (
        (status = 'RUNNING' AND completed_at IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND input_snapshot_id IS NOT NULL AND completed_at IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND error_code IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_stock_analysis_run_running
    ON stock_analysis_runs (user_id, symbol)
    WHERE status = 'RUNNING';

CREATE TABLE stock_analysis_results (
    id UUID PRIMARY KEY,
    stock_analysis_run_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    input_snapshot_id UUID NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    result_status VARCHAR(20) NOT NULL CHECK (result_status IN ('COMPLETED', 'DEGRADED')),
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_stock_analysis_result_run
        FOREIGN KEY (user_id, stock_analysis_run_id, input_snapshot_id)
        REFERENCES stock_analysis_runs (user_id, id, input_snapshot_id)
);

CREATE FUNCTION enforce_stock_analysis_input_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'stock analysis input snapshots are append-only';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_stock_analysis_input_append_only
BEFORE INSERT OR UPDATE OR DELETE ON analysis_input_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_stock_analysis_input_immutability();

CREATE FUNCTION enforce_stock_analysis_run_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'stock analysis run has already finished';
    END IF;
    IF NEW.status = 'RUNNING'
        AND NOT (OLD.input_snapshot_id IS NULL AND NEW.input_snapshot_id IS NOT NULL) THEN
        RAISE EXCEPTION 'stock analysis run reservation is immutable';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.user_id <> OLD.user_id
        OR (NEW.input_snapshot_id IS DISTINCT FROM OLD.input_snapshot_id
            AND NOT (OLD.input_snapshot_id IS NULL AND NEW.input_snapshot_id IS NOT NULL))
        OR NEW.symbol <> OLD.symbol
        OR NEW.started_at <> OLD.started_at THEN
        RAISE EXCEPTION 'stock analysis run identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_stock_analysis_run_transition
BEFORE UPDATE OR DELETE ON stock_analysis_runs
FOR EACH ROW EXECUTE FUNCTION enforce_stock_analysis_run_transition();

CREATE FUNCTION enforce_stock_analysis_result_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    run_status VARCHAR(20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'stock analysis results are append-only';
    END IF;
    SELECT status INTO run_status
      FROM stock_analysis_runs
     WHERE id = NEW.stock_analysis_run_id;
    IF run_status <> 'SUCCEEDED' THEN
        RAISE EXCEPTION 'stock analysis results require a successful run';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_stock_analysis_results_append_only
BEFORE INSERT OR UPDATE OR DELETE ON stock_analysis_results
FOR EACH ROW EXECUTE FUNCTION enforce_stock_analysis_result_immutability();
