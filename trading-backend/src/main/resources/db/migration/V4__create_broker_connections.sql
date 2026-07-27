CREATE TABLE users (
    id UUID PRIMARY KEY
);

CREATE TABLE broker_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    broker_type VARCHAR(40) NOT NULL CHECK (broker_type = 'TOSS_INVEST'),
    status VARCHAR(30) NOT NULL CHECK (
        status IN ('UNVERIFIED', 'ACTIVE', 'INVALID', 'DELETED')
    ),
    credential_ciphertext BYTEA,
    credential_nonce BYTEA,
    credential_key_version INTEGER,
    credential_revision BIGINT NOT NULL CHECK (credential_revision > 0),
    last_validated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_broker_connection_secret_shape CHECK (
        (
            status <> 'DELETED'
            AND credential_ciphertext IS NOT NULL
            AND octet_length(credential_ciphertext) > 16
            AND credential_nonce IS NOT NULL
            AND octet_length(credential_nonce) = 12
            AND credential_key_version IS NOT NULL
            AND credential_key_version > 0
            AND deleted_at IS NULL
        )
        OR
        (
            status = 'DELETED'
            AND credential_ciphertext IS NULL
            AND credential_nonce IS NULL
            AND credential_key_version IS NULL
            AND last_validated_at IS NULL
            AND deleted_at IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uq_broker_connection_active_user_broker
    ON broker_connections (user_id, broker_type)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_broker_connection_owner
    ON broker_connections (user_id, id);
