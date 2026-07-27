CREATE TABLE analysis_runs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    input_sync_run_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_code VARCHAR(60),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_analysis_run_connection
        FOREIGN KEY (user_id, broker_connection_id)
        REFERENCES broker_connections (user_id, id),
    CONSTRAINT fk_analysis_run_input
        FOREIGN KEY (user_id, broker_connection_id, input_sync_run_id)
        REFERENCES account_sync_runs (user_id, broker_connection_id, id),
    CONSTRAINT ck_analysis_run_completion CHECK (
        (status = 'RUNNING' AND completed_at IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND error_code IS NOT NULL)
    ),
    CONSTRAINT uq_analysis_run_owner_id
        UNIQUE (user_id, broker_connection_id, id)
);

CREATE UNIQUE INDEX uq_analysis_run_running
    ON analysis_runs (user_id, broker_connection_id)
    WHERE status = 'RUNNING';

CREATE INDEX ix_analysis_run_latest_success
    ON analysis_runs (user_id, broker_connection_id, completed_at DESC, id DESC)
    WHERE status = 'SUCCEEDED';

CREATE TABLE analysis_results (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    input_sync_run_id UUID NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    result_status VARCHAR(20) NOT NULL CHECK (result_status IN ('COMPLETED', 'DEGRADED')),
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_analysis_result_run
        FOREIGN KEY (user_id, broker_connection_id, analysis_run_id)
        REFERENCES analysis_runs (user_id, broker_connection_id, id),
    CONSTRAINT fk_analysis_result_input
        FOREIGN KEY (user_id, broker_connection_id, input_sync_run_id)
        REFERENCES account_sync_runs (user_id, broker_connection_id, id)
);

CREATE FUNCTION enforce_analysis_run_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'analysis runs are append-only';
    END IF;
    IF OLD.status <> 'RUNNING' OR NEW.status = 'RUNNING' THEN
        RAISE EXCEPTION 'analysis run has already finished';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.user_id <> OLD.user_id
        OR NEW.broker_connection_id <> OLD.broker_connection_id
        OR NEW.input_sync_run_id <> OLD.input_sync_run_id
        OR NEW.started_at <> OLD.started_at THEN
        RAISE EXCEPTION 'analysis run identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_analysis_run_transition
BEFORE UPDATE OR DELETE ON analysis_runs
FOR EACH ROW
EXECUTE FUNCTION enforce_analysis_run_transition();

CREATE FUNCTION enforce_analysis_result_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    run_status VARCHAR(20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'analysis results are append-only';
    END IF;
    SELECT status INTO run_status
      FROM analysis_runs
     WHERE id = NEW.analysis_run_id;
    IF run_status <> 'SUCCEEDED' THEN
        RAISE EXCEPTION 'analysis results require a successful run';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_analysis_results_append_only
BEFORE INSERT OR UPDATE OR DELETE ON analysis_results
FOR EACH ROW
EXECUTE FUNCTION enforce_analysis_result_immutability();
