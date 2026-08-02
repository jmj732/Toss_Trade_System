ALTER TABLE prediction_ingestion_api_keys
    DROP CONSTRAINT prediction_ingestion_api_keys_status_check,
    DROP CONSTRAINT ck_prediction_ingestion_api_key_lifecycle,
    ADD CONSTRAINT prediction_ingestion_api_keys_status_check
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    ADD CONSTRAINT ck_prediction_ingestion_api_key_lifecycle CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'EXPIRED' AND expires_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    );

CREATE OR REPLACE FUNCTION enforce_prediction_ingestion_api_key_updates()
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
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'prediction ingestion API key identity is immutable';
    END IF;

    IF OLD.status IN ('EXPIRED', 'REVOKED') THEN
        RAISE EXCEPTION 'inactive prediction ingestion API key is immutable';
    END IF;

    IF NEW.status = 'ACTIVE'
        AND NEW.revoked_at IS NULL
        AND NEW.last_used_at IS NOT NULL
        AND (OLD.last_used_at IS NULL OR NEW.last_used_at >= OLD.last_used_at) THEN
        RETURN NEW;
    END IF;

    IF NEW.status = 'EXPIRED'
        AND NEW.expires_at IS NOT NULL
        AND NEW.expires_at <= CURRENT_TIMESTAMP
        AND NEW.revoked_at IS NULL
        AND NEW.last_used_at IS NOT DISTINCT FROM OLD.last_used_at THEN
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
