CREATE TABLE real_order_canary_runs (
    run_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    broker_account_id UUID NOT NULL,
    request_key_hash CHAR(64) NOT NULL,
    request_fingerprint_hash CHAR(64) NOT NULL,
    outcome VARCHAR(40) NOT NULL DEFAULT 'RUNNING',
    order_submitted BOOLEAN NOT NULL DEFAULT FALSE,
    unknown BOOLEAN NOT NULL DEFAULT FALSE,
    order_intent_id UUID,
    submission_attempt_id UUID,
    result_blockers TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    UNIQUE (user_id, broker_connection_id, broker_account_id, request_key_hash)
);

CREATE UNIQUE INDEX ux_real_order_canary_active_account
    ON real_order_canary_runs (user_id, broker_connection_id, broker_account_id)
    WHERE outcome = 'RUNNING';

CREATE TABLE real_order_canary_audit_events (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    event_number INTEGER NOT NULL CHECK (event_number > 0),
    user_id UUID NOT NULL,
    broker_connection_id UUID,
    broker_account_id UUID,
    order_intent_id UUID,
    submission_attempt_id UUID,
    step VARCHAR(40) NOT NULL CHECK (step IN (
        'PREFLIGHT', 'PROPOSED', 'APPROVED', 'SUBMITTED', 'OPEN_CLOSED_OBSERVED',
        'CANCEL_REQUESTED', 'FINAL_RECONCILIATION', 'BLOCKED'
    )),
    outcome VARCHAR(40) NOT NULL CHECK (outcome IN (
        'READY', 'PREFLIGHT_ONLY', 'ACCEPTED', 'REJECTED', 'UNKNOWN', 'UNSUPPORTED',
        'CANCELED', 'OPEN_REMAINS', 'MANUAL_REVIEW_REQUIRED', 'BLOCKED',
        'BROKER_ORDER_FOUND', 'RETRY_SAME_KEY_ALLOWED', 'PENDING', 'PARTIALLY_FILLED',
        'CANCELING', 'REPLACING', 'FILLED', 'CANCEL_REJECTED', 'REPLACE_REJECTED', 'REPLACED',
        'FINAL_RECONCILED'
    )),
    broker_response_status VARCHAR(30),
    broker_lifecycle_status VARCHAR(30),
    open_orders_complete BOOLEAN NOT NULL,
    closed_orders_complete BOOLEAN NOT NULL,
    unknown BOOLEAN NOT NULL,
    client_order_id_hash CHAR(64),
    broker_order_id_hash CHAR(64),
    evidence JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, event_number)
);

CREATE INDEX ix_real_order_canary_audit_run
    ON real_order_canary_audit_events (run_id, event_number);

CREATE FUNCTION reject_real_order_canary_audit_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'real order canary audit is append-only';
END;
$$;

CREATE TRIGGER trg_reject_real_order_canary_audit_change
BEFORE UPDATE OR DELETE ON real_order_canary_audit_events
FOR EACH ROW
EXECUTE FUNCTION reject_real_order_canary_audit_change();
