-- Widen actor_type constraint on investment_thesis_revisions to include TELEGRAM
DO $$ DECLARE c text; BEGIN
  SELECT conname INTO c FROM pg_constraint
   WHERE conrelid='investment_thesis_revisions'::regclass AND contype='c'
     AND pg_get_constraintdef(oid) LIKE '%actor_type%';
  EXECUTE format('ALTER TABLE investment_thesis_revisions DROP CONSTRAINT %I', c);
END $$;
ALTER TABLE investment_thesis_revisions ADD CONSTRAINT investment_thesis_revisions_actor_type_check
  CHECK (actor_type IN ('USER_SESSION','CONNECTOR_MCP','TELEGRAM'));

-- Telegram thesis approval workflow: 2-step confirmation (승인 -> 최종 승인) with hashed one-time tokens.
-- Only sha256 hex digests of tokens are stored; raw tokens exist only inside Telegram callback_data.
CREATE TABLE investment_thesis_approval_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    candidate_source VARCHAR(32) NOT NULL
        CHECK (candidate_source IN ('EXISTING_PROPOSAL','COMPUTED_ATR','COMPUTED_SUPPORT')),
    candidate_trigger NUMERIC NOT NULL CHECK (candidate_trigger > 0),
    inputs JSONB NOT NULL CHECK (jsonb_typeof(inputs) = 'object'),
    source_as_of TIMESTAMPTZ NOT NULL,
    thesis_expected_updated_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','AWAITING_CONFIRM','APPROVED','HELD','EXPIRED','SUPERSEDED','CONFLICT','FAILED')),
    expires_at TIMESTAMPTZ NOT NULL,
    confirm_expires_at TIMESTAMPTZ,
    token_sha256 VARCHAR(64) NOT NULL UNIQUE CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    confirm_token_sha256 VARCHAR(64) UNIQUE CHECK (confirm_token_sha256 ~ '^[0-9a-f]{64}$'),
    telegram_message_id BIGINT,
    confirm_message_id BIGINT,
    decided_at TIMESTAMPTZ,
    decided_by_telegram_user_id BIGINT,
    status_reason TEXT CHECK (status_reason IS NULL OR char_length(status_reason) <= 500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- PENDING has not issued a stage-2 token; AWAITING_CONFIRM/APPROVED always went through stage 2.
    -- Terminal HELD/EXPIRED/SUPERSEDED/CONFLICT/FAILED may be reached from either stage.
    CONSTRAINT ck_approval_confirm_tokens CHECK (
        (status = 'PENDING' AND confirm_token_sha256 IS NULL AND confirm_expires_at IS NULL)
        OR (status IN ('AWAITING_CONFIRM','APPROVED')
            AND confirm_token_sha256 IS NOT NULL AND confirm_expires_at IS NOT NULL)
        OR status IN ('HELD','EXPIRED','SUPERSEDED','CONFLICT','FAILED')
    )
);

CREATE UNIQUE INDEX uq_open_approval
    ON investment_thesis_approval_requests(user_id, ticker)
    WHERE status IN ('PENDING','AWAITING_CONFIRM');

CREATE INDEX idx_investment_thesis_approval_requests_owner
    ON investment_thesis_approval_requests(user_id, created_at DESC);

-- Dedupe idempotency: prevents double-processing of Telegram webhook updates
CREATE TABLE telegram_webhook_updates (
    update_id BIGINT PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL
);
