CREATE TABLE connector_oauth_refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL REFERENCES connector_oauth_clients (client_id),
    user_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    scope VARCHAR(30) NOT NULL CHECK (scope IN ('connector:read', 'connector:trade')),
    resource VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_connector_oauth_refresh_connection
        FOREIGN KEY (user_id, connection_id) REFERENCES broker_connections (user_id, id),
    CONSTRAINT ck_connector_oauth_refresh_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_connector_oauth_refresh_expiry ON connector_oauth_refresh_tokens (expires_at);
