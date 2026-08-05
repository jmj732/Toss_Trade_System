CREATE TABLE auth_refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    authenticated_at TIMESTAMPTZ,
    replaced_by_hash CHAR(64),
    revoked_at TIMESTAMPTZ,
    reuse_detected_at TIMESTAMPTZ,
    CONSTRAINT ck_auth_refresh_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_auth_refresh_replacement_hash CHECK (
        replaced_by_hash IS NULL OR length(replaced_by_hash) = 64
    )
);

CREATE INDEX ix_auth_refresh_user ON auth_refresh_sessions (user_id);
CREATE INDEX ix_auth_refresh_family ON auth_refresh_sessions (family_id);
