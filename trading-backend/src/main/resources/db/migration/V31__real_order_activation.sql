ALTER TABLE order_intents
    ADD COLUMN execution_mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    ADD CONSTRAINT ck_order_intent_execution_mode CHECK (execution_mode IN ('PAPER', 'LIVE'));

CREATE TABLE real_order_account_allowlist (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    broker_account_id UUID NOT NULL REFERENCES broker_accounts (id),
    toss_account_seq VARCHAR(32) NOT NULL CHECK (toss_account_seq ~ '^[0-9]+$'),
    display_account_number VARCHAR(20) NOT NULL CHECK (display_account_number ~ '^\*+[^*]{0,4}$'),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    daily_limit_krw NUMERIC(28, 10) NOT NULL CHECK (daily_limit_krw > 0),
    daily_limit_usd NUMERIC(28, 10) NOT NULL CHECK (daily_limit_usd > 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_real_order_allowlist_owner
        FOREIGN KEY (user_id, broker_connection_id)
        REFERENCES broker_connections (user_id, id),
    CONSTRAINT uq_real_order_allowlist_account
        UNIQUE (user_id, broker_connection_id, broker_account_id, toss_account_seq)
);

CREATE INDEX ix_real_order_allowlist_lookup
    ON real_order_account_allowlist (user_id, broker_connection_id, broker_account_id, enabled);

CREATE UNIQUE INDEX uq_real_order_allowlist_enabled_internal_account
    ON real_order_account_allowlist (user_id, broker_connection_id, broker_account_id)
    WHERE enabled = TRUE;

ALTER TABLE broker_orders
    DROP CONSTRAINT ck_broker_orders_status,
    ADD CONSTRAINT ck_broker_orders_status CHECK (status IN (
        'PENDING',
        'PARTIALLY_FILLED',
        'FILLED',
        'CANCELED',
        'REJECTED',
        'CANCEL_REJECTED',
        'REPLACE_REJECTED',
        'REPLACED',
        'CANCELING',
        'REPLACING'
    )) NOT VALID;

ALTER TABLE order_reconciliation_actions
    DROP CONSTRAINT order_reconciliation_actions_action_check,
    ADD CONSTRAINT order_reconciliation_actions_action_check CHECK (action IN (
        'RECONCILIATION_ENTERED',
        'RECONCILIATION_DECIDED',
        'ACCOUNT_LOCK_ENGAGED',
        'ACCOUNT_MAPPING_MISMATCH'
    ));

CREATE TABLE real_order_daily_reservations (
    id UUID PRIMARY KEY,
    allowlist_id UUID NOT NULL REFERENCES real_order_account_allowlist (id),
    order_intent_id UUID NOT NULL UNIQUE REFERENCES order_intents (id),
    usage_date DATE NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('KRW', 'USD')),
    amount NUMERIC(28, 10) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (allowlist_id, order_intent_id)
);

CREATE INDEX ix_real_order_daily_reservations_total
    ON real_order_daily_reservations (allowlist_id, usage_date, currency);

CREATE FUNCTION reject_real_order_allowlist_identity_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.broker_connection_id IS DISTINCT FROM OLD.broker_connection_id
       OR NEW.broker_account_id IS DISTINCT FROM OLD.broker_account_id
       OR NEW.toss_account_seq IS DISTINCT FROM OLD.toss_account_seq THEN
        RAISE EXCEPTION 'real order allowlist identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_real_order_allowlist_identity
BEFORE UPDATE ON real_order_account_allowlist
FOR EACH ROW
EXECUTE FUNCTION reject_real_order_allowlist_identity_change();
