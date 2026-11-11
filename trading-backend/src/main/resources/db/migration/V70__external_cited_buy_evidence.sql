ALTER TABLE investment_strategic_buy_revalidation_runs
    ADD COLUMN evidence_mode VARCHAR(24) NOT NULL DEFAULT 'PROVIDERS',
    ADD CONSTRAINT ck_strategic_revalidation_run_evidence_mode
        CHECK (evidence_mode IN ('PROVIDERS', 'EXTERNAL_CITED'));

ALTER TABLE investment_strategic_buy_revalidations
    ADD COLUMN evidence_mode VARCHAR(24) NOT NULL DEFAULT 'PROVIDERS',
    ADD COLUMN structured_evidence JSONB,
    ADD COLUMN verdict_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_buy_revalidation_evidence_mode
        CHECK (evidence_mode IN ('PROVIDERS', 'EXTERNAL_CITED')),
    ADD COLUMN verification_status VARCHAR(32),
    ADD CONSTRAINT ck_buy_revalidation_external_evidence
        CHECK (evidence_mode <> 'EXTERNAL_CITED' OR structured_evidence IS NOT NULL),
    ADD CONSTRAINT ck_buy_revalidation_verification_status
        CHECK (verification_status IS NULL OR verification_status = 'SOURCE_NOT_VERIFIED'),
    ADD CONSTRAINT ck_buy_revalidation_source_not_verified
        CHECK ((verification_status IS NULL) = (evidence_mode <> 'EXTERNAL_CITED'));

COMMENT ON COLUMN investment_strategic_buy_revalidations.evidence_mode IS
    'EXTERNAL_CITED rows carry ChatGPT-cited fundamentals that the server cannot verify; they are bounded by TTL and notional at submit.';
COMMENT ON COLUMN investment_strategic_buy_revalidations.verification_status IS
    'SOURCE_NOT_VERIFIED marks every EXTERNAL_CITED review: cited URLs, values and as-of dates are stored but their contents were not checked by the server.';
COMMENT ON COLUMN investment_strategic_buy_revalidations.verdict_reasons IS
    'Consistency and identity reasons that turned an otherwise passing external review into BLOCKED.';
