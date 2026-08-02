CREATE TABLE stock_forecasts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    stock_analysis_run_id UUID NOT NULL,
    input_snapshot_id UUID NOT NULL,
    symbol VARCHAR(32) NOT NULL CHECK (symbol ~ '^[A-Z0-9._-]+$'),
    schema_version VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('COMPLETED', 'DEGRADED')),
    model_version VARCHAR(50) NOT NULL,
    contract_version VARCHAR(50) NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    confidence NUMERIC(12, 10) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    missing_data JSONB NOT NULL DEFAULT '[]',
    response JSONB NOT NULL,
    prediction_id UUID REFERENCES analysis_predictions (id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_stock_forecast_snapshot_version
        UNIQUE (user_id, input_snapshot_id, model_version, contract_version),
    CONSTRAINT fk_stock_forecast_run
        FOREIGN KEY (user_id, stock_analysis_run_id, input_snapshot_id)
        REFERENCES stock_analysis_runs (user_id, id, input_snapshot_id)
);

CREATE INDEX ix_stock_forecasts_latest
    ON stock_forecasts (user_id, symbol, created_at DESC, id DESC);

CREATE FUNCTION enforce_stock_forecast_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'stock forecasts are append-only';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_stock_forecasts_append_only
BEFORE INSERT OR UPDATE OR DELETE ON stock_forecasts
FOR EACH ROW EXECUTE FUNCTION enforce_stock_forecast_immutability();
