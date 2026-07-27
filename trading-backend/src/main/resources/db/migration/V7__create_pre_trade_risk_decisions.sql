ALTER TABLE account_snapshots
    DROP CONSTRAINT account_snapshots_cash_balance_status_check,
    ADD CONSTRAINT account_snapshots_cash_balance_status_check
        CHECK (cash_balance_status IN ('KNOWN', 'UNKNOWN'));

ALTER TABLE order_intents
    ADD COLUMN user_id UUID,
    ADD COLUMN broker_connection_id UUID,
    ADD CONSTRAINT ck_order_intent_risk_owner CHECK (
        (user_id IS NULL AND broker_connection_id IS NULL)
        OR (user_id IS NOT NULL AND broker_connection_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_order_intent_risk_owner
        FOREIGN KEY (user_id, broker_connection_id)
        REFERENCES broker_connections (user_id, id),
    ADD CONSTRAINT uq_order_intent_risk_owner
        UNIQUE (id, user_id, broker_connection_id);

CREATE TABLE pre_trade_risk_decisions (
    id UUID PRIMARY KEY,
    order_intent_id UUID NOT NULL,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    phase VARCHAR(10) NOT NULL CHECK (phase IN ('APPROVAL', 'FINAL')),
    outcome VARCHAR(10) NOT NULL CHECK (outcome IN ('APPROVED', 'BLOCKED')),
    reason_codes VARCHAR(500) NOT NULL,
    order_amount NUMERIC(28, 10) NOT NULL CHECK (order_amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('KRW', 'USD')),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_pre_trade_risk_snapshot
        FOREIGN KEY (user_id, broker_connection_id, snapshot_id)
        REFERENCES account_sync_runs (user_id, broker_connection_id, id),
    CONSTRAINT fk_pre_trade_risk_order_owner
        FOREIGN KEY (order_intent_id, user_id, broker_connection_id)
        REFERENCES order_intents (id, user_id, broker_connection_id),
    CONSTRAINT ck_pre_trade_risk_reason CHECK (
        (outcome = 'APPROVED' AND reason_codes = '')
        OR (outcome = 'BLOCKED' AND reason_codes <> '')
    )
);

CREATE INDEX ix_pre_trade_risk_reservations
    ON pre_trade_risk_decisions (
        user_id, broker_connection_id, snapshot_id, currency, phase, outcome
    );

CREATE FUNCTION reject_pre_trade_risk_decision_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'pre-trade risk decisions are append-only';
END;
$$;

CREATE TRIGGER trg_reject_pre_trade_risk_decision_change
BEFORE UPDATE OR DELETE ON pre_trade_risk_decisions
FOR EACH ROW
EXECUTE FUNCTION reject_pre_trade_risk_decision_change();

CREATE FUNCTION reject_order_intent_risk_owner_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.user_id, NEW.broker_connection_id)
        IS DISTINCT FROM (OLD.user_id, OLD.broker_connection_id) THEN
        RAISE EXCEPTION 'order intent risk owner is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_reject_order_intent_risk_owner_change
BEFORE UPDATE OF user_id, broker_connection_id ON order_intents
FOR EACH ROW
EXECUTE FUNCTION reject_order_intent_risk_owner_change();
