CREATE TABLE live_order_operation_idempotency (
    user_id UUID NOT NULL,
    order_intent_id UUID NOT NULL REFERENCES order_intents(id),
    operation VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_FLIGHT', 'ACCEPTED', 'REJECTED', 'UNKNOWN')),
    broker_order_id VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, order_intent_id, operation, idempotency_key)
);

CREATE FUNCTION reject_live_order_operation_identity_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.user_id <> OLD.user_id
       OR NEW.order_intent_id <> OLD.order_intent_id
       OR NEW.operation <> OLD.operation
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.request_hash <> OLD.request_hash
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'live order operation idempotency identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER live_order_operation_idempotency_identity_guard
BEFORE UPDATE ON live_order_operation_idempotency
FOR EACH ROW
EXECUTE FUNCTION reject_live_order_operation_identity_change();

CREATE FUNCTION reject_live_order_operation_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'live order operation idempotency rows are append-only';
END;
$$;

CREATE TRIGGER live_order_operation_idempotency_delete_guard
BEFORE DELETE ON live_order_operation_idempotency
FOR EACH ROW
EXECUTE FUNCTION reject_live_order_operation_delete();
