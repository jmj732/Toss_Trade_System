DROP TRIGGER trg_enforce_analysis_prediction_outcome_immutability
    ON analysis_prediction_outcomes;

ALTER TABLE analysis_prediction_outcomes
    ADD COLUMN target_due_at TIMESTAMPTZ,
    ADD COLUMN observation_time TIMESTAMPTZ,
    ADD COLUMN lag_ms BIGINT;

UPDATE analysis_prediction_outcomes outcome
   SET target_due_at = prediction.predicted_at
           + CASE outcome.horizon
                 WHEN 'D1' THEN INTERVAL '1 day'
                 WHEN 'D5' THEN INTERVAL '5 days'
                 WHEN 'D20' THEN INTERVAL '20 days'
             END,
       observation_time = outcome.observed_at
  FROM analysis_predictions prediction
 WHERE prediction.id = outcome.prediction_id;

UPDATE analysis_prediction_outcomes
   SET lag_ms = GREATEST(
           0,
           FLOOR(EXTRACT(EPOCH FROM (observation_time - target_due_at)) * 1000)::BIGINT);

ALTER TABLE analysis_prediction_outcomes
    ALTER COLUMN target_due_at SET NOT NULL,
    ALTER COLUMN observation_time SET NOT NULL,
    ALTER COLUMN lag_ms SET NOT NULL,
    ADD CONSTRAINT ck_analysis_prediction_outcome_lag CHECK (lag_ms >= 0),
    DROP COLUMN observed_at;

CREATE TRIGGER trg_enforce_analysis_prediction_outcome_immutability
BEFORE INSERT OR UPDATE OR DELETE ON analysis_prediction_outcomes
FOR EACH ROW
EXECUTE FUNCTION enforce_analysis_prediction_outcome_immutability();

CREATE TABLE prediction_evaluation_leases (
    name VARCHAR(40) PRIMARY KEY,
    owner UUID NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_prediction_evaluation_lease_window CHECK (expires_at > acquired_at)
);
