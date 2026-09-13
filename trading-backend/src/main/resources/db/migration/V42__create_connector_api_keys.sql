CREATE TABLE connector_api_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    key_prefix VARCHAR(13) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    CONSTRAINT fk_connector_api_key_connection
        FOREIGN KEY (user_id, connection_id) REFERENCES broker_connections (user_id, id),
    CONSTRAINT ck_connector_api_key_lifecycle CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'EXPIRED' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT ck_connector_api_key_times CHECK (
        (expires_at IS NULL OR expires_at > created_at)
        AND (last_used_at IS NULL OR last_used_at >= created_at)
    )
);

CREATE INDEX ix_connector_api_key_user_created ON connector_api_keys (user_id, created_at, id);
