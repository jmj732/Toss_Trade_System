CREATE TABLE order_intent_audit_logs (
    id UUID PRIMARY KEY,
    order_intent_id UUID NOT NULL REFERENCES order_intents (id),
    from_status VARCHAR(40) NOT NULL CHECK (from_status IN (
        'PROPOSED',
        'APPROVED',
        'REVALIDATING',
        'SUBMISSION_PENDING',
        'RECONCILIATION_REQUIRED',
        'MANUAL_REVIEW_REQUIRED',
        'ACTIVE',
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'CANCELED',
        'REJECTED',
        'EXPIRED',
        'BLOCKED'
    )),
    to_status VARCHAR(40) NOT NULL CHECK (to_status IN (
        'PROPOSED',
        'APPROVED',
        'REVALIDATING',
        'SUBMISSION_PENDING',
        'RECONCILIATION_REQUIRED',
        'MANUAL_REVIEW_REQUIRED',
        'ACTIVE',
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'CANCELED',
        'REJECTED',
        'EXPIRED',
        'BLOCKED'
    )),
    actor VARCHAR(200) NOT NULL,
    terminal_reason VARCHAR(80),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE order_intent_outbox_events (
    id UUID PRIMARY KEY,
    order_intent_id UUID NOT NULL REFERENCES order_intents (id),
    event_type VARCHAR(120) NOT NULL,
    from_status VARCHAR(40) NOT NULL CHECK (from_status IN (
        'PROPOSED',
        'APPROVED',
        'REVALIDATING',
        'SUBMISSION_PENDING',
        'RECONCILIATION_REQUIRED',
        'MANUAL_REVIEW_REQUIRED',
        'ACTIVE',
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'CANCELED',
        'REJECTED',
        'EXPIRED',
        'BLOCKED'
    )),
    to_status VARCHAR(40) NOT NULL CHECK (to_status IN (
        'PROPOSED',
        'APPROVED',
        'REVALIDATING',
        'SUBMISSION_PENDING',
        'RECONCILIATION_REQUIRED',
        'MANUAL_REVIEW_REQUIRED',
        'ACTIVE',
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'CANCELED',
        'REJECTED',
        'EXPIRED',
        'BLOCKED'
    )),
    actor VARCHAR(200) NOT NULL,
    terminal_reason VARCHAR(80),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);

CREATE FUNCTION reject_order_intent_audit_log_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'order intent audit logs are append-only';
END;
$$;

CREATE TRIGGER trg_reject_order_intent_audit_log_change
BEFORE UPDATE OR DELETE ON order_intent_audit_logs
FOR EACH ROW
EXECUTE FUNCTION reject_order_intent_audit_log_change();

CREATE FUNCTION reject_order_intent_outbox_event_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'order intent outbox events cannot be deleted';
    END IF;

    IF (
        NEW.id,
        NEW.order_intent_id,
        NEW.event_type,
        NEW.from_status,
        NEW.to_status,
        NEW.actor,
        NEW.terminal_reason,
        NEW.payload,
        NEW.created_at
    ) IS DISTINCT FROM (
        OLD.id,
        OLD.order_intent_id,
        OLD.event_type,
        OLD.from_status,
        OLD.to_status,
        OLD.actor,
        OLD.terminal_reason,
        OLD.payload,
        OLD.created_at
    ) THEN
        RAISE EXCEPTION 'order intent outbox event business fields are immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_reject_order_intent_outbox_event_change
BEFORE UPDATE OR DELETE ON order_intent_outbox_events
FOR EACH ROW
EXECUTE FUNCTION reject_order_intent_outbox_event_change();
