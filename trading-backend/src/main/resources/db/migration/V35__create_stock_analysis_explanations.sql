ALTER TABLE stock_forecasts
    ADD CONSTRAINT uq_stock_forecast_owner_id UNIQUE (user_id, id);

CREATE TABLE stock_analysis_explanations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    stock_analysis_run_id UUID NOT NULL,
    stock_forecast_id UUID NOT NULL,
    input_snapshot_id UUID NOT NULL,
    symbol VARCHAR(32) NOT NULL CHECK (symbol ~ '^[A-Z0-9._-]+$'),
    schema_version VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('COMPLETED', 'DEGRADED')),
    model_id VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(100) NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    missing_data JSONB NOT NULL DEFAULT '[]',
    citations JSONB NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_stock_analysis_explanation_forecast
        UNIQUE (user_id, stock_forecast_id, model_id, prompt_version),
    CONSTRAINT fk_stock_analysis_explanation_run
        FOREIGN KEY (user_id, stock_analysis_run_id, input_snapshot_id)
        REFERENCES stock_analysis_runs (user_id, id, input_snapshot_id),
    CONSTRAINT fk_stock_analysis_explanation_forecast
        FOREIGN KEY (user_id, stock_forecast_id)
        REFERENCES stock_forecasts (user_id, id)
);

CREATE INDEX ix_stock_analysis_explanations_latest
    ON stock_analysis_explanations (user_id, symbol, created_at DESC, id DESC);

CREATE FUNCTION enforce_stock_analysis_explanation_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'stock analysis explanations are append-only';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_stock_analysis_explanations_append_only
BEFORE INSERT OR UPDATE OR DELETE ON stock_analysis_explanations
FOR EACH ROW EXECUTE FUNCTION enforce_stock_analysis_explanation_immutability();
