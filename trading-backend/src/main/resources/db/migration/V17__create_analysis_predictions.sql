CREATE TABLE analysis_predictions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    symbol VARCHAR(30) NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('KRW', 'USD')),
    predicted_direction VARCHAR(5) NOT NULL CHECK (predicted_direction IN ('UP', 'DOWN')),
    model_version VARCHAR(50) NOT NULL,
    contract_version VARCHAR(50) NOT NULL,
    baseline_price NUMERIC(28, 10) NOT NULL CHECK (baseline_price > 0),
    predicted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_analysis_prediction_connection
        FOREIGN KEY (user_id, broker_connection_id)
        REFERENCES broker_connections (user_id, id)
);

CREATE INDEX ix_analysis_predictions_user_connection
    ON analysis_predictions (user_id, broker_connection_id);

CREATE TABLE analysis_prediction_outcomes (
    id UUID PRIMARY KEY,
    prediction_id UUID NOT NULL REFERENCES analysis_predictions (id),
    horizon VARCHAR(5) NOT NULL CHECK (horizon IN ('D1', 'D5', 'D20')),
    price NUMERIC(28, 10) NOT NULL CHECK (price > 0),
    actual_return NUMERIC(28, 10) NOT NULL,
    direction_correct BOOLEAN NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (prediction_id, horizon)
);

CREATE INDEX ix_analysis_prediction_outcomes_prediction
    ON analysis_prediction_outcomes (prediction_id);

-- Append-only: a horizon's grade is permanent once written, matching the
-- execution_snapshots/risk_policy_history convention elsewhere in this schema.
CREATE FUNCTION enforce_analysis_prediction_outcome_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'analysis prediction outcomes are append-only';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_analysis_prediction_outcome_immutability
BEFORE INSERT OR UPDATE OR DELETE ON analysis_prediction_outcomes
FOR EACH ROW
EXECUTE FUNCTION enforce_analysis_prediction_outcome_immutability();
