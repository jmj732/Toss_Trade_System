CREATE TABLE risk_policies (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    version BIGINT NOT NULL CHECK (version > 0),
    max_order_amount_krw NUMERIC(28, 10) NOT NULL CHECK (max_order_amount_krw > 0),
    max_order_amount_usd NUMERIC(28, 10) NOT NULL CHECK (max_order_amount_usd > 0),
    max_quantity NUMERIC(28, 10) NOT NULL CHECK (max_quantity > 0),
    max_concentration NUMERIC(5, 4) NOT NULL
        CHECK (max_concentration > 0 AND max_concentration <= 1),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(255) NOT NULL
);

CREATE TABLE risk_policy_history (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    version BIGINT NOT NULL CHECK (version > 0),
    max_order_amount_krw NUMERIC(28, 10) NOT NULL CHECK (max_order_amount_krw > 0),
    max_order_amount_usd NUMERIC(28, 10) NOT NULL CHECK (max_order_amount_usd > 0),
    max_quantity NUMERIC(28, 10) NOT NULL CHECK (max_quantity > 0),
    max_concentration NUMERIC(5, 4) NOT NULL
        CHECK (max_concentration > 0 AND max_concentration <= 1),
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_risk_policy_history_version UNIQUE (user_id, version)
);

CREATE INDEX ix_risk_policy_history_user
    ON risk_policy_history (user_id, version DESC);

CREATE FUNCTION reject_risk_policy_history_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'risk policy history is append-only';
END;
$$;

CREATE TRIGGER trg_reject_risk_policy_history_change
BEFORE UPDATE OR DELETE ON risk_policy_history
FOR EACH ROW
EXECUTE FUNCTION reject_risk_policy_history_change();

-- Pins each risk decision to the policy version that actually produced it, so editing a
-- user's policy later can never retroactively change what an already-recorded decision
-- meant. 0 means "no personal policy existed yet — platform defaults applied" and remains a
-- permanently valid value, not a backfill placeholder to migrate away from.
ALTER TABLE pre_trade_risk_decisions
    ADD COLUMN risk_policy_version BIGINT NOT NULL DEFAULT 0;
