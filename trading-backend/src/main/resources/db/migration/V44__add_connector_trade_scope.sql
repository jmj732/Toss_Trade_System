ALTER TABLE connector_api_keys
    ADD COLUMN scope VARCHAR(32) NOT NULL DEFAULT 'connector:read',
    ADD CONSTRAINT ck_connector_api_key_scope
        CHECK (scope IN ('connector:read', 'connector:trade'));
