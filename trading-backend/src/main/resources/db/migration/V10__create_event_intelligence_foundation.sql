CREATE TABLE intelligence_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    source VARCHAR(80) NOT NULL,
    source_event_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    affected_symbols JSONB NOT NULL CHECK (
        jsonb_typeof(affected_symbols) = 'array'
        AND jsonb_array_length(affected_symbols) > 0
    ),
    occurred_at TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_intelligence_event_connection
        FOREIGN KEY (user_id, broker_connection_id)
        REFERENCES broker_connections (user_id, id),
    CONSTRAINT uq_intelligence_event_source
        UNIQUE (user_id, broker_connection_id, source, source_event_id),
    CONSTRAINT uq_intelligence_event_owner_id
        UNIQUE (user_id, broker_connection_id, id)
);

CREATE INDEX ix_intelligence_event_latest
    ON intelligence_events (user_id, broker_connection_id, collected_at DESC, id DESC);

ALTER TABLE analysis_runs
    ADD COLUMN trigger_event_id UUID,
    ADD COLUMN previous_analysis_run_id UUID,
    ADD CONSTRAINT fk_analysis_run_trigger_event
        FOREIGN KEY (user_id, broker_connection_id, trigger_event_id)
        REFERENCES intelligence_events (user_id, broker_connection_id, id),
    ADD CONSTRAINT fk_analysis_run_previous
        FOREIGN KEY (user_id, broker_connection_id, previous_analysis_run_id)
        REFERENCES analysis_runs (user_id, broker_connection_id, id);

CREATE UNIQUE INDEX uq_analysis_run_trigger_event
    ON analysis_runs (trigger_event_id)
    WHERE trigger_event_id IS NOT NULL AND status IN ('RUNNING', 'SUCCEEDED');

CREATE OR REPLACE FUNCTION enforce_analysis_run_transition()
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
        OR NEW.trigger_event_id IS DISTINCT FROM OLD.trigger_event_id
        OR NEW.previous_analysis_run_id IS DISTINCT FROM OLD.previous_analysis_run_id
        OR NEW.started_at <> OLD.started_at THEN
        RAISE EXCEPTION 'analysis run identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE event_analysis_comparisons (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    previous_analysis_run_id UUID,
    new_analysis_run_id UUID NOT NULL UNIQUE,
    comparison JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_event_comparison_event
        FOREIGN KEY (user_id, broker_connection_id, event_id)
        REFERENCES intelligence_events (user_id, broker_connection_id, id),
    CONSTRAINT fk_event_comparison_previous
        FOREIGN KEY (user_id, broker_connection_id, previous_analysis_run_id)
        REFERENCES analysis_runs (user_id, broker_connection_id, id),
    CONSTRAINT fk_event_comparison_new
        FOREIGN KEY (user_id, broker_connection_id, new_analysis_run_id)
        REFERENCES analysis_runs (user_id, broker_connection_id, id)
);

CREATE FUNCTION enforce_intelligence_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'event intelligence is append-only';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_intelligence_events_append_only
BEFORE UPDATE OR DELETE ON intelligence_events
FOR EACH ROW
EXECUTE FUNCTION enforce_intelligence_append_only();

CREATE TRIGGER trg_event_comparisons_append_only
BEFORE UPDATE OR DELETE ON event_analysis_comparisons
FOR EACH ROW
EXECUTE FUNCTION enforce_intelligence_append_only();
