-- Widen actor_type constraint on investment_thesis_revisions to include TELEGRAM
DO $$ DECLARE c text; BEGIN
  SELECT conname INTO c FROM pg_constraint
   WHERE conrelid='investment_thesis_revisions'::regclass AND contype='c'
     AND pg_get_constraintdef(oid) LIKE '%actor_type%';
  EXECUTE format('ALTER TABLE investment_thesis_revisions DROP CONSTRAINT %I', c);
END $$;
ALTER TABLE investment_thesis_revisions ADD CONSTRAINT investment_thesis_revisions_actor_type_check
  CHECK (actor_type IN ('USER_SESSION','CONNECTOR_MCP','TELEGRAM'));

-- Telegram approval workflow: two-step confirmation with tokens and expiry
CREATE TABLE investment_thesis_approval_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    ticker TEXT NOT NULL,
    candidate_source VARCHAR(32) NOT NULL
        CHECK (candidate_source IN ('EXISTING_PROPOSAL','COMPUTED_ATR','COMPUTED_SUPPORT')),
    candidate_trigger NUMERIC,
    inputs JSONB NOT NULL CHECK (jsonb_typeof(inputs) = 'object'),
    source_as_of TIMESTAMPTZ NOT NULL,
    thesis_expected_updated_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','AWAITING_CONFIRM','APPROVED','HELD','EXPIRED','SUPERSEDED','CONFLICT','FAILED')),
    expires_at TIMESTAMPTZ NOT NULL,
    confirm_expires_at TIMESTAMPTZ,
    token_sha256 VARCHAR(64) UNIQUE,
    confirm_token_sha256 VARCHAR(64) UNIQUE,
    telegram_message_id BIGINT,
    decided_at TIMESTAMPTZ,
    decided_by_telegram_user_id BIGINT,
    status_reason TEXT CHECK (status_reason IS NULL OR char_length(status_reason) <= 500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_open_approval UNIQUE (user_id, ticker)
        WHERE status IN ('PENDING','AWAITING_CONFIRM'),
    CONSTRAINT ck_approval_confirm_tokens CHECK (
        (status IN ('AWAITING_CONFIRM','APPROVED','HELD','EXPIRED','CONFLICT','FAILED') AND confirm_token_sha256 IS NOT NULL)
        OR (status IN ('PENDING','SUPERSEDED') AND confirm_token_sha256 IS NULL)
    )
);

CREATE INDEX idx_investment_thesis_approval_requests_owner
    ON investment_thesis_approval_requests(user_id, created_at DESC);

-- Dedupe idempotency: prevents double-processing of Telegram webhook updates
CREATE TABLE telegram_webhook_updates (
    update_id BIGINT PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL
);
