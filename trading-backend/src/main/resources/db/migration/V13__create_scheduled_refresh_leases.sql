CREATE TABLE scheduled_refresh_leases (
    name VARCHAR(40) PRIMARY KEY,
    owner UUID NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_scheduled_refresh_lease_window CHECK (expires_at > acquired_at)
);
