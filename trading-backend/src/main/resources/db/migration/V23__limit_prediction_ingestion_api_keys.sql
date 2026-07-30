ALTER TABLE prediction_ingestion_api_keys
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_prediction_ingestion_api_key_expiry
        CHECK (expires_at IS NULL OR expires_at > created_at);

CREATE TABLE prediction_ingestion_api_key_rejections (
    id UUID PRIMARY KEY,
    key_id UUID NOT NULL REFERENCES prediction_ingestion_api_keys (id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    key_prefix VARCHAR(13) NOT NULL
        CHECK (key_prefix ~ '^tpik_[A-Za-z0-9_-]{8}$'),
    reason VARCHAR(20) NOT NULL
        CHECK (reason IN ('EXPIRED', 'RATE_LIMITED')),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_prediction_ingestion_api_key_rejections_key_time
    ON prediction_ingestion_api_key_rejections (key_id, occurred_at);

CREATE FUNCTION reject_prediction_ingestion_api_key_rejection_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'prediction ingestion API key rejection audit is append-only';
END;
$$;

CREATE TRIGGER trg_reject_prediction_ingestion_api_key_rejection_change
BEFORE UPDATE OR DELETE ON prediction_ingestion_api_key_rejections
FOR EACH ROW
EXECUTE FUNCTION reject_prediction_ingestion_api_key_rejection_change();

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
