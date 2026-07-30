CREATE TABLE prediction_ingestion_api_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    model_version VARCHAR(50) NOT NULL,
    contract_version VARCHAR(50) NOT NULL,
    key_hash CHAR(64) NOT NULL UNIQUE
        CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    key_prefix VARCHAR(13) NOT NULL
        CHECK (key_prefix ~ '^tpik_[A-Za-z0-9_-]{8}$'),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_prediction_ingestion_api_key_scope
        FOREIGN KEY (user_id, model_version, contract_version)
        REFERENCES prediction_model_versions (user_id, model_version, contract_version)
        ON DELETE RESTRICT,
    CONSTRAINT ck_prediction_ingestion_api_key_lifecycle CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT ck_prediction_ingestion_api_key_times CHECK (
        (last_used_at IS NULL OR last_used_at >= created_at)
        AND (revoked_at IS NULL OR revoked_at >= created_at)
    )
);

CREATE INDEX ix_prediction_ingestion_api_keys_user_created
    ON prediction_ingestion_api_keys (user_id, created_at, id);

CREATE FUNCTION enforce_prediction_ingestion_api_key_updates()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.model_version IS DISTINCT FROM OLD.model_version
        OR NEW.contract_version IS DISTINCT FROM OLD.contract_version
        OR NEW.key_hash IS DISTINCT FROM OLD.key_hash
        OR NEW.key_prefix IS DISTINCT FROM OLD.key_prefix
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'prediction ingestion API key identity is immutable';
    END IF;

    IF OLD.status = 'REVOKED' THEN
        RAISE EXCEPTION 'revoked prediction ingestion API key is immutable';
    END IF;

    IF NEW.status = 'ACTIVE'
        AND NEW.revoked_at IS NULL
        AND NEW.last_used_at IS NOT NULL
        AND (OLD.last_used_at IS NULL OR NEW.last_used_at >= OLD.last_used_at) THEN
        RETURN NEW;
    END IF;

    IF NEW.status = 'REVOKED'
        AND NEW.revoked_at IS NOT NULL
        AND NEW.last_used_at IS NOT DISTINCT FROM OLD.last_used_at THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid prediction ingestion API key lifecycle transition';
END;
$$;

CREATE TRIGGER trg_enforce_prediction_ingestion_api_key_updates
BEFORE UPDATE ON prediction_ingestion_api_keys
FOR EACH ROW
EXECUTE FUNCTION enforce_prediction_ingestion_api_key_updates();
