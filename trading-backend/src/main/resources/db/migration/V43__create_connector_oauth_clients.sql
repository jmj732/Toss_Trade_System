CREATE TABLE connector_oauth_clients (
    client_id VARCHAR(128) PRIMARY KEY,
    redirect_uris TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
