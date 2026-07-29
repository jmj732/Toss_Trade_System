CREATE TABLE prediction_model_versions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    model_version VARCHAR(50) NOT NULL,
    contract_version VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'DEPRECATED')),
    created_at TIMESTAMPTZ NOT NULL,
    deprecated_at TIMESTAMPTZ,
    CONSTRAINT uq_prediction_model_version
        UNIQUE (user_id, model_version, contract_version),
    CONSTRAINT ck_prediction_model_version_deprecation CHECK (
        (status = 'ACTIVE' AND deprecated_at IS NULL)
        OR (status = 'DEPRECATED' AND deprecated_at IS NOT NULL)
    )
);

INSERT INTO prediction_model_versions (
    id, user_id, model_version, contract_version, status, created_at
)
SELECT gen_random_uuid(),
       user_id,
       model_version,
       contract_version,
       'ACTIVE',
       MIN(created_at)
  FROM analysis_predictions
 GROUP BY user_id, model_version, contract_version;

CREATE INDEX ix_analysis_predictions_user_model_contract
    ON analysis_predictions (user_id, model_version, contract_version);

ALTER TABLE analysis_predictions
    ADD CONSTRAINT fk_analysis_prediction_model_version
    FOREIGN KEY (user_id, model_version, contract_version)
    REFERENCES prediction_model_versions (user_id, model_version, contract_version)
    ON DELETE RESTRICT;

CREATE FUNCTION enforce_prediction_model_version_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.model_version IS DISTINCT FROM OLD.model_version
        OR NEW.contract_version IS DISTINCT FROM OLD.contract_version
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'prediction model version identity is immutable';
    END IF;

    IF NEW.status IS NOT DISTINCT FROM OLD.status
        AND NEW.deprecated_at IS NOT DISTINCT FROM OLD.deprecated_at THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'ACTIVE'
        AND NEW.status = 'DEPRECATED'
        AND OLD.deprecated_at IS NULL
        AND NEW.deprecated_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'prediction model version status is immutable after deprecation';
END;
$$;

CREATE TRIGGER trg_enforce_prediction_model_version_immutability
BEFORE UPDATE ON prediction_model_versions
FOR EACH ROW
EXECUTE FUNCTION enforce_prediction_model_version_immutability();
