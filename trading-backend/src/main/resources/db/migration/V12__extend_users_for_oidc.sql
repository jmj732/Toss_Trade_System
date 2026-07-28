ALTER TABLE users
    ADD COLUMN oidc_issuer VARCHAR(500),
    ADD COLUMN oidc_subject VARCHAR(255),
    ADD CONSTRAINT ck_user_oidc_identity_shape CHECK (
        (oidc_issuer IS NULL AND oidc_subject IS NULL)
        OR
        (oidc_issuer IS NOT NULL AND oidc_subject IS NOT NULL)
    ),
    ADD CONSTRAINT uq_user_oidc_identity UNIQUE (oidc_issuer, oidc_subject);
